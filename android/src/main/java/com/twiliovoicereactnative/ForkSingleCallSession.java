// FORK — KAR-443
// Owns: the single-Twilio-call invariant across incoming and outgoing setup.
// Hooks into: VoiceFirebaseMessagingService, VoiceModuleProxy, and call terminal callbacks.
// Re-check on SDK bump: CallInvite rejection semantics and all call terminal callback paths.
package com.twiliovoicereactnative;

import android.content.Context;

import androidx.annotation.NonNull;

import com.twilio.voice.CallInvite;

import java.util.UUID;

final class ForkSingleCallSession {
  private static UUID owner;

  private ForkSingleCallSession() {}

  static synchronized boolean claim(@NonNull UUID uuid) {
    if (owner != null) return owner.equals(uuid);
    owner = uuid;
    return true;
  }

  static synchronized boolean isOwner(@NonNull UUID uuid) {
    return owner != null && owner.equals(uuid);
  }

  static synchronized void release(@NonNull UUID uuid) {
    if (owner != null && owner.equals(uuid)) owner = null;
  }

  static boolean claimIncoming(@NonNull Context context,
                               @NonNull CallRecordDatabase.CallRecord callRecord) {
    if (claim(callRecord.getUuid())) return true;

    CallInvite invite = callRecord.getCallInvite();
    if (invite != null) {
      invite.reject(context.getApplicationContext());
      callRecord.setCallInviteUsedState();
    }
    ForkInvitePayloadStore.clear(callRecord.getCallSid());
    ForkVoiceMessageGuard.markSettled(callRecord.getCallSid());
    return false;
  }
}
