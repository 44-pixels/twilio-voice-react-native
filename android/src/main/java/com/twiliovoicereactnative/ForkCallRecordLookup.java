// FORK — KAR-316 (Sentry KAREN-APP-58)
// Owns: null-tolerant CallRecord lookup by UUID and safe full-screen launch
// fallback.
// Hooks into: VoiceService.getCallRecord, VoiceService full-screen launch,
//             VoiceIntentFilter full-screen launch.
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
//
// Full-screen launch is different: it is OS-driven UI surfacing, not a user
// action. If Android re-delivers that activity intent without the UUID extra,
// recover the single active invite from the in-memory database and re-post its
// notification instead of dropping the launch.
package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.CallRecordDatabase.CallRecord.CallInviteState.ACTIVE;
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
  public static CallRecordDatabase.CallRecord getForFullScreenLaunch(@Nullable Intent intent) {
    UUID uuid = readUuid(intent);
    CallRecordDatabase.CallRecord record = getOrNull(uuid);
    if (record != null) return record;
    if (uuid != null) {
      logger.warning("full-screen launch for missing call record " + uuid);
      return null;
    }

    CallRecordDatabase.CallRecord fallback = getSingleActiveInvite();
    if (fallback != null) {
      logger.warning(
        "full-screen launch missing UUID; recovered active invite " + fallback.getUuid());
    }
    return fallback;
  }

  @Nullable
  private static CallRecordDatabase.CallRecord getSingleActiveInvite() {
    CallRecordDatabase.CallRecord candidate = null;
    for (CallRecordDatabase.CallRecord record : getCallRecordDatabase().getCollection()) {
      if (record.getCallInvite() == null || record.getCallInviteState() != ACTIVE) continue;
      if (candidate != null) {
        logger.warning("full-screen launch missing UUID and multiple active invites exist");
        return null;
      }
      candidate = record;
    }
    return candidate;
  }
}
