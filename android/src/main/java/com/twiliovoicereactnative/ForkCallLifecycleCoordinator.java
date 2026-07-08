// FORK — KAR-443
// Owns: typed cross-system call lifecycle coordination between Twilio Voice,
// Jetpack Core-Telecom, notification/full-screen surfaces, wake locks, and
// audio route selection.
// Hooks into: NativeFirebaseMessageHandler, VoiceFirebaseMessagingService,
// VoiceService, VoiceModuleProxy, CallListenerProxy, and ForkRejectCallAction.
// Re-check on SDK bump: Twilio Call/CallInvite lifecycle ordering, nullable
// Call.getSid() timing for outgoing calls, Core-Telecom CallControlScope
// lifecycle, and VoiceService action names.
package com.twiliovoicereactnative;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.audioswitch.AudioDevice;
import com.twilio.voice.CallException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ForkCallLifecycleCoordinator {
  private enum Direction { INCOMING, OUTGOING }
  private enum TelecomState { NOT_REPORTED, REPORTING, ACTIVE, ENDED }
  private enum TwilioState { INVITE, CONNECTING, RINGING, CONNECTED, FAILED, DISCONNECTED }

  private static final SDKLog logger = new SDKLog(ForkCallLifecycleCoordinator.class);
  private static final ConcurrentHashMap<UUID, CallState> calls = new ConcurrentHashMap<>();

  private ForkCallLifecycleCoordinator() {}

  static boolean handleNativeFcm(@NonNull Context context,
                                 @Nullable Map<String, String> data) {
    if (data == null || data.isEmpty()) return false;
    if (ForkVoiceMessageGuard.shouldWakeForIncomingCall(context, data)) {
      ForkIncomingCallWakeLock.acquireIfIncomingCall(context, data);
    }
    return ForkVoiceMessageGuard.handleNativeFcm(context, data);
  }

  static void outgoingConnecting(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.CONNECTING);
    state.twilioState = TwilioState.CONNECTING;
    state.callSid = callSidFor(callRecord);
    logger.debug("outgoingConnecting: " + state.identityLog());
  }

  static void incomingInvite(@NonNull Context context,
                             @NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.INVITE);
    state.twilioState = TwilioState.INVITE;
    state.callSid = callSidFor(callRecord);
    logger.debug("incomingInvite: " + state.identityLog());

    if (!ForkTelecomManager.isAvailable(context)) {
      state.telecomState = TelecomState.NOT_REPORTED;
      ForkTelecomManager.reportIncomingCall(context, callRecord);
      return;
    }

    state.telecomState = TelecomState.REPORTING;
    ForkTelecomManager.reportIncomingCall(context, callRecord);
  }

  static void cancelledInvite(@Nullable String callSid) {
    logger.debug("cancelledInvite: " + callSid);
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishForCallSid(callSid);
    if (hasAcceptedCallForSid(callSid)) {
      logger.debug("cancelledInvite: ignored for already accepted call " + callSid);
      return;
    }
    markEndedForSid(callSid);
    ForkTelecomManager.cancelIncomingCall(callSid);
  }

  static void answerRequested(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.CONNECTING);
    state.twilioState = TwilioState.CONNECTING;
    state.callSid = callSidFor(callRecord);
    logger.debug("answerRequested: " + state.identityLog());

    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    if (state.isTelecomMirrored()) {
      ForkTelecomManager.markAnswered(callRecord);
    }
  }

  static void rejectRequested(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.DISCONNECTED);
    state.twilioState = TwilioState.DISCONNECTED;
    state.callSid = callSidFor(callRecord);
    logger.debug("rejectRequested: " + state.identityLog());

    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    if (state.isTelecomMirrored()) {
      ForkTelecomManager.markRejected(callRecord);
    }
    finishCall(state);
  }

  static void cancelledBySystem(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.DISCONNECTED);
    state.twilioState = TwilioState.DISCONNECTED;
    state.callSid = callSidFor(callRecord);
    logger.debug("cancelledBySystem: " + state.identityLog());

    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    if (hasAcceptedCallForSid(callRecord.getCallSid())) {
      logger.debug("cancelledBySystem: ignored for already accepted call " + callRecord.getCallSid());
      return;
    }
    if (state.isTelecomMirrored()) {
      ForkTelecomManager.cancelIncomingCall(callRecord.getCallSid());
    }
    finishCall(state);
  }

  static void twilioRinging(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.RINGING);
    state.twilioState = TwilioState.RINGING;
    state.callSid = callSidFor(callRecord);
    logger.debug("twilioRinging: " + state.identityLog());
  }

  static void twilioConnected(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.CONNECTED);
    state.twilioState = TwilioState.CONNECTED;
    state.callSid = callSidFor(callRecord);
    logger.debug("twilioConnected: " + state.identityLog());

    if (state.isTelecomMirrored()) {
      ForkTelecomManager.markActive(callRecord);
      state.telecomState = TelecomState.ACTIVE;
    }
  }

  static void twilioDisconnected(@NonNull CallRecordDatabase.CallRecord callRecord,
                                 @Nullable CallException callException) {
    CallState state = stateFor(
      callRecord,
      callException == null ? TwilioState.DISCONNECTED : TwilioState.FAILED);
    state.twilioState = callException == null ? TwilioState.DISCONNECTED : TwilioState.FAILED;
    state.callSid = callSidFor(callRecord);
    logger.debug("twilioDisconnected: " + state.identityLog());

    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    if (state.isTelecomMirrored()) {
      ForkTelecomManager.markDisconnected(callRecord, callException);
    } else {
      logger.debug("twilioDisconnected: no Telecom call to disconnect " + state.identityLog());
    }
    finishCall(state);
  }

  static boolean selectAudioDevice(@NonNull AudioDevice audioDevice,
                                   @NonNull ForkTelecomRouteCallback callback) {
    logger.debug("selectAudioDevice");
    return ForkTelecomManager.selectAudioDevice(audioDevice, callback);
  }

  private static CallState stateFor(@NonNull CallRecordDatabase.CallRecord callRecord,
                                    @NonNull TwilioState fallbackTwilioState) {
    UUID uuid = callRecord.getUuid();
    if (uuid == null) {
      return new CallState(null, directionFor(callRecord), callSidFor(callRecord),
        TelecomState.NOT_REPORTED, fallbackTwilioState);
    }

    CallState existing = calls.get(uuid);
    if (existing != null) {
      existing.direction = directionFor(callRecord);
      return existing;
    }

    CallState created = new CallState(uuid, directionFor(callRecord), callSidFor(callRecord),
      TelecomState.NOT_REPORTED, fallbackTwilioState);
    CallState raced = calls.putIfAbsent(uuid, created);
    return raced == null ? created : raced;
  }

  private static Direction directionFor(@NonNull CallRecordDatabase.CallRecord callRecord) {
    return callRecord.getDirection() == CallRecordDatabase.CallRecord.Direction.OUTGOING
      ? Direction.OUTGOING
      : Direction.INCOMING;
  }

  @Nullable
  private static String callSidFor(@NonNull CallRecordDatabase.CallRecord callRecord) {
    String callSid = callRecord.getCallSid();
    return callSid == null || callSid.isEmpty() ? null : callSid;
  }

  private static void finishCall(@NonNull CallState state) {
    state.telecomState = TelecomState.ENDED;
    if (state.uuid != null) {
      calls.remove(state.uuid);
    }
  }

  private static void markEndedForSid(@Nullable String callSid) {
    if (callSid == null || callSid.isEmpty()) return;
    for (CallState state : calls.values()) {
      if (!callSid.equals(state.callSid)) continue;
      state.twilioState = TwilioState.DISCONNECTED;
      finishCall(state);
      return;
    }
  }

  private static boolean hasAcceptedCallForSid(@Nullable String callSid) {
    if (callSid == null || callSid.isEmpty()) return false;
    for (CallRecordDatabase.CallRecord record : VoiceApplicationProxy.getCallRecordDatabase().getCollection()) {
      if (!callSid.equals(record.getCallSid())) continue;
      if (record.getVoiceCall() != null || record.getCallInviteState() == CallRecordDatabase.CallRecord.CallInviteState.USED) {
        return true;
      }
    }
    return false;
  }

  private static final class CallState {
    @Nullable final UUID uuid;
    Direction direction;
    @Nullable String callSid;
    TelecomState telecomState;
    TwilioState twilioState;

    CallState(@Nullable UUID uuid,
              @NonNull Direction direction,
              @Nullable String callSid,
              @NonNull TelecomState telecomState,
              @NonNull TwilioState twilioState) {
      this.uuid = uuid;
      this.direction = direction;
      this.callSid = callSid;
      this.telecomState = telecomState;
      this.twilioState = twilioState;
    }

    boolean isTelecomMirrored() {
      return direction == Direction.INCOMING
        && telecomState != TelecomState.NOT_REPORTED
        && telecomState != TelecomState.ENDED;
    }

    String identityLog() {
      return "uuid=" + uuid
        + " sid=" + callSid
        + " direction=" + direction
        + " telecom=" + telecomState
        + " twilio=" + twilioState;
    }
  }
}
