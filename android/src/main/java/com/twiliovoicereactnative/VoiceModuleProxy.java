package com.twiliovoicereactnative;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.google.firebase.messaging.FirebaseMessaging;
import com.twilio.audioswitch.AudioDevice;
import com.twilio.voice.Call;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.PreflightOptions;
import com.twilio.voice.PreflightTest;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.UnregistrationListener;
import com.twilio.voice.Voice;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

class VoiceModuleProxy {
  private final SDKLog logger = new SDKLog(VoiceModuleProxy.class);

  private final ReactApplicationContext reactApplicationContext;

  private final AudioSwitchManager audioSwitchManager;

  public VoiceModuleProxy(
    ReactApplicationContext reactApplicationContext,
    AudioSwitchManager audioSwitchManager
  ) {
    this.reactApplicationContext = reactApplicationContext;
    this.audioSwitchManager = audioSwitchManager;
  }

  public void connect(
    String accessToken,
    Map<String, String> twimlParams,
    String notificationDisplayName,
    ModuleProxy.UniversalPromise promise
  ) {
    logger.debug(".connect()");

    ForkTwilioVoiceThread.run(() -> {
      logger.debug(".connect() > runnable");

      // connect & create call record
      final UUID uuid = UUID.randomUUID();
      final String callRecipient =
        twimlParams.containsKey("to") && !(Objects.requireNonNull(twimlParams.get("to")).isBlank())
          ? twimlParams.get("to")
          : this.reactApplicationContext.getString(R.string.unknown_call_recipient);
      ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
        .enableDscp(true)
        .params(twimlParams)
        .callMessageListener(new CallMessageListenerProxy())
        .build();
      // >>> FORK KAR-443 — claim only after synchronous option validation
      if (!ForkCallLifecycleCoordinator.claimOutgoing(uuid)) {
        promise.rejectWithName(
          CommonConstants.ErrorCodeInvalidStateError,
          "Cannot start a second call while another call is in progress.");
        return;
      }
      // <<< FORK
      try {
        final Call call = VoiceApplicationProxy.getVoiceServiceApi().connect(
          connectOptions,
          new CallListenerProxy(
            uuid,
            VoiceApplicationProxy.getVoiceServiceApi().getServiceContext()
          )
        );

        CallRecordDatabase.CallRecord callRecord = new CallRecordDatabase.CallRecord(
          uuid,
          call,
          callRecipient,
          twimlParams,
          CallRecordDatabase.CallRecord.Direction.OUTGOING,
          notificationDisplayName
        );

        // >>> FORK KAR-443 — allocate the outgoing notification before Telecom registration
        callRecord.setNotificationId(NotificationUtility.createNotificationIdentifier());
        // <<< FORK
        VoiceApplicationProxy.getCallRecordDatabase().add(callRecord);
        // >>> FORK KAR-443 — foreground before registering outgoing calls with Telecom
        if (!VoiceApplicationProxy.getVoiceServiceApi().raiseOutgoingCallNotification(callRecord)) {
          VoiceApplicationProxy.getCallRecordDatabase().remove(callRecord);
          ForkCallLifecycleCoordinator.outgoingConnectFailed(uuid);
          promise.rejectWithName(
            CommonConstants.ErrorCodeInvalidStateError,
            "Unable to start active call foreground service.");
          return;
        }
        ForkCallLifecycleCoordinator.outgoingInvite(
          VoiceApplicationProxy.getVoiceServiceApi().getServiceContext(),
          callRecord);
        // <<< FORK

        // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
        ForkCallLifecycleCoordinator.outgoingConnecting(callRecord);
        // <<< FORK

        // notify JS layer
        final WritableMap jsCall = ReactNativeArgumentsSerializer.serializeCall(callRecord);
        promise.resolve(jsCall);
      } catch (SecurityException e) {
        // >>> FORK KAR-443 — release a failed outgoing reservation
        ForkCallLifecycleCoordinator.outgoingConnectFailed(uuid);
        // <<< FORK
        promise.rejectWithCode(31401, e.getMessage());
      // >>> FORK KAR-443 — release any other synchronous outgoing setup failure
      } catch (RuntimeException e) {
        ForkCallLifecycleCoordinator.outgoingConnectFailed(uuid);
        promise.rejectWithName(
          CommonConstants.ErrorCodeInvalidStateError,
          e.getMessage() == null ? "Unable to start outgoing call." : e.getMessage());
      }
      // <<< FORK
    });
  }

  // >>> FORK KAR-873 — see ForkCallbackRequestStore
  public void clearCallbackRequest(String requestId, ModuleProxy.UniversalPromise promise) {
    ForkCallbackRequestStore.clearCallbackRequest(this.reactApplicationContext, requestId, promise);
  }

  public void getInitialCallbackRequest(ModuleProxy.UniversalPromise promise) {
    ForkCallbackRequestStore.getInitialCallbackRequest(this.reactApplicationContext, promise);
  }
  // <<< FORK

  public void getAudioDevices(ModuleProxy.UniversalPromise promise) {
    logger.debug(".getAudioDevices()");

    Map<String, AudioDevice> audioDevices = this.audioSwitchManager.getAudioDevices();
    String selectedAudioDeviceUuid = this.audioSwitchManager.getSelectedAudioDeviceUuid();
    AudioDevice selectedAudioDevice = this.audioSwitchManager.getSelectedAudioDevice();

    WritableMap audioDeviceInfo = ReactNativeArgumentsSerializer.serializeAudioDeviceInfo(
      audioDevices,
      selectedAudioDeviceUuid,
      selectedAudioDevice
    );

    promise.resolve(audioDeviceInfo);
  }

  public void selectAudioDevice(String uuid, ModuleProxy.UniversalPromise promise) {
    logger.debug(".selectAudioDevice()");

    AudioDevice audioDevice = this.audioSwitchManager.getAudioDevices().get(uuid);
    if (audioDevice == null) {
      final String warningMsg = this.reactApplicationContext
        .getString(R.string.missing_audiodevice_uuid, uuid);
      promise.rejectWithName(CommonConstants.ErrorCodeInvalidArgumentError, warningMsg);
      return;
    }

    // >>> FORK KAR-443 — Core Telecom exclusively routes managed calls
    boolean handledByTelecom = ForkCallLifecycleCoordinator.selectAudioDevice(audioDevice, routed -> {
      ForkTwilioVoiceThread.run(() -> {
        if (routed) {
          promise.resolve(null);
        } else {
          promise.rejectWithName(
            CommonConstants.ErrorCodeInvalidStateError,
            "Core Telecom could not select the requested audio device.");
        }
      });
    });
    if (!handledByTelecom) {
      this.audioSwitchManager.getAudioSwitch().selectDevice(audioDevice);
      promise.resolve(null);
    }
    // <<< FORK
  }


  public void getCalls(ModuleProxy.UniversalPromise promise) {
    logger.debug(".getCalls()");

    ForkTwilioVoiceThread.run(() -> {
      logger.debug(".getCalls() > runnable");

      WritableArray callInfos = Arguments.createArray();
      for (
        CallRecordDatabase.CallRecord callRecord :
        VoiceApplicationProxy.getCallRecordDatabase().getCollection()
      ) {
        // incoming calls that have not been acted on do not have call-objects
        if (null != callRecord.getVoiceCall()) {
          final WritableMap callInfo = ReactNativeArgumentsSerializer.serializeCall(callRecord);
          callInfos.pushMap(callInfo);
        }
      }

      promise.resolve(callInfos);
    });
  }

  public void getCallInvites(ModuleProxy.UniversalPromise promise) {
    logger.debug(".getCallInvites()");

    ForkTwilioVoiceThread.run(() -> {
      logger.debug(".getCallInvites() > runnable");

      WritableArray callInviteInfos = Arguments.createArray();
      for (
        CallRecordDatabase.CallRecord callRecord :
        VoiceApplicationProxy.getCallRecordDatabase().getCollection()
      ) {
        if (
          null != callRecord.getCallInvite() &&
            CallRecordDatabase.CallRecord.CallInviteState.ACTIVE == callRecord.getCallInviteState()
        ) {
          final WritableMap callInviteInfo =
            ReactNativeArgumentsSerializer.serializeCallInvite(callRecord);
          callInviteInfos.pushMap(callInviteInfo);
        }
      }

      promise.resolve(callInviteInfos);
    });
  }

  public void getDeviceToken(ModuleProxy.UniversalPromise promise) {
    FirebaseMessaging.getInstance().getToken()
      .addOnCompleteListener(task -> {
        if (!task.isSuccessful()) {
          final String warningMsg = reactApplicationContext.getString(
            R.string.fcm_token_registration_fail,
            task.getException()
          );
          logger.warning(warningMsg);
          promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, warningMsg);
          return;
        }

        // Get FCM registration token
        String fcmToken = task.getResult();

        if (fcmToken == null) {
          final String warningMsg = reactApplicationContext.getString(R.string.fcm_token_null);
          logger.warning(warningMsg);
          promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, warningMsg);
          return;
        }

        promise.resolve(fcmToken);
      });
  }

  public void getVersion(ModuleProxy.UniversalPromise promise) {
    logger.debug(".getVersion()");
    ForkTwilioVoiceThread.run(() -> promise.resolve(Voice.getVersion()));
  }

  public void handleEvent(Map<String, String> eventData, ModuleProxy.UniversalPromise promise) {
    logger.debug(".handleEvent()");

    ForkTwilioVoiceThread.run(() -> {
      logger.debug(".handleEvent() > runnable");

      // validate embedded firebase module is disabled
      if (ConfigurationProperties.isFirebaseServiceEnabled(reactApplicationContext)) {
        final String errorMsg = reactApplicationContext.getString(R.string.method_invocation_invalid);
        logger.warning("Embedded firebase messaging enabled, handleEvent invocation invalid!");
        promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, errorMsg);
        return;
      }

      // attempt to parse message
      // >>> FORK KAR-492 — see ForkVoiceMessageGuard.java
      final boolean success = ForkVoiceMessageGuard.handleEvent(reactApplicationContext, eventData);
      // <<< FORK

      promise.resolve(success);
    });
  }

  public void register(String token, ModuleProxy.UniversalPromise promise) {
    logger.debug(".register()");

    ForkTwilioVoiceThread.run(() -> {
      logger.debug(".register() > runnable");

      FirebaseMessaging.getInstance().getToken()
        .addOnCompleteListener(task -> {
          if (!task.isSuccessful()) {
            final String warningMsg = this.reactApplicationContext
              .getString(R.string.fcm_token_registration_fail, task.getException());
            logger.warning(warningMsg);
            promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, warningMsg);
            return;
          }

          // Get new FCM registration token
          String fcmToken = task.getResult();

          if (fcmToken == null) {
            final String warningMsg = this.reactApplicationContext.getString(R.string.fcm_token_null);
            logger.warning(warningMsg);
            promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, warningMsg);
            return;
          }

          // Log and toast
          logger.debug("Registering with FCM with token " + fcmToken);
          RegistrationListener registrationListener = RegistrationListenerProxy.createRegistrationListener(this.reactApplicationContext, promise);
          ForkTwilioVoiceThread.run(
            () -> Voice.register(token, Voice.RegistrationChannel.FCM, fcmToken, registrationListener));
        });
    });
  }

  public void runPreflight(PreflightOptions preflightOptions, ModuleProxy.UniversalPromise promise) {
    final UUID uuid = UUID.randomUUID();

    ForkTwilioVoiceThread.run(() -> {
      final PreflightTestRecordDatabase.PreflightTestRecord existingPreflightTest = VoiceApplicationProxy
        .getPreflightTestRecordDatabase()
        .getRecord();

      if (existingPreflightTest != null) {
        logger.debug(String.format("existing preflight test: \"%s\"", existingPreflightTest.getUuid()));
        switch (existingPreflightTest.getPreflightTest().getState()) {
          case CONNECTED, CONNECTING -> {
            promise.rejectWithName(
              CommonConstants.ErrorCodeInvalidStateError,
              "Cannot start a PreflightTest while one exists in-progress."
            );
            return;
          }
        }
      } else {
        logger.debug("no existing preflight test");
      }

      final PreflightTest preflightTest = Voice.runPreflight(
        this.reactApplicationContext,
        preflightOptions,
        new PreflightTestListenerProxy(uuid)
      );

      VoiceApplicationProxy.getPreflightTestRecordDatabase().setRecord(uuid, preflightTest);

      promise.resolve(uuid.toString());
    });
  }

  public void setIncomingCallContactHandleTemplate(String template, ModuleProxy.UniversalPromise promise) {
    logger.debug(".setIncomingCallContactHandleTemplate()");
    ConfigurationProperties.setIncomingCallContactHandleTemplate(this.reactApplicationContext, template);
    promise.resolve(null);
  }

  public void unregister(String token, ModuleProxy.UniversalPromise promise) {
    logger.debug(".unregister()");

    ForkTwilioVoiceThread.run(() -> {
      logger.debug(".unregister() > runnable");

      FirebaseMessaging.getInstance().getToken()
        .addOnCompleteListener(task -> {
          if (!task.isSuccessful()) {
            final String warningMsg = this.reactApplicationContext
              .getString(R.string.fcm_token_registration_fail, task.getException());
            logger.warning(warningMsg);
            promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, warningMsg);
            return;
          }

          // Get new FCM registration token
          String fcmToken = task.getResult();

          if (fcmToken == null) {
            final String warningMsg = this.reactApplicationContext
              .getString(R.string.fcm_token_null);
            logger.warning(warningMsg);
            promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, warningMsg);
            return;
          }

          // Log and toast
          logger.debug("Registering with FCM with token " + fcmToken);
          UnregistrationListener unregistrationListener = RegistrationListenerProxy.createUnregistrationListener(this.reactApplicationContext, promise);
          ForkTwilioVoiceThread.run(
            () -> Voice.unregister(token, Voice.RegistrationChannel.FCM, fcmToken, unregistrationListener));
        });
    });
  }
}
