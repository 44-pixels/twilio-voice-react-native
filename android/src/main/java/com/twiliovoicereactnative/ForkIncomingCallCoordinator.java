// FORK — KAR-443
// Owns: native incoming-call presentation lifecycle: wake screen for native FCM,
// mirror Twilio call state into Android Telecom, and keep the full-screen
// incoming-call Activity state-driven. Telecom owns system surfaces (call log,
// headset/wear/auto controls); ForkIncomingCallActivity owns the phone lock-screen UX.
// Hooks into: NativeFirebaseMessageHandler, VoiceFirebaseMessagingService,
// VoiceService, and CallListenerProxy.
// Re-check on SDK bump: CallInvite/CancelledCallInvite lifecycle ordering,
// VoiceService accept/reject/cancel semantics, and CallListenerProxy terminal
// callbacks.
package com.twiliovoicereactnative;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.CallException;

import java.util.Map;

final class ForkIncomingCallCoordinator {
  private ForkIncomingCallCoordinator() {}

  static boolean handleNativeFcm(@NonNull Context context,
                                 @Nullable Map<String, String> data) {
    if (data == null || data.isEmpty()) return false;
    if (ForkVoiceMessageGuard.shouldWakeForIncomingCall(context, data)) {
      ForkIncomingCallWakeLock.acquireIfIncomingCall(context, data);
    }
    return ForkVoiceMessageGuard.handleNativeFcm(context, data);
  }

  static void onInvite(@NonNull Context context,
                       @NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkTelecomManager.reportIncomingCall(context, callRecord);
  }

  static void onCancelledInvite(@Nullable String callSid) {
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishForCallSid(callSid);
    ForkTelecomManager.cancelIncomingCall(callSid);
  }

  static void onAnswered(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    ForkTelecomManager.markAnswered(callRecord);
  }

  static void onRejected(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    ForkTelecomManager.markRejected(callRecord);
  }

  static void onCancelled(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    ForkTelecomManager.cancelIncomingCall(callRecord.getCallSid());
  }

  static void onConnected(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkTelecomManager.markActive(callRecord);
  }

  static void onDisconnected(@NonNull CallRecordDatabase.CallRecord callRecord,
                             @Nullable CallException callException) {
    ForkIncomingCallWakeLock.release();
    ForkIncomingCallActivity.finishFor(callRecord);
    ForkTelecomManager.markDisconnected(callRecord, callException);
  }
}
