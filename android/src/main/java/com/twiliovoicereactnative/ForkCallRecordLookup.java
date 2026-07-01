// FORK — KAR-316 (Sentry KAREN-APP-58)
// Owns: null-tolerant CallRecord lookup by UUID/SID.
// Hooks into: VoiceService, ForkIncomingCallFocus, ForkRejectCallAction.
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

import android.content.Intent;

import androidx.annotation.Nullable;

import java.util.UUID;

public final class ForkCallRecordLookup {
  private static final SDKLog logger = new SDKLog(ForkCallRecordLookup.class);

  private ForkCallRecordLookup() {}

  @Nullable
  public static UUID readUuid(@Nullable Intent intent) {
    if (intent == null) return null;
    Object value = intent.getSerializableExtra(Constants.MSG_KEY_UUID);
    if (value instanceof UUID) return (UUID)value;
    if (value != null) {
      logger.warning("Unexpected call UUID extra type: " + value.getClass().getName());
    }
    return null;
  }

  @Nullable
  public static CallRecordDatabase.CallRecord getOrNull(@Nullable UUID uuid) {
    if (uuid == null) return null;
    return getCallRecordDatabase().get(new CallRecordDatabase.CallRecord(uuid));
  }

  @Nullable
  public static CallRecordDatabase.CallRecord getBySid(@Nullable String callSid) {
    if (callSid == null || callSid.length() == 0) return null;
    return getCallRecordDatabase().get(new CallRecordDatabase.CallRecord(callSid));
  }
}
