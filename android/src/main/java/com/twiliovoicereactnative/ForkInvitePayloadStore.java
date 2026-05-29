// FORK — KAR-492
// Owns: carrying the original Twilio invite FCM payload into notification
// action intents so a killed process can reconstruct and reject the invite.
// Hooks into: VoiceFirebaseMessagingService.MessageHandler.onCallInvite,
//             VoiceModuleProxy.handleEvent, NotificationUtility incoming-call
//             PendingIntent construction, ForkRejectCallAction.
// Re-check on SDK bump: whether Twilio exposes a public reject-by-payload or
// persists CallInviteProxy across process death.
//
// CallInvite is Parcelable, but after process death its native CallInviteProxy
// is gone; CallInvite.reject() then only reports a local CallCancelledException
// and does not signal Twilio. Replaying the original FCM payload through
// Voice.handleMessage recreates the proxy in the new process, allowing the
// reject to be sent properly.
package com.twiliovoicereactnative;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;
import com.twilio.voice.Voice;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ForkInvitePayloadStore {
  private static final SDKLog logger = new SDKLog(ForkInvitePayloadStore.class);

  private static final String EXTRA_INVITE_PAYLOAD =
    "com.twiliovoicereactnative.fork.INVITE_PAYLOAD";

  private static final Map<String, HashMap<String, String>> payloadByCallSid =
    new ConcurrentHashMap<>();

  private ForkInvitePayloadStore() {}

  public static void remember(@NonNull CallInvite callInvite,
                              @Nullable Map<String, String> payload) {
    String callSid = callInvite.getCallSid();
    if (callSid == null || callSid.isEmpty() || payload == null || payload.isEmpty()) return;
    logger.log("remember invite payload for callSid=" + callSid);
    payloadByCallSid.put(callSid, new HashMap<>(payload));
  }

  public static void clear(@Nullable String callSid) {
    if (callSid == null || callSid.isEmpty()) return;
    payloadByCallSid.remove(callSid);
  }

  public static void attachToIntent(@NonNull Intent intent, @Nullable String callSid) {
    if (callSid == null || callSid.isEmpty()) return;
    HashMap<String, String> payload = payloadByCallSid.get(callSid);
    if (payload == null || payload.isEmpty()) return;
    logger.log("attach invite payload for callSid=" + callSid);
    intent.putExtra(EXTRA_INVITE_PAYLOAD, new HashMap<>(payload));
  }

  public static boolean rejectFromIntent(@NonNull Context context, @Nullable Intent intent) {
    return rejectFromIntent(context, intent, null, () -> {});
  }

  public static boolean rejectFromIntent(@NonNull Context context,
                                         @Nullable Intent intent,
                                         @Nullable String actionKey,
                                         @NonNull Runnable onComplete) {
    HashMap<String, String> payload = readPayload(intent);
    if (payload == null) {
      logger.warning("no invite payload on Decline intent");
      return false;
    }

    logger.log("replaying invite payload for Decline");
    boolean handled = Voice.handleMessage(
      context.getApplicationContext(),
      payload,
      new RejectingMessageListener(context.getApplicationContext(), actionKey, onComplete),
      new CallMessageListenerProxy());
    logger.log("Decline payload replay handled=" + handled);
    if (!handled) {
      logger.warning("reject invite payload was not a valid Twilio message");
      onComplete.run();
    }
    return handled;
  }

  @Nullable
  private static HashMap<String, String> readPayload(@Nullable Intent intent) {
    if (intent == null) return null;
    Serializable value = intent.getSerializableExtra(EXTRA_INVITE_PAYLOAD);
    if (!(value instanceof Map<?, ?>)) return null;

    HashMap<String, String> payload = new HashMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet()) {
      if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
        payload.put((String)entry.getKey(), (String)entry.getValue());
      }
    }
    return payload.isEmpty() ? null : payload;
  }

  private static final class RejectingMessageListener implements MessageListener {
    private final Context context;
    @Nullable private final String actionKey;
    private final Runnable onComplete;

    RejectingMessageListener(@NonNull Context context,
                             @Nullable String actionKey,
                             @NonNull Runnable onComplete) {
      this.context = context;
      this.actionKey = actionKey;
      this.onComplete = onComplete;
    }

    @Override
    public void onCallInvite(@NonNull CallInvite callInvite) {
      logger.log("rejecting reconstructed CallInvite " + callInvite.getCallSid());
      callInvite.reject(context);
      clear(callInvite.getCallSid());
      ForkNotificationIdentity.cancelForCallSid(context, callInvite.getCallSid());
      ForkRejectCallAction.finishDeclineKey(actionKey == null ? callInvite.getCallSid() : actionKey);
      onComplete.run();
    }

    @Override
    public void onCancelledCallInvite(@NonNull CancelledCallInvite cancelledCallInvite,
                                      @Nullable CallException callException) {
      logger.log("reconstructed invite already cancelled " + cancelledCallInvite.getCallSid());
      ForkNotificationIdentity.cancelForCallSid(context, cancelledCallInvite.getCallSid());
      clear(cancelledCallInvite.getCallSid());
      ForkRejectCallAction.finishDeclineKey(
        actionKey == null ? cancelledCallInvite.getCallSid() : actionKey);
      onComplete.run();
    }
  }
}
