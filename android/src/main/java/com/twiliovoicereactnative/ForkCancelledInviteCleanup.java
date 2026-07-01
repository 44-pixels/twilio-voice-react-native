// FORK — KAR-492
// Owns: cancelled-invite cleanup when the in-memory CallRecord is gone.
// Hooks into: VoiceFirebaseMessagingService.MessageHandler.onCancelledCallInvite.
// Re-check on SDK bump: whether upstream still requireNonNulls the removed
// CallRecord on cancelled invites.
//
// If Android kills the process after an incoming notification is posted, the
// CallRecordDatabase is empty when a later cancelled-invite FCM is handled.
// Upstream crashes/returns before notification cleanup because the random
// notification id lived only in that CallRecord. Our incoming ids are derived
// from callSid, so cancelled invites can remove stale notifications directly.
package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.CancelledCallInvite;

public final class ForkCancelledInviteCleanup {
  private static final SDKLog logger = new SDKLog(ForkCancelledInviteCleanup.class);

  private ForkCancelledInviteCleanup() {}

  @Nullable
  public static CallRecordDatabase.CallRecord removeRecordOrCancelNotification(
    @NonNull CancelledCallInvite cancelledCallInvite
  ) {
    CallRecordDatabase.CallRecord callRecord = getCallRecordDatabase()
      .get(new CallRecordDatabase.CallRecord(cancelledCallInvite.getCallSid()));
    ForkInvitePayloadStore.clear(cancelledCallInvite.getCallSid());
    if (callRecord != null && isAcceptedOrActive(callRecord)) {
      logger.warning(
        "stale cancelled invite for accepted call; keeping CallRecord callSid="
          + cancelledCallInvite.getCallSid());
      ForkVoiceMessageGuard.markSettled(cancelledCallInvite.getCallSid());
      return null;
    }
    if (callRecord != null) return getCallRecordDatabase().remove(callRecord);

    if (ForkNotificationIdentity.cancelForCallSid(
      VoiceApplicationProxy.getApplicationContext(),
      cancelledCallInvite.getCallSid())) {
      logger.warning(
        "cancelled invite had no CallRecord; removed notification for callSid="
          + cancelledCallInvite.getCallSid());
    } else {
      logger.warning(
        "cancelled invite had no CallRecord and no notification id for callSid="
          + cancelledCallInvite.getCallSid());
    }
    ForkVoiceMessageGuard.markSettled(cancelledCallInvite.getCallSid());
    return null;
  }

  private static boolean isAcceptedOrActive(@NonNull CallRecordDatabase.CallRecord callRecord) {
    return callRecord.getCallInviteState() == CallRecordDatabase.CallRecord.CallInviteState.USED
      || callRecord.getVoiceCall() != null;
  }
}
