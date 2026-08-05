package com.twiliovoicereactnative;


import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyCallSid;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyType;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueAccepted;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueCancelled;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueNotificationTapped;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueRejected;
import static com.twiliovoicereactnative.CommonConstants.ScopeCallInvite;
import static com.twiliovoicereactnative.CommonConstants.ScopeVoice;
import static com.twiliovoicereactnative.CommonConstants.VoiceErrorKeyError;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventError;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventType;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventTypeValueIncomingCallInvite;
import static com.twiliovoicereactnative.Constants.ACTION_ACCEPT_CALL;
import static com.twiliovoicereactnative.Constants.ACTION_CALL_DISCONNECT;
import static com.twiliovoicereactnative.Constants.ACTION_CANCEL_CALL;
import static com.twiliovoicereactnative.Constants.ACTION_CANCEL_ACTIVE_CALL_NOTIFICATION;
import static com.twiliovoicereactnative.Constants.ACTION_FOREGROUND_AND_DEPRIORITIZE_INCOMING_CALL_NOTIFICATION;
import static com.twiliovoicereactnative.Constants.ACTION_INCOMING_CALL;
import static com.twiliovoicereactnative.Constants.ACTION_PUSH_APP_TO_FOREGROUND;
import static com.twiliovoicereactnative.Constants.ACTION_RAISE_OUTGOING_CALL_NOTIFICATION;
import static com.twiliovoicereactnative.Constants.ACTION_REJECT_CALL;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CALL_INVITE_INFO;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CANCELLED_CALL_INVITE_INFO;
import static com.twiliovoicereactnative.Constants.VOICE_CHANNEL_HIGH_IMPORTANCE;
import static com.twiliovoicereactnative.JSEventEmitter.constructJSMap;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCall;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCallException;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCallInvite;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCancelledCallInvite;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeError;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getJSEventEmitter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ServiceCompat;

import com.facebook.react.bridge.WritableMap;
import com.twilio.voice.AcceptOptions;
import com.twilio.voice.Call;
// >>> FORK KAR-809 — see ForkCallInviteSettlement.java
import com.twilio.voice.CallInvite;
// <<< FORK
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.Voice;

import java.lang.ref.WeakReference;
import java.util.UUID;

public class VoiceService extends Service {
  private static final SDKLog logger = new SDKLog(VoiceService.class);
  private static WeakReference<VoiceService> runningService = new WeakReference<>(null);
  public class VoiceServiceAPI extends Binder {
    public Call connect(@NonNull ConnectOptions cxnOptions,
                        @NonNull Call.Listener listener) {
      logger.debug("connect");
      return ForkTwilioVoiceThread.callValueBlocking(
        () -> Voice.connect(VoiceService.this, cxnOptions, listener));
    }
    public void disconnect(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.disconnect(callRecord);
    }
    public void incomingCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.incomingCall(callRecord);
    }
    public void acceptCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.acceptCall(callRecord);
    }
    public void rejectCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.rejectCall(callRecord);
    }
    public void cancelCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.cancelCall(callRecord);
    }
    // >>> FORK KAR-443 — active outgoing foregrounding reports failure
    public boolean raiseOutgoingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
      return VoiceService.this.raiseOutgoingCallNotification(callRecord);
    }
    // <<< FORK
    public void cancelActiveCallNotification(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.cancelActiveCallNotification(callRecord);
    }
    public void foregroundAndDeprioritizeIncomingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.foregroundAndDeprioritizeIncomingCallNotification(callRecord);
    }
    public Context getServiceContext() {
      return VoiceService.this;
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    runningService = new WeakReference<>(this);
  }

  @Override
  public void onDestroy() {
    VoiceService service = runningService.get();
    if (service == this) {
      runningService = new WeakReference<>(null);
    }
    super.onDestroy();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // apparently the system can recreate the service without sending it an intent so protect
    // against that case (GH-430).
    if (null != intent) {
      // >>> FORK KAR-316 (Sentry KAREN-APP-58) — see ForkCallRecordLookup
      final String action = intent.getAction();
      if (action == null) {
        logger.warning("VoiceService received intent with no action, ignoring");
        return START_NOT_STICKY;
      }
      // <<< FORK
      switch (action) {
        case ACTION_INCOMING_CALL:
          incomingCall(getCallRecord(getMessageUUID(intent)));
          break;
        case ACTION_ACCEPT_CALL:
          try {
            acceptCall(getCallRecord(getMessageUUID(intent)));
          } catch (SecurityException e) {
            sendPermissionsError();
            logger.warning(e, "Cannot accept call, lacking necessary permissions");
          }
          break;
        case ACTION_REJECT_CALL:
          rejectCall(getCallRecord(getMessageUUID(intent)), intent);
          break;
        case ACTION_CANCEL_CALL:
          cancelCall(getCallRecord(getMessageUUID(intent)));
          break;
        case ACTION_CALL_DISCONNECT:
          disconnect(getCallRecord(getMessageUUID(intent)));
          break;
        case ACTION_RAISE_OUTGOING_CALL_NOTIFICATION:
          raiseOutgoingCallNotification(getCallRecord(getMessageUUID(intent)));
          break;
        case ACTION_CANCEL_ACTIVE_CALL_NOTIFICATION:
          cancelActiveCallNotification(getCallRecord(getMessageUUID(intent)));
          break;
        case ACTION_FOREGROUND_AND_DEPRIORITIZE_INCOMING_CALL_NOTIFICATION:
          foregroundAndDeprioritizeIncomingCallNotification(getCallRecord(getMessageUUID(intent)));
          break;
        case ACTION_PUSH_APP_TO_FOREGROUND:
          logger.warning("VoiceService received foreground request, ignoring");
          break;
        default:
          logger.log("Unknown notification, ignoring");
          break;
      }
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return new VoiceServiceAPI();
  }
  public static Intent constructMessage(@NonNull Context context,
                                        @NonNull final String action,
                                        @NonNull final Class<?> target,
                                        @NonNull final UUID uuid) {
    Intent intent = new Intent(context.getApplicationContext(), target);
    intent.setAction(action);
    intent.putExtra(Constants.MSG_KEY_UUID, uuid);
    return intent;
  }
  private void disconnect(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("disconnect");
    if (null != callRecord) {
      // >>> FORK KAR-876 — only disconnect a live call. Call.disconnect() on an
      // already-disconnected call segfaults in libtwilio_voice (Sentry KAREN-APP-E4).
      final Call voiceCall = callRecord.getVoiceCall();
      final boolean willDisconnect =
        voiceCall != null && voiceCall.getState() != Call.State.DISCONNECTED;
      // >>> FORK KAR-878 — see ForkSentryReporter.java
      ForkSentryReporter.recordEndCallAction(
        callRecord.getUuid(),
        true,
        voiceCall,
        willDisconnect,
        getCallRecordDatabase().getCollection().size());
      // <<< FORK
      if (willDisconnect) {
        // >>> FORK KAR-878 — see ForkSentryReporter.java
        ForkSentryReporter.recordDisconnectBoundary(
          "voice.call.disconnect_invocation.before", callRecord.getUuid(), voiceCall);
        // <<< FORK
        ForkTwilioVoiceThread.runBlocking(voiceCall::disconnect);
        // >>> FORK KAR-878 — see ForkSentryReporter.java
        ForkSentryReporter.recordDisconnectBoundary(
          "voice.call.disconnect_invocation.after", callRecord.getUuid(), voiceCall);
        // <<< FORK
      } else {
        logger.warning("disconnect: no live voice call to disconnect");
      }
      // <<< FORK
    } else {
      logger.warning("No call record found");
    }
    // >>> FORK KAR-448 — see ForkLockScreenFlags.java
    ForkLockScreenFlags.clearForEndedCall();
    // <<< FORK
  }
  private void incomingCall(final CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord) { logger.warning("incomingCall: no call record (KAR-316)"); return; } // FORK KAR-316
    logger.debug("incomingCall: " + callRecord.getUuid());

    // verify that mic permissions have been granted and if not, throw a error
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) &&
      ActivityCompat.checkSelfPermission(VoiceService.this,
        Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

      // report to js layer lack of permissions issue
      sendPermissionsError();

      // report an error to logger
      logger.warning("WARNING: Incoming call cannot be handled, microphone permission not granted");
      // >>> FORK KAR-443 — permission failure is terminal for the reserved invite
      ForkCallInviteRejection.markReason(
        callRecord,
        ForkCallInviteRejection.Reason.MICROPHONE_PERMISSION_MISSING);
      rejectCall(callRecord);
      // <<< FORK
      return;
    }

    // put up notification
    // >>> FORK KAR-492 — see ForkNotificationIdentity.java
    callRecord.setNotificationId(ForkNotificationIdentity.incomingNotificationId(callRecord));
    // <<< FORK
    Notification notification = NotificationUtility.createIncomingCallNotification(
      VoiceService.this,
      callRecord,
      VOICE_CHANNEL_HIGH_IMPORTANCE);
    createOrReplaceNotification(callRecord.getNotificationId(), notification);



    // play ringer sound
    // >>> FORK KAR-373 / KAR-443 — ringing stays outside Telecom call-audio activation
    VoiceApplicationProxy.getMediaPlayerManager().play(MediaPlayerManager.SoundTable.INCOMING);
    // <<< FORK

    // trigger JS layer
    sendJSEvent(
      ScopeVoice,
      constructJSMap(
        new Pair<>(VoiceEventType, VoiceEventTypeValueIncomingCallInvite),
        new Pair<>(JS_EVENT_KEY_CALL_INVITE_INFO, serializeCallInvite(callRecord))));
  }
  private void acceptCall(final CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord) { logger.warning("acceptCall: no call record (KAR-316)"); return; } // FORK KAR-316
    logger.debug("acceptCall: " + callRecord.getUuid());

    // >>> FORK KAR-443 — make repeated delivery of the same answer idempotent
    if (callRecord.getVoiceCall() != null) {
      callRecord.resolveCallAcceptedPromise(serializeCall(callRecord));
      return;
    }
    // <<< FORK

    // verify that mic permissions have been granted and if not, throw a error
    if (ActivityCompat.checkSelfPermission(VoiceService.this,
      Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      // cancel incoming call notification
      removeNotification(callRecord.getNotificationId());

      // stop ringer sound
      VoiceApplicationProxy.getMediaPlayerManager().stop();
      // >>> FORK KAR-443 — fail the pending answer before rejecting it
      callRecord.failCallAcceptedPromise("Microphone permission is not granted.");
      // <<< FORK

      // report an error to JS layer
      sendPermissionsError();

      // report an error to logger
      logger.warning("WARNING: Call not accepted, microphone permission not granted");
      // >>> FORK KAR-443 — classify native invite rejection
      ForkCallInviteRejection.markReason(
        callRecord,
        ForkCallInviteRejection.Reason.MICROPHONE_PERMISSION_MISSING);
      // <<< FORK
      rejectCall(callRecord);
      return;
    }

    // >>> FORK KAR-443 — Telecom must authorize app-originated answers first
    if (ForkCallLifecycleCoordinator.authorizeAnswer(callRecord)
      == ForkCoreTelecomManager.ANSWER_PENDING) return;
    // <<< FORK
    // cancel existing notification & put up in call
    Notification notification = NotificationUtility.createCallAnsweredNotificationWithLowImportance(
      VoiceService.this,
      callRecord);
    // >>> FORK KAR-443 — foreground failure must precede and prevent Twilio acceptance
    if (!createOrReplaceForegroundNotification(callRecord.getNotificationId(), notification)) {
      callRecord.failCallAcceptedPromise("Unable to start active call foreground service.");
      ForkCallInviteRejection.markReason(
        callRecord,
        ForkCallInviteRejection.Reason.FOREGROUND_SERVICE_FAILED);
      rejectCall(callRecord);
      return;
    }
    // <<< FORK

    // >>> FORK KAR-809 — settle only after Telecom and foreground prerequisites succeed
    final CallInvite callInvite = ForkCallInviteSettlement.claim(
      callRecord, ForkCallInviteSettlement.Action.ACCEPT);
    if (callInvite == null) {
      removeForegroundNotification();
      ForkCallInviteSettlement.rejectPendingAction(
        callRecord, ForkCallInviteSettlement.Action.ACCEPT);
      return;
    }
    ForkCallInviteSettlement.rejectCompetingAction(
      callRecord, ForkCallInviteSettlement.Action.ACCEPT);
    // <<< FORK

    // stop ringer sound
    VoiceApplicationProxy.getMediaPlayerManager().stop();

    // >>> FORK KAR-448 — see ForkLockScreenFlags.java (foreground-accept path; intent-gated path in VoiceActivityProxy doesn't fire here)
    ForkLockScreenFlags.applyForActiveCall();
    // <<< FORK

    // accept call
    AcceptOptions acceptOptions = new AcceptOptions.Builder()
      .enableDscp(true)
      .callMessageListener(new CallMessageListenerProxy())
      .build();

    // >>> FORK KAR-443, KAR-809 — use the atomically claimed invite and setup reservation
    boolean accepted = ForkCallLifecycleCoordinator.acceptIncoming(
      VoiceService.this,
      callRecord,
      callInvite,
      acceptOptions);
    if (!accepted) return;
    // CallInvite state was transitioned by ForkCallInviteSettlement.claim().
    // <<< FORK

    // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
    ForkCallLifecycleCoordinator.answerRequested(callRecord);
    // <<< FORK

    // >>> FORK KAR-492 — see ForkInvitePayloadStore.java / ForkVoiceMessageGuard.java
    ForkInvitePayloadStore.clear(callRecord.getCallSid());
    ForkVoiceMessageGuard.markSettled(callRecord.getCallSid());
    // <<< FORK

    // handle if event spawned from JS
    callRecord.resolveCallAcceptedPromise(serializeCall(callRecord));

    // notify JS layer
    sendJSEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueAccepted),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid()),
        new Pair<>(JS_EVENT_KEY_CALL_INVITE_INFO, serializeCallInvite(callRecord))));
  }
  private void rejectCall(final CallRecordDatabase.CallRecord callRecord,
                          @NonNull final Intent intent) {
    // >>> FORK KAR-492 — see ForkRejectCallAction.java
    if (null == callRecord) {
      removeForegroundNotification();
      ForkRejectCallAction.rejectFromIntent(VoiceService.this, intent);
      return;
    }
    // <<< FORK
    rejectCall(callRecord);
  }
  private void rejectCall(final CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord) { logger.warning("rejectCall: no call record (KAR-316)"); return; } // FORK KAR-316
    logger.debug("rejectCall: " + callRecord.getUuid());

    // >>> FORK KAR-809 — see ForkCallInviteSettlement.java
    final CallInvite callInvite = ForkCallInviteSettlement.claim(
      callRecord, ForkCallInviteSettlement.Action.REJECT);
    if (callInvite == null) {
      ForkCallInviteSettlement.rejectPendingAction(
        callRecord, ForkCallInviteSettlement.Action.REJECT);
      return;
    }
    ForkCallInviteSettlement.rejectCompetingAction(
      callRecord, ForkCallInviteSettlement.Action.REJECT);
    // <<< FORK

    // >>> FORK KAR-443, KAR-809 — reject and clean while retaining ownership
    ForkCallInviteRejection.rejectClaimed(VoiceService.this, callInvite, callRecord);
    // <<< FORK
  }
  private void cancelCall(final CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord) { logger.warning("cancelCall: no call record (KAR-316)"); return; } // FORK KAR-316
    logger.debug("CancelCall: " + callRecord.getUuid());

    // take down notification
    removeForegroundNotification();
    removeNotification(callRecord.getNotificationId());

    // stop ringer sound
    VoiceApplicationProxy.getMediaPlayerManager().stop();
    // >>> FORK KAR-448 — see ForkLockScreenFlags.java
    ForkLockScreenFlags.clearForEndedCall();
    // <<< FORK
    // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
    ForkCallLifecycleCoordinator.cancelledBySystem(callRecord);
    // <<< FORK
    // >>> FORK KAR-492 — see ForkInvitePayloadStore.java / ForkVoiceMessageGuard.java
    ForkInvitePayloadStore.clear(callRecord.getCallSid());
    ForkVoiceMessageGuard.markSettled(callRecord.getCallSid());
    // <<< FORK

    // notify JS layer
    sendJSEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueCancelled),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid()),
        new Pair<>(JS_EVENT_KEY_CANCELLED_CALL_INVITE_INFO, serializeCancelledCallInvite(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeCallException(callRecord))));
  }
  // >>> FORK KAR-443 — active outgoing foregrounding reports failure to its caller
  private boolean raiseOutgoingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord) { logger.warning("raiseOutgoingCallNotification: no call record (KAR-316)"); return false; } // FORK KAR-316
    logger.debug("raiseOutgoingCallNotification: " + callRecord.getUuid());

    // put up outgoing call notification
    Notification notification =
      NotificationUtility.createOutgoingCallNotificationWithLowImportance(
        VoiceService.this,
        callRecord);
    if (!createOrReplaceForegroundNotification(callRecord.getNotificationId(), notification)) {
      return false;
    }
    return true;
  }
  // <<< FORK
  private void foregroundAndDeprioritizeIncomingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord) { logger.warning("foregroundAndDeprioritizeIncomingCallNotification: no call record (KAR-316)"); return; } // FORK KAR-316
    logger.debug("foregroundAndDeprioritizeIncomingCallNotification: " + callRecord.getUuid());

    // >>> FORK KAR-591 — do NOT deprioritize or stop the ringer; the peeking heads-up is
    // re-posted when the activity's window gains focus (armed on the tap). See ForkIncomingCallFocus.java.
    // <<< FORK

    // notify JS layer
    sendJSEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueNotificationTapped),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid())));
  }
  private void cancelActiveCallNotification(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("cancelNotification");
    // only take down notification & stop any active sounds if one is active
    if (null != callRecord) {
      VoiceApplicationProxy.getMediaPlayerManager().stop();
      removeForegroundNotification();
    }
  }
  private void createOrReplaceNotification(final int notificationId,
                                           final Notification notification) {
    NotificationManager mNotificationManager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    mNotificationManager.notify(notificationId, notification);
  }
  private boolean createOrReplaceForegroundNotification(final int notificationId,
                                                        final Notification notification) {
    // >>> FORK KAR-443 — POST_NOTIFICATIONS is not a prerequisite for foreground execution
    return foregroundNotification(notificationId, notification);
    // <<< FORK
  }
  private void removeNotification(final int notificationId) {
    logger.debug("removeNotification");
    NotificationManager mNotificationManager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    mNotificationManager.cancel(notificationId);
  }
  static void removeForegroundNotificationIfRunning() {
    VoiceService service = runningService.get();
    if (service == null) return;
    service.removeForegroundNotification();
  }
  private void removeForegroundNotification() {
    logger.debug("removeForegroundNotification");
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
  }
  private boolean foregroundNotification(int id, Notification notification) {
    // >>> FORK KAR-443 — see ForkActiveCallForeground.java
    boolean started = ForkActiveCallForeground.start(this, id, notification);
    if (!started) sendPermissionsError();
    return started;
    // <<< FORK
  }
  private static UUID getMessageUUID(@NonNull final Intent intent) {
    // >>> FORK KAR-316 (Sentry KAREN-APP-58) — see ForkCallRecordLookup
    return ForkCallRecordLookup.readUuid(intent);
    // <<< FORK
  }
  private static CallRecordDatabase.CallRecord getCallRecord(final UUID uuid) {
    // >>> FORK KAR-316 (Sentry KAREN-APP-58) — see ForkCallRecordLookup
    // Re-check on SDK bump: whether upstream still requireNonNull's the lookup.
    return ForkCallRecordLookup.getOrNull(uuid);
    // <<< FORK
  }
  private static void sendJSEvent(@NonNull String scope, @NonNull WritableMap event) {
    getJSEventEmitter().sendEvent(scope, event);
  }
  private static void sendPermissionsError() {
    final String errorMessage = "Missing permissions.";
    final int errorCode = 31401;
    getJSEventEmitter().sendEvent(ScopeVoice, constructJSMap(
      new Pair<>(VoiceEventType, VoiceEventError),
      new Pair<>(VoiceErrorKeyError, serializeError(errorCode, errorMessage))
    ));
  }
}
