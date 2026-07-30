// FORK — KAR-443
// Owns: Java-owned snapshots used for single-call and Core Telecom arbitration.
// Hooks into: ForkSingleCallSession and ForkCoreTelecomManager.
// Re-check on SDK bump: CallRecord fields that determine invite/call liveness.
package com.twiliovoicereactnative;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

final class ForkCallRecordSnapshot {
  @NonNull final UUID uuid;
  @Nullable final String callSid;
  final boolean activeInvite;
  final boolean hasCall;
  @Nullable final ForkCallLifecycleCoordinator.TwilioState callState;
  final boolean liveCall;

  private ForkCallRecordSnapshot(
    @NonNull UUID uuid,
    @Nullable String callSid,
    boolean activeInvite,
    boolean hasCall,
    @Nullable ForkCallLifecycleCoordinator.TwilioState callState,
    boolean liveCall
  ) {
    this.uuid = uuid;
    this.callSid = callSid;
    this.activeInvite = activeInvite;
    this.hasCall = hasCall;
    this.callState = callState;
    this.liveCall = liveCall;
  }

  @NonNull
  static ForkCallRecordSnapshot capture(
    @NonNull CallRecordDatabase.CallRecord callRecord
  ) {
    UUID uuid = callRecord.getUuid();
    boolean hasCall = callRecord.getVoiceCall() != null;
    boolean activeInvite = callRecord.getCallInvite() != null
      && callRecord.getCallInviteState()
        == CallRecordDatabase.CallRecord.CallInviteState.ACTIVE;
    ForkCallLifecycleCoordinator.TwilioState callState =
      ForkCallLifecycleCoordinator.twilioState(uuid);
    boolean liveCall = hasCall && (callState == null || !callState.isTerminal());

    return new ForkCallRecordSnapshot(
      uuid,
      callRecord.getCallSid(),
      activeInvite,
      hasCall,
      callState,
      liveCall);
  }

  boolean hasLiveTwilioState() {
    return activeInvite || liveCall;
  }
}
