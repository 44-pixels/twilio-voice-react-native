package com.twiliovoicereactnative;

import android.content.Context;
import android.util.Pair;
import com.facebook.react.bridge.WritableMap;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.UnregistrationListener;

public class RegistrationListenerProxy {
  // >>> FORK KAR-878 — see ForkLogger.java
  private static final ForkLogger logger = new ForkLogger(RegistrationListenerProxy.class);
  // <<< FORK

  public static RegistrationListener createRegistrationListener(Context context, ModuleProxy.UniversalPromise promise) {
    return new RegistrationListener() {
      @Override
      public void onRegistered(String accessToken, String fcmToken) {
        // >>> FORK KAR-878 — see ForkLogger.java
        logger.debug("Successfully registered FCM");
        // <<< FORK

        final WritableMap payload = JSEventEmitter.constructJSMap(
          new Pair(CommonConstants.VoiceEventType, CommonConstants.VoiceEventRegistered)
        );
        VoiceApplicationProxy.getJSEventEmitter().sendEvent(CommonConstants.ScopeVoice, payload);
        promise.resolve(null);
      }

      @Override
      public void onError(
        RegistrationException registrationException,
        String accessToken,
        String fcmToken
      ) {
        final String errorMessage = context.getString(
          R.string.registration_error,
          registrationException.getErrorCode(),
          registrationException.getMessage()
        );
        // >>> FORK KAR-878 — see ForkLogger.java
        logger.error(registrationException, errorMessage);
        // <<< FORK

        final WritableMap payload = JSEventEmitter.constructJSMap(
          new Pair(
            CommonConstants.VoiceEventType,
            CommonConstants.VoiceEventError
          ),
          new Pair(
            CommonConstants.VoiceErrorKeyError,
            ReactNativeArgumentsSerializer.serializeVoiceException(registrationException)
          )
        );
        VoiceApplicationProxy.getJSEventEmitter().sendEvent(CommonConstants.ScopeVoice, payload);
        promise.rejectWithCode(
          registrationException.getErrorCode(),
          registrationException.getMessage()
        );
      }
    };
  }

  public static UnregistrationListener createUnregistrationListener(Context context, ModuleProxy.UniversalPromise promise) {
    return new UnregistrationListener() {
      @Override
      public void onUnregistered(String accessToken, String fcmToken) {
        // >>> FORK KAR-878 — see ForkLogger.java
        logger.debug("Successfully unregistered FCM");
        // <<< FORK
        final WritableMap payload = JSEventEmitter.constructJSMap(
          new Pair(CommonConstants.VoiceEventType, CommonConstants.VoiceEventUnregistered)
        );
        VoiceApplicationProxy.getJSEventEmitter().sendEvent(CommonConstants.ScopeVoice, payload);
        promise.resolve(null);
      }

      @Override
      public void onError(RegistrationException registrationException, String accessToken, String fcmToken) {
        final String errorMessage = context.getString(
          R.string.unregistration_error,
          registrationException.getErrorCode(),
          registrationException.getMessage()
        );
        // >>> FORK KAR-878 — see ForkLogger.java
        logger.error(registrationException, errorMessage);
        // <<< FORK
        final WritableMap payload = JSEventEmitter.constructJSMap(
          new Pair(CommonConstants.VoiceEventType, CommonConstants.VoiceEventError),
          new Pair(CommonConstants.VoiceErrorKeyError, ReactNativeArgumentsSerializer.serializeVoiceException(registrationException))
        );
        VoiceApplicationProxy.getJSEventEmitter().sendEvent(CommonConstants.ScopeVoice, payload);
        promise.rejectWithCode(registrationException.getErrorCode(), registrationException.getMessage());
      }
    };
  }
}
