// FORK — KAR-685
// Owns: null-tolerant CallRecord lookup/removal for late Twilio Call.Listener callbacks.
// Hooks into: CallListenerProxy.
// Re-check on SDK bump: whether upstream CallListenerProxy still wraps CallRecordDatabase
// get/remove in Objects.requireNonNull, and whether Call.getSid remains the stable cleanup id.
//
// Twilio can deliver stale/late lifecycle callbacks after another native path has already
// settled an invite or removed the in-memory CallRecord. Callback handlers must be
// idempotent: missing records should clean up safe global state and return, not crash.
package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getMediaPlayerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.Call;

import java.util.UUID;

final class ForkCallListenerRecordGuard {
  private static final SDKLog logger = new SDKLog(ForkCallListenerRecordGuard.class);

  private ForkCallListenerRecordGuard() {}

  @Nullable
  static CallRecordDatabase.CallRecord getOrNull(@NonNull String eventName,
                                                 @NonNull UUID uuid,
                                                 @NonNull Call call) {
    CallRecordDatabase.CallRecord callRecord = getCallRecordDatabase()
      .get(new CallRecordDatabase.CallRecord(uuid));
    if (callRecord == null) logMissing(eventName, uuid, call);
    return callRecord;
  }

  @Nullable
  static CallRecordDatabase.CallRecord removeOrNull(@NonNull String eventName,
                                                    @NonNull UUID uuid,
                                                    @NonNull Call call) {
    CallRecordDatabase.CallRecord callRecord = getCallRecordDatabase()
      .remove(new CallRecordDatabase.CallRecord(uuid));
    if (callRecord != null) return callRecord;

    logMissing(eventName, uuid, call);
    cleanupMissingTerminal(uuid, call);
    return null;
  }

  private static void cleanupMissingTerminal(@NonNull UUID uuid, @NonNull Call call) {
    String callSid = call.getSid();
    if (ForkSingleCallSession.isOwner(uuid)) getMediaPlayerManager().stop();
    ForkCallLifecycleCoordinator.cleanupMissingTerminal(uuid);
    if (callSid == null || callSid.isEmpty()) return;

    ForkInvitePayloadStore.clear(callSid);
    ForkVoiceMessageGuard.markSettled(callSid);
    ForkNotificationIdentity.cancelForCallSid(
      VoiceApplicationProxy.getApplicationContext(),
      callSid);
  }

  private static void logMissing(@NonNull String eventName,
                                 @NonNull UUID uuid,
                                 @NonNull Call call) {
    logger.warning(
      "missing CallRecord for " + eventName
        + " uuid=" + uuid
        + " callSid=" + logValue(call.getSid()));
  }

  @NonNull
  private static String logValue(@Nullable String value) {
    return value == null || value.isEmpty() ? "missing" : value;
  }
}
