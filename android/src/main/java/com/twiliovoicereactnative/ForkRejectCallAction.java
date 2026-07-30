// FORK — KAR-492, KAR-809
// Owns: notification Decline action handling outside VoiceService, including
// atomic ownership of live-record rejection.
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

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.CallInvite;

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

    CallInvite callInvite = ForkCallInviteSettlement.claim(
      callRecord, ForkCallInviteSettlement.Action.REJECT);
    if (callInvite == null) {
      finishDeclineKey(actionKey);
      return;
    }
    ForkCallInviteSettlement.rejectCompetingAction(
      callRecord, ForkCallInviteSettlement.Action.REJECT);

    ForkCallInviteRejection.rejectClaimed(context, callInvite, callRecord);
    finishDeclineKey(actionKey);
  }
}
