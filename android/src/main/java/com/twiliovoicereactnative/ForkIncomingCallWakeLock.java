// FORK — KAR-443
// Owns: waking the screen for native-first incoming-call FCM handling.
// Hooks into: NativeFirebaseMessageHandler and ForkCallLifecycleCoordinator.
// Re-check on SDK bump: whether all incoming-call payloads still carry
// twi_message_type=twilio.voice.call before Voice.handleMessage.
package com.twiliovoicereactnative;

import android.content.Context;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

final class ForkIncomingCallWakeLock {
  private static final String KEY_MESSAGE_TYPE = "twi_message_type";
  private static final String KEY_CALL_SID = "twi_call_sid";
  private static final String TYPE_CALL = "twilio.voice.call";
  private static final String WAKE_LOCK_TAG =
    "TwilioVoiceReactNative:incomingCallWakeLock";
  private static final long WAKE_LOCK_TIMEOUT_MILLIS = 30_000L;

  private static final SDKLog logger = new SDKLog(ForkIncomingCallWakeLock.class);
  private static final Map<Map<String, String>, Lease> pendingByPayload =
    new IdentityHashMap<>();
  private static final Map<UUID, Lease> activeByCall = new HashMap<>();

  private ForkIncomingCallWakeLock() {}

  static synchronized void acquireIfIncomingCall(
    @NonNull Context context,
    @Nullable Map<String, String> data
  ) {
    if (data == null || !TYPE_CALL.equals(data.get(KEY_MESSAGE_TYPE))) return;

    PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    if (powerManager == null || powerManager.isInteractive()) return;

    try {
      PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
        WAKE_LOCK_TAG);
      wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
      Lease previous = pendingByPayload.put(
        data,
        new Lease(wakeLock, data.get(KEY_CALL_SID)));
      releaseLease(previous, "replaced pending payload");
      logger.debug("event=incoming_wake_lock action=acquire sid="
        + logValue(data.get(KEY_CALL_SID)));
    } catch (RuntimeException error) {
      logger.warning(error, "failed to acquire incoming-call wake lock");
    }
  }

  static synchronized void bindToCall(@NonNull UUID uuid,
                                      @Nullable Map<String, String> payload) {
    if (payload == null) return;
    Lease lease = pendingByPayload.remove(payload);
    if (lease == null) return;

    Lease previous = activeByCall.put(uuid, lease);
    releaseLease(previous, "replaced call lease");
    logger.debug("event=incoming_wake_lock action=bind uuid=" + uuid
      + " sid=" + logValue(lease.callSid));
  }

  static synchronized void releaseForPayload(@Nullable Map<String, String> payload) {
    if (payload == null) return;
    releaseLease(pendingByPayload.remove(payload), "payload settled before presentation");
  }

  static synchronized void release(@NonNull UUID uuid) {
    releaseLease(activeByCall.remove(uuid), "call settled uuid=" + uuid);
  }

  private static void releaseLease(@Nullable Lease lease, @NonNull String reason) {
    if (lease == null || !lease.wakeLock.isHeld()) return;

    try {
      lease.wakeLock.release();
      logger.debug("event=incoming_wake_lock action=release sid="
        + logValue(lease.callSid) + " reason=" + reason);
    } catch (RuntimeException error) {
      logger.warning(error, "failed to release incoming-call wake lock");
    }
  }

  @NonNull
  private static String logValue(@Nullable String value) {
    return value == null || value.isEmpty() ? "NONE" : value;
  }

  private static final class Lease {
    @NonNull final PowerManager.WakeLock wakeLock;
    @Nullable final String callSid;

    Lease(@NonNull PowerManager.WakeLock wakeLock, @Nullable String callSid) {
      this.wakeLock = wakeLock;
      this.callSid = callSid;
    }
  }
}
