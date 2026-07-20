// FORK — KAR-492, KAR-809
// Owns: atomic cancelled-invite settlement and cleanup when the in-memory
// CallRecord is gone.
// Hooks into: VoiceFirebaseMessagingService.MessageHandler.onCancelledCallInvite.
// Re-check on SDK bump: whether upstream still requireNonNulls the removed
// CallRecord and where cancellation competes with accept/reject.
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

import com.twilio.voice.CallException;
import com.twilio.voice.CancelledCallInvite;

public final class ForkCancelledInviteCleanup {
  private static final SDKLog logger = new SDKLog(ForkCancelledInviteCleanup.class);

  private ForkCancelledInviteCleanup() {}

  @Nullable
  public static CallRecordDatabase.CallRecord settleOrCancelNotification(
    @NonNull CancelledCallInvite cancelledCallInvite,
    @Nullable CallException callException
  ) {
    CallRecordDatabase.CallRecord callRecord = getCallRecordDatabase()
      .get(new CallRecordDatabase.CallRecord(cancelledCallInvite.getCallSid()));
    ForkInvitePayloadStore.clear(cancelledCallInvite.getCallSid());

    if (callRecord != null) {
      boolean cancellationWon = ForkCallInviteSettlement.cancel(
        callRecord, cancelledCallInvite, callException);
      if (!cancellationWon) {
        logger.warning(
          "stale cancelled invite for settled call; keeping CallRecord callSid="
            + cancelledCallInvite.getCallSid());
        ForkVoiceMessageGuard.markSettled(cancelledCallInvite.getCallSid());
        return null;
      }

      ForkCallInviteSettlement.rejectPendingActions(callRecord);
      return callRecord;
    }

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

  public static void removeSettledRecord(
    @NonNull CallRecordDatabase.CallRecord callRecord
  ) {
    getCallRecordDatabase().remove(callRecord);
  }
}
