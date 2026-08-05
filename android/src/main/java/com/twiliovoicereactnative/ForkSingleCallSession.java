// FORK — KAR-443
// Owns: the single-Twilio-call invariant across incoming and outgoing setup.
// Hooks into: VoiceFirebaseMessagingService, VoiceModuleProxy, and call terminal callbacks.
// Re-check on SDK bump: CallInvite rejection semantics, Call.State terminal semantics,
// and all call terminal callback paths.
package com.twiliovoicereactnative;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.CallInvite;

import java.util.UUID;

final class ForkSingleCallSession {
  @Nullable private static UUID owner;
  private static int setupReservations;
  private static boolean releasePending;

  private ForkSingleCallSession() {}

  static boolean claimOutgoing(@NonNull Context context, @NonNull UUID uuid) {
    while (true) {
      OwnerSnapshot existing = ownerSnapshot();
      if (existing.uuid == null) {
        synchronized (ForkSingleCallSession.class) {
          if (owner != null) continue;
          owner = uuid;
          setupReservations = 1;
          releasePending = false;
          return true;
        }
      }
      if (existing.uuid.equals(uuid)) return true;

      StaleOwner staleOwner = staleOwner(existing);
      if (staleOwner == null) return false;
      if (!transferOwner(existing, uuid)) continue;

      ForkCallLifecycleCoordinator.retireStaleOwner(
        context,
        existing.uuid,
        staleOwner.callRecord,
        staleOwner.snapshot);
      return true;
    }
  }

  static synchronized boolean reserveSetup(@NonNull UUID uuid) {
    if (owner == null || !owner.equals(uuid)) return false;
    setupReservations++;
    return true;
  }

  static void completeSetup(@NonNull UUID uuid) {
    boolean shouldRelease;
    synchronized (ForkSingleCallSession.class) {
      if (owner == null || !owner.equals(uuid) || setupReservations == 0) return;
      setupReservations--;
      shouldRelease = setupReservations == 0 && releasePending;
    }
    if (!shouldRelease) return;

    ForkTelecomManager.cleanupTerminalCall(uuid);
    if (!ForkTelecomManager.isTelecomAudioOwner(uuid)) releaseIfOwner(uuid);
  }

  static synchronized boolean hasSetupReservation(@NonNull UUID uuid) {
    return owner != null && owner.equals(uuid) && setupReservations > 0;
  }

  static synchronized boolean isOwner(@NonNull UUID uuid) {
    return owner != null && owner.equals(uuid);
  }

  @Nullable
  static synchronized UUID ownerUuid() {
    return owner;
  }

  static boolean releaseIfOwner(@NonNull UUID uuid) {
    synchronized (ForkSingleCallSession.class) {
      if (owner == null || !owner.equals(uuid)) return false;
      if (setupReservations > 0) {
        releasePending = true;
        return false;
      }
      owner = null;
      releasePending = false;
    }
    ForkCallLifecycleCoordinator.ownerReleased(uuid);
    return true;
  }

  static boolean claimIncoming(@NonNull Context context,
                               @NonNull CallRecordDatabase.CallRecord callRecord) {
    UUID incomingUuid = callRecord.getUuid();
    CallInvite invite = callRecord.getCallInvite();
    if (invite == null) return false;

    while (true) {
      OwnerSnapshot existing = ownerSnapshot();
      if (existing.uuid == null) {
        synchronized (ForkSingleCallSession.class) {
          if (owner != null) continue;
          owner = incomingUuid;
          setupReservations = 1;
          releasePending = false;
          return true;
        }
      }
      if (existing.uuid.equals(incomingUuid)) return true;

      CallRecordDatabase.CallRecord ownerRecord = ownerRecord(existing.uuid);
      ForkCallRecordSnapshot recordSnapshot = ownerRecord == null
        ? null
        : ForkCallRecordSnapshot.capture(ownerRecord);

      if (isDuplicateInvite(invite, recordSnapshot)) {
        ForkCallInviteRejection.logDuplicateInviteIgnored(
          invite,
          callRecord,
          existing.uuid);
        return false;
      }

      boolean telecomManaged = ForkTelecomManager.isTelecomAudioOwner(existing.uuid);
      boolean liveOwner = existing.setupReservations > 0
        || (recordSnapshot != null && recordSnapshot.hasLiveTwilioState())
        || telecomManaged;
      if (liveOwner) {
        if (!reserveSetup(existing.uuid)) continue;
        rejectIncoming(context, invite, callRecord, existing.uuid,
          ForkCallInviteRejection.Reason.LIVE_CALL_ALREADY_ACTIVE);
        return false;
      }

      if (!transferOwner(existing, incomingUuid)) continue;

      ForkCallInviteRejection.logStaleOwnerRecovered(
        invite,
        callRecord,
        existing.uuid);
      ForkCallLifecycleCoordinator.retireStaleOwner(
        context,
        existing.uuid,
        ownerRecord,
        recordSnapshot);
      return true;
    }
  }

  private static void rejectIncoming(
    @NonNull Context context,
    @NonNull CallInvite invite,
    @NonNull CallRecordDatabase.CallRecord callRecord,
    @NonNull UUID ownerUuid,
    @NonNull ForkCallInviteRejection.Reason reason
  ) {
    try {
      ForkCallInviteRejection.reject(context, invite, callRecord, reason, ownerUuid);
    } finally {
      try {
        callRecord.setCallInviteUsedState();
        ForkInvitePayloadStore.clear(callRecord.getCallSid());
        ForkVoiceMessageGuard.markSettled(callRecord.getCallSid());
      } finally {
        completeSetup(ownerUuid);
      }
    }
  }

  @Nullable
  private static StaleOwner staleOwner(@NonNull OwnerSnapshot existing) {
    if (existing.setupReservations > 0
      || ForkTelecomManager.isTelecomAudioOwner(existing.uuid)) return null;

    CallRecordDatabase.CallRecord callRecord = ownerRecord(existing.uuid);
    ForkCallRecordSnapshot snapshot = callRecord == null
      ? null
      : ForkCallRecordSnapshot.capture(callRecord);
    if (snapshot != null && snapshot.hasLiveTwilioState()) return null;
    return new StaleOwner(callRecord, snapshot);
  }

  private static boolean transferOwner(@NonNull OwnerSnapshot existing,
                                       @NonNull UUID replacement) {
    synchronized (ForkSingleCallSession.class) {
      if (!existing.matches(owner, setupReservations)) return false;
      if (setupReservations > 0) return false;
      owner = replacement;
      setupReservations = 1;
      releasePending = false;
      return true;
    }
  }

  @NonNull
  private static synchronized OwnerSnapshot ownerSnapshot() {
    return new OwnerSnapshot(owner, setupReservations);
  }

  @Nullable
  private static CallRecordDatabase.CallRecord ownerRecord(@NonNull UUID uuid) {
    return VoiceApplicationProxy.getCallRecordDatabase()
      .get(new CallRecordDatabase.CallRecord(uuid));
  }

  private static boolean isDuplicateInvite(
    @NonNull CallInvite invite,
    @Nullable ForkCallRecordSnapshot ownerSnapshot
  ) {
    if (ownerSnapshot == null) return false;
    String incomingCallSid = invite.getCallSid();
    return incomingCallSid != null && incomingCallSid.equals(ownerSnapshot.callSid);
  }

  private static final class OwnerSnapshot {
    @Nullable final UUID uuid;
    final int setupReservations;

    OwnerSnapshot(@Nullable UUID uuid, int setupReservations) {
      this.uuid = uuid;
      this.setupReservations = setupReservations;
    }

    boolean matches(@Nullable UUID currentOwner, int currentReservations) {
      return uuid != null
        && uuid.equals(currentOwner)
        && setupReservations == currentReservations;
    }
  }

  private static final class StaleOwner {
    @Nullable final CallRecordDatabase.CallRecord callRecord;
    @Nullable final ForkCallRecordSnapshot snapshot;

    StaleOwner(@Nullable CallRecordDatabase.CallRecord callRecord,
               @Nullable ForkCallRecordSnapshot snapshot) {
      this.callRecord = callRecord;
      this.snapshot = snapshot;
    }
  }
}
