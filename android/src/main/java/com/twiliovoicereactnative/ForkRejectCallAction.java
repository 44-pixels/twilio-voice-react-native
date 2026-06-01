// FORK — KAR-492
// Owns: notification Decline action handling outside VoiceService.
// Hooks into: ForkNotificationActionReceiver and VoiceService ACTION_REJECT_CALL
//             null-record fallback.
// Re-check on SDK bump: whether upstream still routes Decline through a
// getService PendingIntent and in-memory CallRecord only.
//
// A notification action may run after the app task/process was killed. In that
// state the in-memory CallRecord and native CallInviteProxy can be gone. First
// prefer the live CallRecord path; otherwise replay the original invite FCM
// payload so Twilio recreates the proxy and can receive a real reject.
package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyCallSid;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyType;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueRejected;
import static com.twiliovoicereactnative.CommonConstants.ScopeCallInvite;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CALL_INVITE_INFO;
import static com.twiliovoicereactnative.JSEventEmitter.constructJSMap;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCallInvite;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getJSEventEmitter;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ForkRejectCallAction {
  private static final SDKLog logger = new SDKLog(ForkRejectCallAction.class);
  private static final Set<String> inFlightDeclines = ConcurrentHashMap.newKeySet();

  private ForkRejectCallAction() {}

  public static boolean rejectFromIntent(@NonNull Context context, @Nullable Intent intent) {
    return rejectFromIntent(context, intent, () -> {});
  }

  public static boolean rejectFromIntent(@NonNull Context context,
                                         @Nullable Intent intent,
                                         @NonNull Runnable onComplete) {
    logger.log("Decline action started");
    String actionKey = actionKey(intent);
    if (!inFlightDeclines.add(actionKey)) {
      logger.log("Decline action already in flight key=" + actionKey);
      onComplete.run();
      return true;
    }

    CallRecordDatabase.CallRecord callRecord = ForkCallRecordLookup.getOrNull(
      ForkCallRecordLookup.readUuid(intent));
    if (callRecord != null) {
      logger.log("Decline action using live CallRecord " + callRecord.getUuid());
      rejectLiveRecord(context, callRecord, actionKey);
      onComplete.run();
      return true;
    }

    logger.log("Decline action has no live CallRecord; trying stored invite payload");
    if (ForkInvitePayloadStore.rejectFromIntent(context, intent, actionKey, onComplete)) {
      finishDeclineKey(actionKey);
      return true;
    }

    logger.warning("Decline action had no live CallRecord and no replayable invite payload");
    ForkNotificationIdentity.cancelFromIntent(context, intent);
    inFlightDeclines.remove(actionKey);
    onComplete.run();
    return false;
  }

  static void finishDecline(@Nullable String callSid) {
    finishDeclineKey(callSid);
  }

  static void finishDeclineKey(@Nullable String actionKey) {
    if (actionKey == null || actionKey.isEmpty()) return;
    new Handler(Looper.getMainLooper()).postDelayed(
      () -> inFlightDeclines.remove(actionKey),
      30000);
  }

  private static String actionKey(@Nullable Intent intent) {
    String callSid = ForkNotificationIdentity.callSidFromIntent(intent);
    if (callSid != null && !callSid.isEmpty()) return callSid;
    int notificationId = ForkNotificationIdentity.notificationIdFromIntent(intent);
    if (notificationId > 0) return String.valueOf(notificationId);
    return "missing-call-identity";
  }

  private static void rejectLiveRecord(@NonNull Context context,
                                       @NonNull CallRecordDatabase.CallRecord callRecord,
                                       @NonNull String actionKey) {
    logger.debug("rejectLiveRecord: " + callRecord.getUuid());

    getCallRecordDatabase().remove(callRecord);

    VoiceService.removeForegroundNotificationIfRunning();
    VoiceApplicationProxy.getMediaPlayerManager().stop();
    VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().deactivate();

    if (callRecord.getCallInvite() == null) {
      logger.warning("Decline action found CallRecord without CallInvite " + callRecord.getUuid());
      ForkNotificationIdentity.cancelForCallSid(context, callRecord.getCallSid());
      ForkVoiceMessageGuard.markSettled(callRecord.getCallSid());
      finishDeclineKey(actionKey);
      return;
    }

    callRecord.getCallInvite().reject(context.getApplicationContext());
    callRecord.setCallInviteUsedState();
    ForkInvitePayloadStore.clear(callRecord.getCallSid());
    ForkNotificationIdentity.cancelForCallSid(context, callRecord.getCallSid());
    ForkVoiceMessageGuard.markSettled(callRecord.getCallSid());
    finishDeclineKey(actionKey);

    ForkLockScreenFlags.clearForEndedCall();

    if (callRecord.getCallRejectedPromise() != null) {
      callRecord.getCallRejectedPromise().resolve(callRecord.getUuid().toString());
    }

    getJSEventEmitter().sendEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueRejected),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid()),
        new Pair<>(JS_EVENT_KEY_CALL_INVITE_INFO, serializeCallInvite(callRecord))));
  }
}
