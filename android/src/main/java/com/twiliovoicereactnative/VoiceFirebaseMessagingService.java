package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getVoiceServiceApi;

import com.twiliovoicereactnative.CallRecordDatabase.CallRecord;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;

import java.util.Map;
import java.util.UUID;

public class VoiceFirebaseMessagingService extends FirebaseMessagingService {
  private static final SDKLog logger = new SDKLog(VoiceFirebaseMessagingService.class);

  public static class MessageHandler implements MessageListener  {
    private final Map<String, String> payload;

    public MessageHandler(@Nullable Map<String, String> payload) {
      this.payload = payload;
    }

    @Override
    public void onCallInvite(@NonNull CallInvite callInvite) {
      logger.log(String.format("onCallInvite %s", callInvite.getCallSid()));

      final CallRecord callRecord = new CallRecord(UUID.randomUUID(), callInvite);
      // >>> FORK KAR-443 — enforce one Twilio call before this invite changes shared state
      try {
        if (!ForkCallLifecycleCoordinator.claimIncoming(
          getVoiceServiceApi().getServiceContext(),
          callRecord)) return;
        ForkIncomingCallWakeLock.bindToCall(callRecord.getUuid(), payload);
      } finally {
        ForkIncomingCallWakeLock.releaseForPayload(payload);
      }
      // <<< FORK
      // >>> FORK KAR-492 — see ForkInvitePayloadStore.java
      ForkInvitePayloadStore.remember(callInvite, payload);
      // <<< FORK

      getCallRecordDatabase().add(callRecord);
      // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
      ForkCallLifecycleCoordinator.incomingInvite(getVoiceServiceApi().getServiceContext(), callRecord);
      // <<< FORK
      getVoiceServiceApi().incomingCall(callRecord);
      // >>> FORK KAR-492 — see ForkVoiceMessageGuard.java
      ForkVoiceMessageGuard.markPresented(payload, callInvite.getCallSid());
      // <<< FORK
    }

    @Override
    public void onCancelledCallInvite(@NonNull CancelledCallInvite cancelledCallInvite,
                                      @Nullable CallException callException) {
      logger.log(String.format("onCancelledCallInvite %s", cancelledCallInvite.getCallSid()));

      // >>> FORK KAR-443 — a cancellation callback never owns an unbound invite wake lock
      ForkIncomingCallWakeLock.releaseForPayload(payload);
      // <<< FORK

      // >>> FORK KAR-492, KAR-809 — see ForkCancelledInviteCleanup.java
      CallRecord callRecord = ForkCancelledInviteCleanup
        .settleOrCancelNotification(cancelledCallInvite, callException);
      if (callRecord == null) return;
      // <<< FORK

      // >>> FORK KAR-443, KAR-809 — only the winning settlement changes lifecycle
      ForkCallLifecycleCoordinator.cancelledInvite(callRecord);
      ForkCancelledInviteCleanup.removeSettledRecord(callRecord);
      // <<< FORK

      getVoiceServiceApi().cancelCall(callRecord);
    }
  }

  @Override
  public void onNewToken(@NonNull String token) {
    logger.log("Refreshed FCM token: " + token);
    // >>> FORK KAR-492 — durable public PushTokenChanged event delivery
    NativeFirebaseMessageHandler.onNewToken(this, token);
    // <<< FORK
  }

  /**
   * Called when message is received.
   *
   * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
   */
  @Override
  public void onMessageReceived(RemoteMessage remoteMessage) {
    logger.debug("onMessageReceived remoteMessage: " + remoteMessage.toString());
    logger.debug("Bundle data: " + remoteMessage.getData());
    logger.debug("From: " + remoteMessage.getFrom());

    // Check if message contains a data payload.
    if (!remoteMessage.getData().isEmpty()) {
      // >>> FORK KAR-443 — see ForkCallLifecycleCoordinator.java
      if (!ForkCallLifecycleCoordinator.handleNativeFcm(this, remoteMessage.getData())) {
      // <<< FORK
        logger.error("The message was not a valid Twilio Voice SDK payload: " +
          remoteMessage.getData());
      }
    }
  }
}
