// FORK — KAR-443
// Owns: cross-system call action orchestration between Twilio Voice, Jetpack
// Core-Telecom, notification/full-screen surfaces, wake locks, and audio route
// selection. Upstream files should report events here instead of coordinating
// Telecom/Twilio state directly.
// Hooks into: ForkIncomingCallCoordinator, VoiceModuleProxy, and low-level
// ForkTelecomManager/ForkCoreTelecomManager adapters.
// Re-check on SDK bump: Core-Telecom CallControlScope lifecycle, Twilio
// CallInvite accept/reject lifecycle, and AudioSwitch route ownership.
package com.twiliovoicereactnative;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.audioswitch.AudioDevice;
import com.twilio.voice.CallException;

import java.util.Map;

final class ForkCallActionOrchestrator {
  private static final SDKLog logger = new SDKLog(ForkCallActionOrchestrator.class);

  private ForkCallActionOrchestrator() {}

  static boolean handleNativeFcm(@NonNull Context context,
                                 @Nullable Map<String, String> data) {
    if (data == null || data.isEmpty()) return false;
    if (ForkVoiceMessageGuard.shouldWakeForIncomingCall(context, data)) {
      ForkIncomingCallWakeLock.acquireIfIncomingCall(context, data);
    }
    return ForkVoiceMessageGuard.handleNativeFcm(context, data);
  }

  static void incomingInvite(@NonNull Context context,
                             @NonNull CallRecordDatabase.CallRecord callRecord) {
    logger.debug("incomingInvite: " + callRecord.getUuid());
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
    ForkTelecomManager.cancelIncomingCall(callSid);
  }

  static void answerRequested(@NonNull CallRecordDatabase.CallRecord callRecord) {
    logger.debug("answerRequested: " + callRecord.getUuid());
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    ForkTelecomManager.markAnswered(callRecord);
  }

  static void rejectRequested(@NonNull CallRecordDatabase.CallRecord callRecord) {
    logger.debug("rejectRequested: " + callRecord.getUuid());
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    ForkTelecomManager.markRejected(callRecord);
  }

  static void cancelledBySystem(@NonNull CallRecordDatabase.CallRecord callRecord) {
    logger.debug("cancelledBySystem: " + callRecord.getUuid());
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    if (hasAcceptedCallForSid(callRecord.getCallSid())) {
      logger.debug("cancelledBySystem: ignored for already accepted call " + callRecord.getCallSid());
      return;
    }
    ForkTelecomManager.cancelIncomingCall(callRecord.getCallSid());
  }

  static void twilioConnected(@NonNull CallRecordDatabase.CallRecord callRecord) {
    logger.debug("twilioConnected: " + callRecord.getUuid());
    ForkTelecomManager.markActive(callRecord);
  }

  static void twilioDisconnected(@NonNull CallRecordDatabase.CallRecord callRecord,
                                 @Nullable CallException callException) {
    logger.debug("twilioDisconnected: " + callRecord.getUuid());
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    ForkTelecomManager.markDisconnected(callRecord, callException);
  }

  static boolean selectAudioDevice(@NonNull AudioDevice audioDevice,
                                   @NonNull ForkTelecomRouteCallback callback) {
    logger.debug("selectAudioDevice");
    return ForkTelecomManager.selectAudioDevice(audioDevice, callback);
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
}
