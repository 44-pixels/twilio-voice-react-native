// FORK — KAR-443
// Owns: Java-facing boundary for Android Telecom call lifecycle mirroring via
// Jetpack Core-Telecom. Keeps fork Java code away from Core-Telecom/Kotlin API
// details and exposes Telecom-owned endpoint state to the React Native API.
// Hooks into: ForkCallLifecycleCoordinator and app.plugin.js.
// Re-check on SDK bump: Core-Telecom mirroring lifecycle and VoiceService action names.
package com.twiliovoicereactnative;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.WritableMap;
import com.twilio.voice.CallException;

final class ForkTelecomManager {
  private static final SDKLog logger = new SDKLog(ForkTelecomManager.class);

  private ForkTelecomManager() {}

  static boolean isAvailable(@NonNull Context context) {
    return ForkCoreTelecomManager.isAvailable(context);
  }

  static void reportIncomingCall(@NonNull Context context,
                                 @NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCoreTelecomManager.reportIncomingCall(context, callRecord);
  }

  static void reportOutgoingCall(@NonNull Context context,
                                 @NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCoreTelecomManager.reportOutgoingCall(context, callRecord);
  }

  static int authorizeAnswer(@NonNull CallRecordDatabase.CallRecord callRecord) {
    return ForkCoreTelecomManager.authorizeAnswer(callRecord);
  }

  static boolean isTelecomAudioOwner(@NonNull CallRecordDatabase.CallRecord callRecord) {
    return ForkCoreTelecomManager.isTelecomAudioOwner(callRecord);
  }

  static boolean isTelecomAudioOwner(@NonNull java.util.UUID uuid) {
    return ForkCoreTelecomManager.isTelecomAudioOwner(uuid);
  }

  @NonNull
  static String stateForLog(@Nullable java.util.UUID uuid) {
    return ForkCoreTelecomManager.stateForLog(uuid);
  }

  static void cancelIncomingCall(@Nullable String callSid) {
    ForkCoreTelecomManager.cancelIncomingCall(callSid);
  }

  static void cleanupTerminalCall(@NonNull java.util.UUID uuid) {
    ForkCoreTelecomManager.cleanupTerminalCall(uuid);
  }

  static void markAnswered(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCoreTelecomManager.markAnswered(callRecord);
  }

  static void markRejected(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCoreTelecomManager.markRejected(callRecord);
  }

  static void markActive(@NonNull CallRecordDatabase.CallRecord callRecord) {
    ForkCoreTelecomManager.markActive(callRecord);
  }

  static void markDisconnected(@NonNull CallRecordDatabase.CallRecord callRecord,
                               @Nullable CallException callException) {
    ForkCoreTelecomManager.markDisconnected(callRecord, callException);
  }

  static void markDisconnected(@NonNull CallRecordDatabase.CallRecord callRecord,
                               @Nullable CallException callException,
                               @NonNull Runnable onAudioReleased) {
    ForkCoreTelecomManager.markDisconnected(callRecord, callException, onAudioReleased);
  }

  @NonNull
  static WritableMap getAudioDevices() {
    return ForkCoreTelecomManager.audioDeviceInfo();
  }

  static int selectAudioDevice(@NonNull String endpointUuid,
                               @NonNull ForkTelecomRouteCallback callback) {
    return ForkCoreTelecomManager.selectAudioDevice(endpointUuid, callback);
  }

  static void sendVoiceServiceAction(@NonNull Context context,
                                     @NonNull String action,
                                     @NonNull CallRecordDatabase.CallRecord callRecord) {
    if (Constants.ACTION_ACCEPT_CALL.equals(action) && launchMainActivity(context, callRecord)) {
      return;
    }

    VoiceService.VoiceServiceAPI api = VoiceApplicationProxy.getVoiceServiceApi();
    if (api != null) {
      if (Constants.ACTION_ACCEPT_CALL.equals(action)) {
        api.acceptCall(callRecord);
        return;
      }
      if (Constants.ACTION_REJECT_CALL.equals(action)) {
        api.rejectCall(callRecord);
        return;
      }
      if (Constants.ACTION_CALL_DISCONNECT.equals(action)) {
        api.disconnect(callRecord);
        return;
      }
    }

    Intent intent = VoiceService.constructMessage(
      context,
      action,
      VoiceService.class,
      callRecord.getUuid());
    try {
      context.getApplicationContext().startService(intent);
    } catch (IllegalStateException | SecurityException e) {
      logger.warning(e, "failed to dispatch Telecom action=" + action);
    }
  }

  private static boolean launchMainActivity(@NonNull Context context,
                                            @NonNull CallRecordDatabase.CallRecord callRecord) {
    Class<?> mainActivityClass = VoiceApplicationProxy.getMainActivityClass();
    if (mainActivityClass == null) {
      logger.warning("cannot launch app for accepted call: no main activity class");
      return false;
    }

    Intent intent = VoiceService.constructMessage(
      context,
      Constants.ACTION_ACCEPT_CALL,
      mainActivityClass,
      callRecord.getUuid());
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    try {
      context.getApplicationContext().startActivity(intent);
      return true;
    } catch (RuntimeException e) {
      logger.warning(e, "failed to launch app for accepted call");
      return false;
    }
  }

}
