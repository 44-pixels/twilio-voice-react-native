// FORK — KAR-443
// Owns: waking the screen for native-first incoming-call FCM handling.
// Hooks into: NativeFirebaseMessageHandler.
// Re-check on SDK bump: whether all incoming-call payloads still carry
// twi_message_type=twilio.voice.call before Voice.handleMessage.
package com.twiliovoicereactnative;

import android.content.Context;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

final class ForkIncomingCallWakeLock {
  private static final String KEY_MESSAGE_TYPE = "twi_message_type";
  private static final String TYPE_CALL = "twilio.voice.call";
  private static final String WAKE_LOCK_TAG =
    "TwilioVoiceReactNative:incomingCallWakeLock";
  private static final long WAKE_LOCK_TIMEOUT_MILLIS = 30_000L;

  private static final SDKLog logger = new SDKLog(ForkIncomingCallWakeLock.class);
  @Nullable private static PowerManager.WakeLock activeWakeLock;

  private ForkIncomingCallWakeLock() {}

  static synchronized void acquireIfIncomingCall(@NonNull Context context,
                                                 @Nullable Map<String, String> data) {
    if (data == null || !TYPE_CALL.equals(data.get(KEY_MESSAGE_TYPE))) return;

    PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    if (powerManager == null || powerManager.isInteractive()) return;

    try {
      releaseHeldWakeLock();
      PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
        WAKE_LOCK_TAG);
      wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
      activeWakeLock = wakeLock;
      logger.debug("acquired incoming-call wake lock");
    } catch (RuntimeException e) {
      logger.warning(e, "failed to acquire incoming-call wake lock");
    }
  }

  static synchronized void release() {
    releaseHeldWakeLock();
  }

  private static void releaseHeldWakeLock() {
    PowerManager.WakeLock wakeLock = activeWakeLock;
    activeWakeLock = null;
    if (wakeLock == null || !wakeLock.isHeld()) return;

    try {
      wakeLock.release();
      logger.debug("released incoming-call wake lock");
    } catch (RuntimeException e) {
      logger.warning(e, "failed to release incoming-call wake lock");
    }
  }
}
