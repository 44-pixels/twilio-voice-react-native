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
import com.twilio.voice.AcceptOptions;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ForkCallLifecycleCoordinator {
  private enum Direction { INCOMING, OUTGOING }
  private enum TelecomState { NOT_REPORTED, REPORTING, ACTIVE, ENDED }
  enum TwilioState {
    INVITE,
    CONNECTING,
    RINGING,
    CONNECTED,
    FAILED,
    DISCONNECTED;

    boolean isTerminal() {
      return this == FAILED || this == DISCONNECTED;
    }
  }

  private static final SDKLog logger = new SDKLog(ForkCallLifecycleCoordinator.class);
  private static final ConcurrentHashMap<UUID, CallState> calls = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<UUID, Call> outgoingSetupCalls =
    new ConcurrentHashMap<>();

  private ForkCallLifecycleCoordinator() {}

  static boolean handleNativeFcm(@NonNull Context context,
                                 @Nullable Map<String, String> data) {
    if (data == null || data.isEmpty()) return false;
    if (ForkVoiceMessageGuard.shouldWakeForIncomingCall(context, data)) {
      ForkIncomingCallWakeLock.acquireIfIncomingCall(context, data);
    }
    try {
      boolean handled = ForkVoiceMessageGuard.handleNativeFcm(context, data);
      if (!handled) ForkIncomingCallWakeLock.releaseForPayload(data);
      return handled;
    } catch (RuntimeException error) {
      ForkIncomingCallWakeLock.releaseForPayload(data);
      throw error;
    }
  }

  static boolean claimIncoming(@NonNull Context context,
                               @NonNull CallRecordDatabase.CallRecord callRecord) {
    return ForkSingleCallSession.claimIncoming(context, callRecord);
  }

  static boolean claimOutgoing(@NonNull Context context, @NonNull UUID uuid) {
    return ForkSingleCallSession.claimOutgoing(context, uuid);
  }

  static void outgoingInvite(@NonNull Context context,
                             @NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.CONNECTING);
    state.telecomState = ForkTelecomManager.isAvailable(context)
      ? TelecomState.REPORTING
      : TelecomState.NOT_REPORTED;
    ForkTelecomManager.reportOutgoingCall(context, callRecord);
  }

  @NonNull
  static Call connectOutgoing(@NonNull UUID uuid,
                              @NonNull ConnectOptions connectOptions,
                              @NonNull Call.Listener listener) {
    Call call = VoiceApplicationProxy.getVoiceServiceApi().connect(connectOptions, listener);
    outgoingSetupCalls.put(uuid, call);
    return call;
  }

  static void outgoingSetupFailed(@NonNull UUID uuid) {
    CallRecordDatabase.CallRecord callRecord = VoiceApplicationProxy
      .getCallRecordDatabase()
      .get(new CallRecordDatabase.CallRecord(uuid));
    Call call = callRecord == null ? outgoingSetupCalls.get(uuid) : callRecord.getVoiceCall();
    outgoingSetupFailed(uuid, call);
  }

  static void outgoingSetupFailed(@NonNull UUID uuid, @Nullable Call call) {
    outgoingSetupCalls.remove(uuid);
    ForkSingleCallSession.completeSetup(uuid);
    if (call == null
      || isTerminalTwilioState(uuid)
      || call.getState() == Call.State.DISCONNECTED) {
      CallRecordDatabase.CallRecord callRecord = VoiceApplicationProxy
        .getCallRecordDatabase()
        .get(new CallRecordDatabase.CallRecord(uuid));
      if (callRecord != null) {
        VoiceApplicationProxy.getCallRecordDatabase().remove(callRecord);
      }
      ForkSingleCallSession.releaseIfOwner(uuid);
      return;
    }

    call.disconnect();
  }

  static int authorizeAnswer(@NonNull CallRecordDatabase.CallRecord callRecord) {
    return ForkTelecomManager.authorizeAnswer(callRecord);
  }

  static void activateFallbackAudio(@NonNull CallRecordDatabase.CallRecord callRecord) {
    if (!ForkTelecomManager.isTelecomAudioOwner(callRecord)) {
      VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().activate();
    }
  }

  static void deactivateFallbackAudio(@NonNull CallRecordDatabase.CallRecord callRecord) {
    if (ForkSingleCallSession.isOwner(callRecord.getUuid())
      && !ForkTelecomManager.isTelecomAudioOwner(callRecord)) {
      VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().deactivate();
    }
  }

  static void cleanupMissingTerminal(@NonNull UUID uuid) {
    if (!ForkSingleCallSession.isOwner(uuid)) return;
    if (!ForkTelecomManager.isTelecomAudioOwner(uuid)) {
      VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().deactivate();
    }
    ForkSingleCallSession.releaseIfOwner(uuid);
  }

  static void outgoingRingingForeground(
    @NonNull CallRecordDatabase.CallRecord callRecord,
    @NonNull Call call
  ) {
    if (!VoiceApplicationProxy.getVoiceServiceApi().raiseOutgoingCallNotification(callRecord)) {
      outgoingSetupFailed(callRecord.getUuid(), call);
    }
  }

  static void outgoingConnecting(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.CONNECTING);
    state.twilioState = TwilioState.CONNECTING;
    state.callSid = callSidFor(callRecord);
    logger.debug("outgoingConnecting: " + state.identityLog());
    outgoingSetupCalls.remove(callRecord.getUuid());
    ForkSingleCallSession.completeSetup(callRecord.getUuid());
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
      ForkSingleCallSession.completeSetup(callRecord.getUuid());
      return;
    }

    state.telecomState = TelecomState.REPORTING;
    ForkTelecomManager.reportIncomingCall(context, callRecord);
    ForkSingleCallSession.completeSetup(callRecord.getUuid());
  }

  static boolean acceptIncoming(@NonNull Context context,
                                @NonNull CallRecordDatabase.CallRecord callRecord,
                                @NonNull CallInvite callInvite,
                                @NonNull AcceptOptions acceptOptions) {
    try {
      ForkTwilioVoiceThread.runBlocking(() -> callRecord.setCall(
        callInvite.accept(
          context,
          acceptOptions,
          new CallListenerProxy(callRecord.getUuid(), context))));
    } catch (RuntimeException error) {
      logger.warning(error, "event=call_accept result=synchronous_failure uuid="
        + callRecord.getUuid() + " sid=" + callRecord.getCallSid());
      cleanupSynchronousAcceptFailure(context, callRecord, error);
      return false;
    }
    ForkSingleCallSession.completeSetup(callRecord.getUuid());
    return true;
  }

  static void cancelledInvite(@NonNull CallRecordDatabase.CallRecord callRecord) {
    String callSid = callRecord.getCallSid();
    logger.debug("cancelledInvite: " + callSid);
    ForkIncomingCallWakeLock.release(callRecord.getUuid());
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

    ForkIncomingCallWakeLock.release(callRecord.getUuid());
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

    ForkIncomingCallWakeLock.release(callRecord.getUuid());
    ForkIncomingCallActivity.finishFor(callRecord);
    if (state.isTelecomMirrored()) {
      ForkTelecomManager.markRejected(callRecord);
    }
    finishCall(state);
    ForkSingleCallSession.completeSetup(callRecord.getUuid());
    if (!ForkTelecomManager.isTelecomAudioOwner(callRecord)) {
      ForkSingleCallSession.releaseIfOwner(callRecord.getUuid());
    }
  }

  static void cancelledBySystem(@NonNull CallRecordDatabase.CallRecord callRecord) {
    CallState state = stateFor(callRecord, TwilioState.DISCONNECTED);
    state.twilioState = TwilioState.DISCONNECTED;
    state.callSid = callSidFor(callRecord);
    logger.debug("cancelledBySystem: " + state.identityLog());

    ForkIncomingCallWakeLock.release(callRecord.getUuid());
    ForkIncomingCallActivity.finishFor(callRecord);
    if (hasAcceptedCallForSid(callRecord.getCallSid())) {
      logger.debug("cancelledBySystem: ignored for already accepted call " + callRecord.getCallSid());
      return;
    }
    boolean telecomManaged = ForkTelecomManager.isTelecomAudioOwner(callRecord);
    if (state.isTelecomMirrored()) {
      ForkTelecomManager.cancelIncomingCall(callRecord.getCallSid());
    }
    finishCall(state);
    if (!telecomManaged) ForkSingleCallSession.releaseIfOwner(callRecord.getUuid());
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

  static void twilioDisconnectedWithSound(
    @NonNull Context context,
    @NonNull CallRecordDatabase.CallRecord callRecord,
    @Nullable CallException callException
  ) {
    VoiceApplicationProxy.getMediaPlayerManager().stop();
    ForkCallSounds.playCallEnded(context, () -> {
      VoiceApplicationProxy.getVoiceServiceApi().cancelActiveCallNotification(callRecord);
      twilioDisconnected(callRecord, callException);
    });
  }

  static void twilioDisconnected(@NonNull CallRecordDatabase.CallRecord callRecord,
                                 @Nullable CallException callException) {
    CallState state = stateFor(
      callRecord,
      callException == null ? TwilioState.DISCONNECTED : TwilioState.FAILED);
    state.twilioState = callException == null ? TwilioState.DISCONNECTED : TwilioState.FAILED;
    state.callSid = callSidFor(callRecord);
    logger.debug("twilioDisconnected: " + state.identityLog());

    ForkIncomingCallWakeLock.release(callRecord.getUuid());
    ForkIncomingCallActivity.finishFor(callRecord);
    deactivateFallbackAudio(callRecord);
    boolean telecomManaged = ForkTelecomManager.isTelecomAudioOwner(callRecord);
    if (state.isTelecomMirrored()) {
      ForkTelecomManager.markDisconnected(callRecord, callException);
    } else {
      logger.debug("twilioDisconnected: no Telecom call to disconnect " + state.identityLog());
    }
    finishCall(state);
    if (!telecomManaged) ForkSingleCallSession.releaseIfOwner(callRecord.getUuid());
  }

  private static void cleanupSynchronousAcceptFailure(
    @NonNull Context context,
    @NonNull CallRecordDatabase.CallRecord callRecord,
    @NonNull RuntimeException error
  ) {
    UUID uuid = callRecord.getUuid();
    String callSid = callRecord.getCallSid();
    boolean telecomManaged = ForkTelecomManager.isTelecomAudioOwner(callRecord);
    String promiseMessage = error.getMessage() == null
      ? "Unable to accept incoming call."
      : error.getMessage();

    cleanupAcceptFailure("reject promise",
      () -> callRecord.failCallAcceptedPromise(promiseMessage));
    cleanupAcceptFailure("remove CallRecord",
      () -> VoiceApplicationProxy.getCallRecordDatabase().remove(callRecord));
    cleanupAcceptFailure("remove foreground notification",
      VoiceService::removeForegroundNotificationIfRunning);
    cleanupAcceptFailure("stop ringer",
      () -> VoiceApplicationProxy.getMediaPlayerManager().stop());
    cleanupAcceptFailure("deactivate fallback audio",
      () -> deactivateFallbackAudio(callRecord));
    cleanupAcceptFailure("release wake lock",
      () -> ForkIncomingCallWakeLock.release(uuid));
    cleanupAcceptFailure("finish incoming activity",
      () -> ForkIncomingCallActivity.finishFor(callRecord));
    cleanupAcceptFailure("clear invite payload",
      () -> ForkInvitePayloadStore.clear(callSid));
    cleanupAcceptFailure("cancel notification",
      () -> ForkNotificationIdentity.cancelForCallSid(context, callSid));
    cleanupAcceptFailure("settle voice message",
      () -> ForkVoiceMessageGuard.markSettled(callSid));
    cleanupAcceptFailure("clear lock-screen flags", ForkLockScreenFlags::clearForEndedCall);
    calls.remove(uuid);

    if (telecomManaged) {
      cleanupAcceptFailure("disconnect Telecom call",
        () -> ForkTelecomManager.markDisconnected(callRecord, null));
    }
    cleanupAcceptFailure("complete setup reservation",
      () -> ForkSingleCallSession.completeSetup(uuid));
    cleanupAcceptFailure("release session owner", () -> {
      if (!ForkTelecomManager.isTelecomAudioOwner(callRecord)) {
        ForkSingleCallSession.releaseIfOwner(uuid);
      }
    });
  }

  private static void cleanupAcceptFailure(@NonNull String operation,
                                           @NonNull Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException cleanupError) {
      logger.warning(cleanupError,
        "event=call_accept_cleanup result=failure operation=" + operation);
    }
  }

  static void retireStaleOwner(
    @NonNull Context context,
    @NonNull UUID uuid,
    @Nullable CallRecordDatabase.CallRecord callRecord,
    @Nullable ForkCallRecordSnapshot snapshot
  ) {
    calls.remove(uuid);
    ForkIncomingCallWakeLock.release(uuid);
    outgoingSetupCalls.remove(uuid);
    if (callRecord != null) {
      VoiceApplicationProxy.getCallRecordDatabase().remove(callRecord);
      ForkIncomingCallActivity.finishFor(callRecord);
    }
    VoiceService.removeForegroundNotificationIfRunning();
    VoiceApplicationProxy.getMediaPlayerManager().stop();
    VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().deactivate();

    String callSid = snapshot == null ? null : snapshot.callSid;
    ForkInvitePayloadStore.clear(callSid);
    ForkNotificationIdentity.cancelForCallSid(context, callSid);
    ForkVoiceMessageGuard.markSettled(callSid);
    ForkLockScreenFlags.clearForEndedCall();
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
  }

  @Nullable
  static TwilioState twilioState(@NonNull UUID uuid) {
    CallState state = calls.get(uuid);
    return state == null ? null : state.twilioState;
  }

  static void ownerReleased(@NonNull UUID uuid) {
    calls.remove(uuid);
  }

  private static boolean isTerminalTwilioState(@NonNull UUID uuid) {
    TwilioState state = twilioState(uuid);
    return state != null && state.isTerminal();
  }

  private static void markEndedForSid(@Nullable String callSid) {
    if (callSid == null || callSid.isEmpty()) return;
    for (CallState state : calls.values()) {
      if (!callSid.equals(state.callSid)) continue;
      state.twilioState = TwilioState.DISCONNECTED;
      boolean telecomManaged = state.uuid != null
        && ForkTelecomManager.isTelecomAudioOwner(state.uuid);
      finishCall(state);
      if (state.uuid != null && !telecomManaged) {
        ForkSingleCallSession.releaseIfOwner(state.uuid);
      }
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
    volatile Direction direction;
    @Nullable volatile String callSid;
    volatile TelecomState telecomState;
    volatile TwilioState twilioState;

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
      return telecomState != TelecomState.NOT_REPORTED
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
