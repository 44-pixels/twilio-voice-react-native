// FORK — KAR-878
// Owns: native Twilio Voice operational reporting to the host application's Sentry SDK.
// Hooks into: Android call, registration, messaging, preflight, and platform failure paths.
// Re-check on SDK bump: Sentry Android capture APIs, Twilio call states, and callback ordering.
package com.twiliovoicereactnative;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.Call;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.sentry.Breadcrumb;
import io.sentry.IScope;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;

final class ForkSentryReporter {
  private static final String CATEGORY = "twilio.voice";
  private static final String OPERATION_TAG = "twilio_voice.operation";
  private static final Map<UUID, CallDiagnosticRecord> callRecords = new ConcurrentHashMap<>();

  private static final class CallDiagnosticRecord {
    private final long createdAtElapsedRealtime = SystemClock.elapsedRealtime();
    private boolean lifecycleObserved;
    private boolean ringingObserved;
    private boolean connectedObserved;
    @Nullable private Map<String, Object> pendingStuckCancellationData;
  }

  private ForkSentryReporter() {}

  static void reportError(@NonNull String operation, @Nullable Throwable cause) {
    reportError(operation, cause, Collections.emptyMap());
  }

  static void reportError(@NonNull String operation,
                          @Nullable Throwable cause,
                          @NonNull Map<String, Object> data) {
    if (cause == null) {
      Sentry.captureMessage(operation, SentryLevel.ERROR, scope ->
        enrichScope(scope, operation, data));
      return;
    }

    Sentry.captureException(cause, scope -> enrichScope(scope, operation, data));
  }

  static void reportWarning(@NonNull String operation, @Nullable Throwable cause) {
    reportWarning(operation, cause, Collections.emptyMap());
  }

  static void reportWarning(@NonNull String operation,
                            @Nullable Throwable cause,
                            @NonNull Map<String, Object> data) {
    if (cause == null) {
      Sentry.captureMessage(operation, SentryLevel.WARNING, scope ->
        enrichScope(scope, operation, data));
      return;
    }

    final SentryEvent event = new SentryEvent(cause);
    event.setLevel(SentryLevel.WARNING);
    Sentry.captureEvent(event, scope -> enrichScope(scope, operation, data));
  }

  static void addBreadcrumb(@NonNull String operation) {
    addBreadcrumb(operation, Collections.emptyMap());
  }

  static void addBreadcrumb(@NonNull String operation, @NonNull Map<String, Object> data) {
    final Breadcrumb breadcrumb = new Breadcrumb(operation);
    breadcrumb.setCategory(CATEGORY);
    breadcrumb.setLevel(SentryLevel.INFO);
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      breadcrumb.setData(entry.getKey(), entry.getValue());
    }
    Sentry.addBreadcrumb(breadcrumb);
  }

  static void recordCallCreation(@NonNull UUID uuid) {
    callRecords.put(uuid, new CallDiagnosticRecord());
  }

  static void recordConnectResult(@NonNull UUID uuid,
                                  @Nullable Call call,
                                  int nativeCallMapSize) {
    final Map<String, Object> data = callData(uuid, call);
    data.put("call_returned", call != null);
    data.put("initial_call_state", stateName(call));
    data.put("native_call_map_size", nativeCallMapSize);

    addBreadcrumb("voice.call.connect_returned", data);
    if (call == null) {
      callRecords.remove(uuid);
      reportError("voice.call.connect_returned_null", null, data);
    }
  }

  static void recordConnectException(@NonNull String operation,
                                     @NonNull UUID uuid,
                                     @NonNull Throwable cause,
                                     int nativeCallMapSize) {
    final Map<String, Object> data = callData(uuid, null);
    data.put("native_call_map_size", nativeCallMapSize);
    callRecords.remove(uuid);
    reportError(operation, cause, data);
  }

  static void recordLifecycle(@NonNull String operation,
                              @NonNull UUID uuid,
                              @NonNull Call call,
                              @Nullable Throwable cause) {
    final Map<String, Object> data = callData(uuid, call);
    final CallDiagnosticRecord record = callRecords.get(uuid);
    if (record != null) {
      synchronized (record) {
        record.lifecycleObserved = true;
        if ("voice.call.ringing".equals(operation)) record.ringingObserved = true;
        if ("voice.call.connected".equals(operation)) record.connectedObserved = true;
      }
    }

    addBreadcrumb(operation, data);
    if (cause != null) {
      final String errorOperation = "voice.call.disconnected".equals(operation)
        ? "voice.call.disconnected_with_error"
        : operation;
      reportError(errorOperation, cause, data);
    }

    if ("voice.call.disconnected".equals(operation)
      || "voice.call.connect_failed".equals(operation)) {
      callRecords.remove(uuid);
    }
  }

  static void recordEndCallAction(@NonNull UUID uuid,
                                  boolean callMapContainsUuid,
                                  @Nullable Call call,
                                  boolean willInvokeDisconnect,
                                  int nativeCallMapSize) {
    final CallDiagnosticRecord record = callRecords.get(uuid);
    final Map<String, Object> data = callData(uuid, call);
    data.put("call_map_contains_uuid", callMapContainsUuid);
    data.put("native_call_map_size", nativeCallMapSize);
    data.put("diagnostic_record_found", record != null);

    boolean stuckCancellation = false;
    if (record == null) {
      data.put("ringing_observed", false);
      data.put("connected_observed", false);
      data.put("elapsed_since_creation_ms", null);
    } else {
      synchronized (record) {
        data.put("ringing_observed", record.ringingObserved);
        data.put("connected_observed", record.connectedObserved);
        data.put(
          "elapsed_since_creation_ms",
          SystemClock.elapsedRealtime() - record.createdAtElapsedRealtime);
        stuckCancellation = !record.lifecycleObserved;
        if (stuckCancellation && willInvokeDisconnect) record.pendingStuckCancellationData = data;
      }
    }

    addBreadcrumb("voice.call.end_action", data);
    if (stuckCancellation && !willInvokeDisconnect) {
      reportWarning("voice.call.cancelled_while_stuck_connecting", null, data);
    }
    if (!stuckCancellation || !willInvokeDisconnect) callRecords.remove(uuid);
  }

  static void recordDisconnectBoundary(@NonNull String operation,
                                       @NonNull UUID uuid,
                                       @NonNull Call call) {
    addBreadcrumb(operation, callData(uuid, call));
    if (!"voice.call.disconnect_invocation.after".equals(operation)) return;

    final CallDiagnosticRecord record = callRecords.remove(uuid);
    if (record == null) return;

    final Map<String, Object> stuckCancellationData;
    synchronized (record) {
      stuckCancellationData = record.pendingStuckCancellationData;
    }
    if (stuckCancellationData != null) {
      reportWarning("voice.call.cancelled_while_stuck_connecting", null, stuckCancellationData);
    }
  }

  static void recordAudioSession(@NonNull String operation, @NonNull UUID uuid) {
    final Map<String, Object> data = new HashMap<>();
    data.put("call_uuid", uuid.toString());
    addBreadcrumb(operation, data);
  }

  @NonNull
  private static Map<String, Object> callData(@NonNull UUID uuid, @Nullable Call call) {
    final Map<String, Object> data = new HashMap<>();
    data.put("call_uuid", uuid.toString());
    data.put("call_state", stateName(call));
    return data;
  }

  @Nullable
  private static String stateName(@Nullable Call call) {
    return call == null ? null : call.getState().name().toLowerCase(Locale.US);
  }

  private static void enrichScope(@NonNull IScope scope,
                                  @NonNull String operation,
                                  @NonNull Map<String, Object> data) {
    scope.setTag(OPERATION_TAG, operation);
    scope.setContexts("twilio_voice", data);
  }
}
