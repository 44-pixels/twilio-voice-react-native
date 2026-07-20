// FORK — KAR-809
// Owns: atomic ownership of an incoming CallInvite's terminal transition.
// Hooks into: VoiceService accept/reject, ForkRejectCallAction, and
//             ForkCancelledInviteCleanup.
// Re-check on SDK bump: every path that accepts, rejects, or cancels a
// CallInvite must claim its transition here before producing side effects.
//
// Twilio cancellation and user actions can arrive on different threads while
// holding the same CallRecord. Synchronizing on that record makes ACTIVE the
// single claimable state. The winner captures the invite before changing the
// state and invokes Twilio after leaving the monitor; losing paths perform no
// call-lifecycle side effects.
package com.twiliovoicereactnative;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;

final class ForkCallInviteSettlement {
  enum Action {
    ACCEPT,
    REJECT
  }

  private static final SDKLog logger = new SDKLog(ForkCallInviteSettlement.class);

  private ForkCallInviteSettlement() {}

  @Nullable
  static CallInvite claim(@NonNull CallRecordDatabase.CallRecord callRecord,
                          @NonNull Action action) {
    synchronized (callRecord) {
      if (callRecord.getCallInviteState()
        != CallRecordDatabase.CallRecord.CallInviteState.ACTIVE) {
        logger.log(
          "Ignoring " + action + " for settled CallInvite " + callRecord.getUuid());
        return null;
      }

      CallInvite callInvite = callRecord.getCallInvite();
      if (callInvite == null) {
        logger.warning(
          "ACTIVE CallRecord has no CallInvite " + callRecord.getUuid());
        callRecord.setCallInviteUsedState();
        return null;
      }

      callRecord.setCallInviteUsedState();
      return callInvite;
    }
  }

  static void rejectPendingAction(@NonNull CallRecordDatabase.CallRecord callRecord,
                                  @NonNull Action action) {
    ModuleProxy.UniversalPromise promise = action == Action.ACCEPT
      ? callRecord.getCallAcceptedPromise()
      : callRecord.getCallRejectedPromise();
    if (promise == null) return;

    promise.rejectWithName(
      CommonConstants.ErrorCodeInvalidStateError,
      "Call invite was already settled");
  }

  static void rejectCompetingAction(@NonNull CallRecordDatabase.CallRecord callRecord,
                                    @NonNull Action winningAction) {
    rejectPendingAction(
      callRecord,
      winningAction == Action.ACCEPT ? Action.REJECT : Action.ACCEPT);
  }

  static void rejectPendingActions(@NonNull CallRecordDatabase.CallRecord callRecord) {
    rejectPendingAction(callRecord, Action.ACCEPT);
    rejectPendingAction(callRecord, Action.REJECT);
  }

  static boolean cancel(@NonNull CallRecordDatabase.CallRecord callRecord,
                        @NonNull CancelledCallInvite cancelledCallInvite,
                        @Nullable CallException callException) {
    synchronized (callRecord) {
      if (callRecord.getCallInviteState()
        != CallRecordDatabase.CallRecord.CallInviteState.ACTIVE
        || callRecord.getCallInvite() == null) {
        logger.log(
          "Ignoring cancellation for settled CallInvite " + callRecord.getUuid());
        return false;
      }

      callRecord.setCancelledCallInvite(cancelledCallInvite);
      callRecord.setCallException(callException);
      return true;
    }
  }
}
