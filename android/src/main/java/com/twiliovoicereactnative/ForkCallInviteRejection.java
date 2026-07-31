// FORK — KAR-443
// Owns: native CallInvite rejection transactions, cleanup, and diagnostics.
// Hooks into: ForkSingleCallSession, VoiceService, ForkRejectCallAction,
//             ForkInvitePayloadStore, and ForkCoreTelecomManager.
// Re-check on SDK bump: every native CallInvite.reject() call site and Twilio
// Call.State terminal semantics.
package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyCallSid;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyType;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueRejected;
import static com.twiliovoicereactnative.CommonConstants.ScopeCallInvite;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CALL_INVITE_INFO;
import static com.twiliovoicereactnative.JSEventEmitter.constructJSMap;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCallInvite;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.CallInvite;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ForkCallInviteRejection {
  enum Reason {
    USER_REJECTED,
    LIVE_CALL_ALREADY_ACTIVE,
    STALE_OWNER_RECOVERED,
    MICROPHONE_PERMISSION_MISSING,
    FOREGROUND_SERVICE_FAILED,
    TELECOM_ANSWER_FAILED,
    DUPLICATE_INVITE
  }

  private static final SDKLog logger = new SDKLog(ForkCallInviteRejection.class);
  private static final ConcurrentHashMap<UUID, Reason> requestedReasons =
    new ConcurrentHashMap<>();

  private ForkCallInviteRejection() {}

  static void markReason(@NonNull CallRecordDatabase.CallRecord callRecord,
                         @NonNull Reason reason) {
    requestedReasons.put(callRecord.getUuid(), reason);
  }

  static void clearReason(@NonNull CallRecordDatabase.CallRecord callRecord) {
    requestedReasons.remove(callRecord.getUuid());
  }

  static void rejectClaimed(@NonNull Context context,
                            @NonNull CallInvite callInvite,
                            @NonNull CallRecordDatabase.CallRecord callRecord) {
    try {
      ForkTwilioVoiceThread.runBlocking(() -> reject(
        context,
        callInvite,
        callRecord,
        Reason.USER_REJECTED));
    } finally {
      try {
        cleanupRejectedArtifacts(context, callRecord);
      } finally {
        ForkCallLifecycleCoordinator.rejectRequested(callRecord);
      }
    }
  }

  static void reject(@NonNull Context context,
                     @NonNull CallInvite callInvite,
                     @Nullable CallRecordDatabase.CallRecord inviteRecord,
                     @NonNull Reason fallbackReason) {
    reject(
      context,
      callInvite,
      inviteRecord,
      fallbackReason,
      ForkSingleCallSession.ownerUuid());
  }

  static void reject(@NonNull Context context,
                     @NonNull CallInvite callInvite,
                     @Nullable CallRecordDatabase.CallRecord inviteRecord,
                     @NonNull Reason fallbackReason,
                     @Nullable UUID ownerUuid) {
    Reason reason = inviteRecord == null
      ? fallbackReason
      : consumeReason(inviteRecord, fallbackReason);
    log(callInvite.getCallSid(), inviteRecord, reason, ownerUuid);
    callInvite.reject(context.getApplicationContext());
  }

  static void logStaleOwnerRecovered(@NonNull CallInvite callInvite,
                                     @NonNull CallRecordDatabase.CallRecord inviteRecord,
                                     @NonNull UUID staleOwnerUuid) {
    log(
      callInvite.getCallSid(),
      inviteRecord,
      Reason.STALE_OWNER_RECOVERED,
      staleOwnerUuid);
  }

  static void logDuplicateInviteIgnored(
    @NonNull CallInvite callInvite,
    @NonNull CallRecordDatabase.CallRecord inviteRecord,
    @NonNull UUID ownerUuid
  ) {
    log(callInvite.getCallSid(), inviteRecord, Reason.DUPLICATE_INVITE, ownerUuid);
    logger.log("event=call_invite_duplicate"
      + " decision=IGNORE"
      + " sid=" + value(callInvite.getCallSid())
      + " duplicateUuid=" + inviteRecord.getUuid()
      + " ownerUuid=" + ownerUuid);
  }

  private static void cleanupRejectedArtifacts(
    @NonNull Context context,
    @NonNull CallRecordDatabase.CallRecord callRecord
  ) {
    VoiceApplicationProxy.getCallRecordDatabase().remove(callRecord);
    VoiceService.removeForegroundNotificationIfRunning();
    VoiceApplicationProxy.getMediaPlayerManager().stop();
    ForkCallLifecycleCoordinator.deactivateFallbackAudio(callRecord);
    ForkInvitePayloadStore.clear(callRecord.getCallSid());
    ForkNotificationIdentity.cancelForCallSid(context, callRecord.getCallSid());
    ForkVoiceMessageGuard.markSettled(callRecord.getCallSid());
    ForkLockScreenFlags.clearForEndedCall();

    if (callRecord.getCallRejectedPromise() != null) {
      callRecord.getCallRejectedPromise().resolve(callRecord.getUuid().toString());
    }

    VoiceApplicationProxy.getJSEventEmitter().sendEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueRejected),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid()),
        new Pair<>(JS_EVENT_KEY_CALL_INVITE_INFO, serializeCallInvite(callRecord))));
  }

  @NonNull
  private static Reason consumeReason(
    @NonNull CallRecordDatabase.CallRecord callRecord,
    @NonNull Reason fallbackReason
  ) {
    Reason reason = requestedReasons.remove(callRecord.getUuid());
    return reason == null ? fallbackReason : reason;
  }

  private static void log(@Nullable String callSid,
                          @Nullable CallRecordDatabase.CallRecord inviteRecord,
                          @NonNull Reason reason,
                          @Nullable UUID ownerUuid) {
    CallRecordDatabase.CallRecord ownerRecord = ownerRecord(ownerUuid, inviteRecord);

    logger.log("event=call_invite_decision"
      + " sid=" + value(callSid)
      + " reason=" + reason
      + " ownerUuid=" + value(ownerUuid)
      + " ownerRecordState=" + ownerRecordState(ownerRecord)
      + " inviteState=" + inviteState(inviteRecord)
      + " callState=" + callState(inviteRecord)
      + " telecomState=" + ForkTelecomManager.stateForLog(ownerUuid));
  }

  @Nullable
  private static CallRecordDatabase.CallRecord ownerRecord(
    @Nullable UUID ownerUuid,
    @Nullable CallRecordDatabase.CallRecord inviteRecord
  ) {
    if (ownerUuid == null) return null;
    if (inviteRecord != null && ownerUuid.equals(inviteRecord.getUuid())) {
      return inviteRecord;
    }
    return VoiceApplicationProxy.getCallRecordDatabase()
      .get(new CallRecordDatabase.CallRecord(ownerUuid));
  }

  @NonNull
  private static String ownerRecordState(
    @Nullable CallRecordDatabase.CallRecord callRecord
  ) {
    if (callRecord == null) return "MISSING";
    ForkCallRecordSnapshot snapshot = ForkCallRecordSnapshot.capture(callRecord);
    if (snapshot.hasCall) {
      return snapshot.callState == null ? "CALL_UNKNOWN_LIVE" : "CALL_" + snapshot.callState;
    }
    return snapshot.activeInvite ? "INVITE_ACTIVE" : "INVITE_SETTLED";
  }

  @NonNull
  private static String inviteState(
    @Nullable CallRecordDatabase.CallRecord callRecord
  ) {
    if (callRecord == null) return "UNKNOWN";
    synchronized (callRecord) {
      return callRecord.getCallInviteState().toString();
    }
  }

  @NonNull
  private static String callState(
    @Nullable CallRecordDatabase.CallRecord callRecord
  ) {
    if (callRecord == null) return "NONE";
    ForkCallRecordSnapshot snapshot = ForkCallRecordSnapshot.capture(callRecord);
    if (!snapshot.hasCall) return "NONE";
    return snapshot.callState == null ? "UNKNOWN_LIVE" : snapshot.callState.toString();
  }

  @NonNull
  private static String value(@Nullable Object value) {
    return value == null ? "NONE" : value.toString();
  }
}
