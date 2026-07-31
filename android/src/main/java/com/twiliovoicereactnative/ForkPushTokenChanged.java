// FORK — KAR-492
// Owns: durable Android FCM token-change delivery into the public Voice event API.
// Hooks into: VoiceFirebaseMessagingService, NativeFirebaseMessageHandler, ModuleProxy,
//             TwilioVoiceReactNativeModule, ExpoModule, and Voice.tsx.
// Re-check on SDK bump: FirebaseMessagingService.onNewToken delivery and React
// native-listener setup ordering.
package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.JSEventEmitter.constructJSMap;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;

import java.lang.ref.WeakReference;

final class ForkPushTokenChanged {
  private static final SDKLog logger = new SDKLog(ForkPushTokenChanged.class);
  private static final String PREFS_NAME =
    "com.twiliovoicereactnative.fork.PUSH_TOKEN_CHANGED";
  private static final String PENDING_TOKEN = "pendingToken";

  private static WeakReference<ReactApplicationContext> reactContext =
    new WeakReference<>(null);

  private ForkPushTokenChanged() {}

  static synchronized void onNewToken(@NonNull Context context,
                                      @NonNull String token) {
    SharedPreferences preferences = preferences(context);
    if (!preferences.edit().putString(PENDING_TOKEN, token).commit()) {
      logger.warning("Failed to persist pending FCM token change");
      return;
    }

    emitPendingSignal(token);
  }

  static synchronized void registerContext(
    @NonNull ReactApplicationContext context
  ) {
    reactContext = new WeakReference<>(context);
  }

  static synchronized void consumePending(
    @NonNull Context context,
    @NonNull ModuleProxy.UniversalPromise promise
  ) {
    SharedPreferences preferences = preferences(context);
    String token = preferences.getString(PENDING_TOKEN, null);
    if (token == null || token.isEmpty()) {
      promise.resolve(null);
      return;
    }

    if (!preferences.edit().remove(PENDING_TOKEN).commit()) {
      logger.warning("Failed to consume pending FCM token change");
    }
    promise.resolve(token);
  }

  private static void emitPendingSignal(@NonNull String token) {
    ReactApplicationContext context = reactContext.get();
    if (context == null || !context.hasActiveReactInstance()) return;

    VoiceApplicationProxy.getJSEventEmitter().sendEvent(
      CommonConstants.ScopeVoice,
      constructJSMap(
        new Pair<>(
          CommonConstants.VoiceEventType,
          CommonConstants.VoiceEventPushTokenChanged),
        new Pair<>(CommonConstants.VoiceEventPushToken, token)));
  }

  @NonNull
  private static SharedPreferences preferences(@NonNull Context context) {
    return context.getApplicationContext().getSharedPreferences(
      PREFS_NAME,
      Context.MODE_PRIVATE);
  }
}
