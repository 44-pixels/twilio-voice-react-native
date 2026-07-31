// FORK — KAR-492
// Owns: public native entry points for host-app Firebase Messaging multiplexers,
// including Twilio payload routing and FCM token changes.
// Hooks into: host app FirebaseMessagingService implementations that need to
// route Twilio Voice payloads before RN/JS startup.
// Re-check on SDK bump: VoiceFirebaseMessagingService.MessageHandler still
// owns CallInvite/CancelledCallInvite handling, ForkCallLifecycleCoordinator
// still wakes incoming calls before Voice.handleMessage, Twilio call payloads
// still carry twi_message_type=twilio.voice.call, and onNewToken remains the
// Firebase token-refresh callback.
package com.twiliovoicereactnative;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Native-first Twilio Voice FCM handler for apps that provide their own
 * FirebaseMessagingService, for example to multiplex with RNFirebase.
 *
 * <p>Call this before starting React Native / JS. It returns {@code true} when
 * the payload was consumed by the Voice SDK or intentionally skipped by the
 * fork's duplicate/stale-message guard. Return immediately in that case; pass
 * the message to the app's regular Firebase handler only when this returns
 * {@code false}.</p>
 */
public final class NativeFirebaseMessageHandler {
  private NativeFirebaseMessageHandler() {}

  public static boolean handle(@NonNull Context context, @NonNull RemoteMessage message) {
    return handle(context, message.getData());
  }

  public static boolean handle(@NonNull Context context,
                               @Nullable Map<String, String> data) {
    // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
    return ForkCallLifecycleCoordinator.handleNativeFcm(context, data);
    // <<< FORK
  }

  public static void onNewToken(@NonNull Context context, @NonNull String token) {
    ForkPushTokenChanged.onNewToken(context, token);
  }
}
