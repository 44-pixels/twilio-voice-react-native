// FORK — KAR-443
// Owns: compatibility shim for older one-line hooks. All cross-system call
// policy lives in ForkCallActionOrchestrator.
// Hooks into: NativeFirebaseMessageHandler, VoiceFirebaseMessagingService,
// VoiceService, and CallListenerProxy.
// Re-check on SDK bump: call lifecycle hook names; prefer wiring new hooks
// directly to ForkCallActionOrchestrator.
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
    return ForkCallActionOrchestrator.handleNativeFcm(context, data);
  }

  static void onInvite(@NonNull Context context,
                       @NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCallActionOrchestrator.incomingInvite(context, callRecord);
  }

  static void onCancelledInvite(@Nullable String callSid) {
    ForkCallActionOrchestrator.cancelledInvite(callSid);
  }

  static void onAnswered(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCallActionOrchestrator.answerRequested(callRecord);
  }

  static void onRejected(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCallActionOrchestrator.rejectRequested(callRecord);
  }

  static void onCancelled(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCallActionOrchestrator.cancelledBySystem(callRecord);
  }

  static void onConnected(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCallActionOrchestrator.twilioConnected(callRecord);
  }

  static void onDisconnected(@NonNull CallRecordDatabase.CallRecord callRecord,
                             @Nullable CallException callException) {
    ForkCallActionOrchestrator.twilioDisconnected(callRecord, callException);
  }
}
