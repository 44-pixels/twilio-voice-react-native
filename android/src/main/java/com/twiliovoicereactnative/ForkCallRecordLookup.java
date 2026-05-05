// FORK — KAR-316 (Sentry KAREN-APP-58)
// Owns: null-tolerant CallRecord lookup by UUID.
// Hooks into: VoiceService.getCallRecord.
// Re-check on SDK bump: whether upstream's getCallRecord still wraps the
// CallRecordDatabase lookup in Objects.requireNonNull, and whether the
// per-handler null guards in VoiceService are still in place.
//
// Race: backend <Dial timeout> handoff cancels the invite (cancelCall removes
// the CallRecord) while the user's tap on the heads-up notification is already
// in flight. The system delivers ACTION_ACCEPT_CALL / REJECT / FOREGROUND to a
// recreated service instance, the lookup returns null, and upstream's
// requireNonNull throws NPE inside onStartCommand — Android wraps it in
// "Unable to start service" and kills the process. Returning null instead lets
// the per-action handlers log and bail; the notification was already cancelled
// by cancelCall, so silent dismissal is correct.
package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;

import androidx.annotation.Nullable;

import java.util.UUID;

public final class ForkCallRecordLookup {
  private ForkCallRecordLookup() {}

  @Nullable
  public static CallRecordDatabase.CallRecord getOrNull(UUID uuid) {
    if (uuid == null) return null;
    return getCallRecordDatabase().get(new CallRecordDatabase.CallRecord(uuid));
  }
}
