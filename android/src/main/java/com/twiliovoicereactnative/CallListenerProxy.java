package com.twiliovoicereactnative;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.WritableMap;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;

import static com.twiliovoicereactnative.CommonConstants.CallEventConnected;
import static com.twiliovoicereactnative.CommonConstants.CallEventDisconnected;
import static com.twiliovoicereactnative.CommonConstants.CallEventReconnected;
import static com.twiliovoicereactnative.CommonConstants.CallEventReconnecting;
import static com.twiliovoicereactnative.CommonConstants.CallEventRinging;
import static com.twiliovoicereactnative.CommonConstants.ScopeCall;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventType;
import static com.twiliovoicereactnative.CommonConstants.VoiceErrorKeyError;
import static com.twiliovoicereactnative.CommonConstants.CallEventCurrentWarnings;
import static com.twiliovoicereactnative.CommonConstants.CallEventPreviousWarnings;
import static com.twiliovoicereactnative.CommonConstants.CallEventConnectFailure;
import static com.twiliovoicereactnative.CommonConstants.CallEventQualityWarningsChanged;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CALL_INFO;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getJSEventEmitter;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getMediaPlayerManager;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getVoiceServiceApi;
import static com.twiliovoicereactnative.JSEventEmitter.constructJSMap;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.*;

import com.twiliovoicereactnative.CallRecordDatabase.CallRecord;

import java.util.Date;
import java.util.Set;
import java.util.UUID;

class CallListenerProxy implements Call.Listener {
  private static final SDKLog logger = new SDKLog(CallListenerProxy.class);
  private final UUID uuid;
  private final Context context;

  public CallListenerProxy(UUID uuid, Context context) {
    this.uuid = uuid;
    this.context = context;
  }

  @Override
  public void onConnectFailure(@NonNull Call call, @NonNull CallException callException) {
    debug("onConnectFailure");

    // >>> FORK KAR-857 — see ForkCallIssueState.java
    ForkCallIssueState.ended(uuid);
    // <<< FORK

    // stop sound
    getMediaPlayerManager().stop();
    // >>> FORK KAR-443 — owner-aware routing cleanup runs via onDisconnected below
    // Do not deactivate AudioSwitch until call ownership is known.
    // <<< FORK

    // find call record & remove
    // >>> FORK KAR-685 — see ForkCallListenerRecordGuard.java
    CallRecord callRecord = ForkCallListenerRecordGuard.removeOrNull("onConnectFailure", uuid, call);
    if (callRecord == null) return;
    // <<< FORK
    // take down notification
    getVoiceServiceApi().cancelActiveCallNotification(callRecord);
    // >>> FORK KAR-443 — release only after owner-scoped cleanup
    ForkCallLifecycleCoordinator.twilioDisconnected(callRecord, callException);
    // <<< FORK

    // serialize and notify JS
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventConnectFailure),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeVoiceException(callException))));
  }

  @Override
  public void onRinging(@NonNull Call call) {
    debug("onRinging");

    // find call record
    // >>> FORK KAR-685 — see ForkCallListenerRecordGuard.java
    CallRecord callRecord = ForkCallListenerRecordGuard.getOrNull("onRinging", uuid, call);
    if (callRecord == null) return;
    // <<< FORK
    callRecord.setCall(call);
    // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
    ForkCallLifecycleCoordinator.twilioRinging(callRecord);
    // <<< FORK

    // create notification & sound
    // >>> FORK KAR-443 — outgoing foregrounding may allocate the ID before ringing
    if (callRecord.getNotificationId() < 0) {
      callRecord.setNotificationId(NotificationUtility.createNotificationIdentifier());
    }
    // <<< FORK
    // >>> FORK KAR-443 — Core Telecom owns audio for registered outgoing calls
    ForkCallLifecycleCoordinator.activateFallbackAudio(callRecord);
    // <<< FORK
    getMediaPlayerManager().play(MediaPlayerManager.SoundTable.RINGTONE);
    getVoiceServiceApi().raiseOutgoingCallNotification(callRecord);

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventRinging),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord))));
  }

  @Override
  public void onConnected(@NonNull Call call) {
    debug("onConnected");

    // find call record
    // >>> FORK KAR-685 — see ForkCallListenerRecordGuard.java
    CallRecord callRecord = ForkCallListenerRecordGuard.getOrNull("onConnected", uuid, call);
    if (callRecord == null) return;
    // <<< FORK
    callRecord.setCall(call);
    callRecord.setTimestamp(new Date());
    getMediaPlayerManager().stop();
    // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
    ForkCallLifecycleCoordinator.twilioConnected(callRecord);
    // <<< FORK
    // >>> FORK KAR-857 — see ForkCallIssueState.java
    ForkCallIssueState.connected(uuid);
    // <<< FORK

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventConnected),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord))));
  }

  @Override
  public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
    debug("onReconnecting");

    // find & update call record
    // >>> FORK KAR-685 — see ForkCallListenerRecordGuard.java
    CallRecord callRecord = ForkCallListenerRecordGuard.getOrNull("onReconnecting", uuid, call);
    if (callRecord == null) return;
    // <<< FORK
    // >>> FORK KAR-857 — see ForkCallIssueState.java
    ForkCallIssueState.reconnecting(uuid);
    // <<< FORK

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventReconnecting),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeVoiceException(callException))));
  }

  @Override
  public void onReconnected(@NonNull Call call) {
    debug("onReconnected");

    // find & update call record
    // >>> FORK KAR-685 — see ForkCallListenerRecordGuard.java
    CallRecord callRecord = ForkCallListenerRecordGuard.getOrNull("onReconnected", uuid, call);
    if (callRecord == null) return;
    // <<< FORK
    // >>> FORK KAR-857 — see ForkCallIssueState.java
    ForkCallIssueState.reconnected(uuid);
    // <<< FORK

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventReconnected),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord))));
  }

  @Override
  public void onDisconnected(@NonNull Call call, @Nullable CallException callException) {
    debug("onDisconnected");

    // >>> FORK KAR-857 — see ForkCallIssueState.java
    ForkCallIssueState.ended(uuid);
    // <<< FORK

    // find & remove call record
    // >>> FORK KAR-685 — see ForkCallListenerRecordGuard.java
    CallRecord callRecord = ForkCallListenerRecordGuard.removeOrNull("onDisconnected", uuid, call);
    if (callRecord == null) return;
    // <<< FORK
    // stop audio & cancel notification
    // >>> FORK KAR-443, KAR-787 — retain the call route through the call-ended sound
    ForkCallLifecycleCoordinator.twilioDisconnectedWithSound(context, callRecord, callException);
    // <<< FORK

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventDisconnected),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeVoiceException(callException))));
  }

  @Override
  public void onCallQualityWarningsChanged(@NonNull Call call,
                                           @NonNull Set<Call.CallQualityWarning> currentWarnings,
                                           @NonNull Set<Call.CallQualityWarning> previousWarnings) {
    debug("onCallQualityWarningsChanged");

    // find call record
    // >>> FORK KAR-685 — see ForkCallListenerRecordGuard.java
    CallRecord callRecord = ForkCallListenerRecordGuard.getOrNull("onCallQualityWarningsChanged", uuid, call);
    if (callRecord == null) return;
    // <<< FORK
    // >>> FORK KAR-857 — see ForkCallIssueState.java
    ForkCallIssueState.qualityWarningsChanged(uuid, currentWarnings);
    // <<< FORK

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventQualityWarningsChanged),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(CallEventCurrentWarnings, serializeCallQualityWarnings(currentWarnings)),
        new Pair<>(CallEventPreviousWarnings, serializeCallQualityWarnings(previousWarnings))));
  }

  private void sendJSEvent(@NonNull WritableMap event) {
    getJSEventEmitter().sendEvent(ScopeCall, event);
  }

  private void debug(final String message) {
    logger.debug(String.format("%s UUID:%s", message, uuid.toString()));
  }
}
