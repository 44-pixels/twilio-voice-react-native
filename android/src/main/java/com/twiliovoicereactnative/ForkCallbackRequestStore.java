// FORK — KAR-873
// Owns: Telecom UUID mappings and durable callback-request delivery.
// Hooks into: ForkCoreTelecomManager, ForkCallBackActivity, VoiceModuleProxy.
// Re-check on SDK bump: CallsManager call IDs and Telecom callback extras.
package com.twiliovoicereactnative;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import java.util.Map;
import java.util.UUID;

final class ForkCallbackRequestStore {
  static final String EXTRA_UUID = "android.telecom.extra.UUID";

  private static final String PREFERENCES = "twilio_voice_callback_requests";
  private static final String MAPPING_PREFIX = "telecom_uuid.";
  private static final String PENDING_REQUEST_ID = "pending.request_id";
  private static final String PENDING_HANDLE = "pending.handle";
  private static final int MAXIMUM_MAPPINGS = 200;

  static final class CallbackRequest {
    final String requestId;
    final String handle;

    CallbackRequest(@NonNull String requestId, @NonNull String handle) {
      this.requestId = requestId;
      this.handle = handle;
    }
  }

  private ForkCallbackRequestStore() {}

  static synchronized void rememberCall(
    @NonNull Context context,
    @NonNull UUID telecomUuid,
    @Nullable String handle
  ) {
    if (handle == null || handle.isEmpty()) return;

    SharedPreferences preferences = preferences(context);
    String mappingKey = MAPPING_PREFIX + telecomUuid;
    boolean mappingExists = preferences.contains(mappingKey);
    SharedPreferences.Editor editor = preferences.edit().putString(mappingKey, handle);
    int mappingCount = 0;
    String mappingToRemove = null;
    for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
      if (!entry.getKey().startsWith(MAPPING_PREFIX)) continue;
      mappingCount += 1;
      if (!entry.getKey().equals(mappingKey)) mappingToRemove = entry.getKey();
    }
    if (!mappingExists && mappingCount >= MAXIMUM_MAPPINGS && mappingToRemove != null) {
      editor.remove(mappingToRemove);
    }
    editor.commit();
  }

  @Nullable
  static synchronized CallbackRequest recordCallbackRequest(
    @NonNull Context context,
    @Nullable String telecomUuidValue
  ) {
    UUID telecomUuid = parseUuid(telecomUuidValue);
    if (telecomUuid == null) return null;

    SharedPreferences preferences = preferences(context);
    String handle = preferences.getString(MAPPING_PREFIX + telecomUuid, null);
    if (handle == null || handle.isEmpty()) return null;

    CallbackRequest request = new CallbackRequest(UUID.randomUUID().toString(), handle);
    preferences.edit()
      .putString(PENDING_REQUEST_ID, request.requestId)
      .putString(PENDING_HANDLE, request.handle)
      .commit();
    return request;
  }

  @Nullable
  static synchronized CallbackRequest pending(@NonNull Context context) {
    SharedPreferences preferences = preferences(context);
    String requestId = preferences.getString(PENDING_REQUEST_ID, null);
    String handle = preferences.getString(PENDING_HANDLE, null);
    if (requestId == null || handle == null) return null;

    return new CallbackRequest(requestId, handle);
  }

  static void clearCallbackRequest(
    @NonNull Context context,
    @NonNull String requestId,
    @NonNull ModuleProxy.UniversalPromise promise
  ) {
    clearPending(context, requestId);
    promise.resolve(null);
  }

  static void emit(
    @NonNull JSEventEmitter emitter,
    @NonNull CallbackRequest request
  ) {
    emitter.sendEvent(
      CommonConstants.ScopeVoice,
      JSEventEmitter.constructJSMap(
        new Pair<>(CommonConstants.VoiceEventType, CommonConstants.VoiceEventCallbackRequested),
        new Pair<>(CommonConstants.CallbackRequestKeyRequestId, request.requestId),
        new Pair<>(CommonConstants.CallbackRequestKeyHandle, request.handle)));
  }

  static void getInitialCallbackRequest(
    @NonNull Context context,
    @NonNull ModuleProxy.UniversalPromise promise
  ) {
    CallbackRequest request = pending(context);
    if (request == null) {
      promise.resolve(null);
      return;
    }

    WritableMap payload = Arguments.createMap();
    payload.putString(CommonConstants.CallbackRequestKeyRequestId, request.requestId);
    payload.putString(CommonConstants.CallbackRequestKeyHandle, request.handle);
    promise.resolve(payload);
  }

  static synchronized void clearPending(
    @NonNull Context context,
    @NonNull String requestId
  ) {
    SharedPreferences preferences = preferences(context);
    if (!requestId.equals(preferences.getString(PENDING_REQUEST_ID, null))) return;

    preferences.edit()
      .remove(PENDING_REQUEST_ID)
      .remove(PENDING_HANDLE)
      .commit();
  }

  @Nullable
  private static UUID parseUuid(@Nullable String value) {
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static SharedPreferences preferences(@NonNull Context context) {
    return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
  }
}
