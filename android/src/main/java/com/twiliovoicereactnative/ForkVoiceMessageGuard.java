// FORK — KAR-492
// Owns: persistent idempotency for Twilio Voice FCM payload handling across
// process death. Hooks into: VoiceFirebaseMessagingService.onMessageReceived,
// VoiceModuleProxy.handleEvent, and ForkInvitePayloadStore lifecycle markers.
// Re-check on SDK bump: Twilio payload key names, message-id semantics, and
// Voice.handleMessage return behavior for call/cancel payloads.
//
// RNFirebase may retry the same FCM invite after the task/process is removed.
// Replaying an already-settled invite asks Twilio about a dead call and causes
// a stale onCancelledCallInvite. Store only non-sensitive Twilio IDs with a
// short TTL; never persist raw FCM payloads here.
package com.twiliovoicereactnative;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.voice.Voice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ForkVoiceMessageGuard {
  private static final SDKLog logger = new SDKLog(ForkVoiceMessageGuard.class);

  private static final String PREFS_NAME =
    "com.twiliovoicereactnative.fork.VOICE_MESSAGE_GUARD";
  private static final String MESSAGE_PREFIX = "message:";
  private static final String SETTLED_PREFIX = "settled:";

  private static final String KEY_MESSAGE_TYPE = "twi_message_type";
  private static final String KEY_MESSAGE_ID = "twi_message_id";
  private static final String KEY_CALL_SID = "twi_call_sid";
  private static final String TYPE_CALL = "twilio.voice.call";
  private static final String TYPE_CANCEL = "twilio.voice.cancel";

  private static final long TTL_MILLIS = 24L * 60L * 60L * 1000L;
  private static final int MAX_TOMBSTONES = 1024;

  public enum Source {
    NATIVE_FCM("native_fcm"),
    HANDLE_EVENT("handleEvent"),
    LIFECYCLE("lifecycle");

    private final String logName;

    Source(@NonNull String logName) {
      this.logName = logName;
    }
  }

  private ForkVoiceMessageGuard() {}

  public static boolean handleNativeFcm(@NonNull Context context,
                                        @NonNull Map<String, String> payload) {
    return handleMessage(context, payload, Source.NATIVE_FCM);
  }

  public static boolean handleEvent(@NonNull Context context,
                                    @NonNull Map<String, String> payload) {
    return handleMessage(context, payload, Source.HANDLE_EVENT);
  }

  public static synchronized boolean shouldWakeForIncomingCall(@NonNull Context context,
                                                               @NonNull Map<String, String> payload) {
    TwilioMessage message = TwilioMessage.from(payload, null);
    return TYPE_CALL.equals(message.messageType) && !decide(context, message).skip;
  }

  public static synchronized void markPresented(@Nullable Map<String, String> payload,
                                                @Nullable String fallbackCallSid) {
    TwilioMessage message = TwilioMessage.from(payload, fallbackCallSid);
    if (message.messageId == null) return;

    Context context = VoiceApplicationProxy.getApplicationContext();
    SharedPreferences prefs = prefs(context);
    long nowMillis = System.currentTimeMillis();
    prune(prefs, nowMillis);
    if (!prefs.edit().putLong(MESSAGE_PREFIX + message.messageId, nowMillis).commit()) {
      logger.warning("[FORK KAR-492] failed to mark presented messageId="
        + message.messageId);
      return;
    }
    logDecision(Source.LIFECYCLE, message, "mark_presented");
  }

  public static synchronized void markSettled(@Nullable String callSid) {
    String nonEmptyCallSid = nonEmpty(callSid);
    if (nonEmptyCallSid == null) return;

    Context context = VoiceApplicationProxy.getApplicationContext();
    SharedPreferences prefs = prefs(context);
    long nowMillis = System.currentTimeMillis();
    prune(prefs, nowMillis);
    if (!prefs.edit().putLong(SETTLED_PREFIX + nonEmptyCallSid, nowMillis).commit()) {
      logger.warning("[FORK KAR-492] failed to mark settled callSid=" + nonEmptyCallSid);
      return;
    }
    logDecision(Source.LIFECYCLE, TwilioMessage.settled(nonEmptyCallSid), "mark_settled");
  }

  private static boolean handleMessage(@NonNull Context context,
                                       @NonNull Map<String, String> payload,
                                       @NonNull Source source) {
    TwilioMessage message = TwilioMessage.from(payload, null);
    Decision decision = decide(context, message);
    logDecision(source, message, decision.logName);
    if (decision.skip) return true;

    return Voice.handleMessage(
      context.getApplicationContext(),
      payload,
      new VoiceFirebaseMessagingService.MessageHandler(payload),
      new CallMessageListenerProxy());
  }

  @NonNull
  private static Decision decide(@NonNull Context context, @NonNull TwilioMessage message) {
    if (!message.isTwilioVoiceMessage()) return Decision.PROCESS;

    SharedPreferences prefs = prefs(context);
    long nowMillis = System.currentTimeMillis();
    prune(prefs, nowMillis);

    if ((TYPE_CALL.equals(message.messageType) || TYPE_CANCEL.equals(message.messageType))
      && message.callSid != null
      && isFresh(prefs, SETTLED_PREFIX + message.callSid, nowMillis)) {
      return Decision.SKIP_SETTLED;
    }

    if (TYPE_CALL.equals(message.messageType)
      && message.messageId != null
      && isFresh(prefs, MESSAGE_PREFIX + message.messageId, nowMillis)) {
      return Decision.SKIP_DUPLICATE;
    }

    return Decision.PROCESS;
  }

  private static boolean isFresh(@NonNull SharedPreferences prefs,
                                 @NonNull String key,
                                 long nowMillis) {
    long timestampMillis = prefs.getLong(key, 0L);
    return timestampMillis > 0L && nowMillis - timestampMillis <= TTL_MILLIS;
  }

  private static void prune(@NonNull SharedPreferences prefs, long nowMillis) {
    Map<String, ?> entries = prefs.getAll();
    List<Tombstone> retained = new ArrayList<>();
    SharedPreferences.Editor editor = prefs.edit();
    boolean changed = false;

    for (Map.Entry<String, ?> entry : entries.entrySet()) {
      Object value = entry.getValue();
      if (!(value instanceof Long)) {
        editor.remove(entry.getKey());
        changed = true;
        continue;
      }

      long timestampMillis = (Long)value;
      if (timestampMillis <= 0L || nowMillis - timestampMillis > TTL_MILLIS) {
        editor.remove(entry.getKey());
        changed = true;
        continue;
      }

      retained.add(new Tombstone(entry.getKey(), timestampMillis));
    }

    if (retained.size() > MAX_TOMBSTONES) {
      retained.sort(Comparator.comparingLong(tombstone -> tombstone.timestampMillis));
      int overflow = retained.size() - MAX_TOMBSTONES;
      for (int i = 0; i < overflow; i++) {
        editor.remove(retained.get(i).key);
      }
      changed = true;
    }

    if (changed && !editor.commit()) {
      logger.warning("[FORK KAR-492] failed to prune message guard tombstones");
    }
  }

  @NonNull
  private static SharedPreferences prefs(@NonNull Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private static void logDecision(@NonNull Source source,
                                  @NonNull TwilioMessage message,
                                  @NonNull String decision) {
    if (!message.isTwilioVoiceMessage()) return;
    logger.log("[FORK KAR-492] message source=" + source.logName
      + " callSid=" + logValue(message.callSid)
      + " messageId=" + logValue(message.messageId)
      + " type=" + logValue(message.messageType)
      + " decision=" + decision);
  }

  private static String logValue(@Nullable String value) {
    return value == null ? "missing" : value;
  }

  @Nullable
  private static String nonEmpty(@Nullable String value) {
    if (value == null || value.isEmpty()) return null;
    return value;
  }

  private enum Decision {
    PROCESS(false, "process"),
    SKIP_DUPLICATE(true, "skip_duplicate"),
    SKIP_SETTLED(true, "skip_settled");

    private final boolean skip;
    private final String logName;

    Decision(boolean skip, @NonNull String logName) {
      this.skip = skip;
      this.logName = logName;
    }
  }

  private static final class TwilioMessage {
    @Nullable private final String messageType;
    @Nullable private final String messageId;
    @Nullable private final String callSid;

    private TwilioMessage(@Nullable String messageType,
                          @Nullable String messageId,
                          @Nullable String callSid) {
      this.messageType = messageType;
      this.messageId = messageId;
      this.callSid = callSid;
    }

    @NonNull
    private static TwilioMessage from(@Nullable Map<String, String> payload,
                                      @Nullable String fallbackCallSid) {
      String fallback = nonEmpty(fallbackCallSid);
      if (payload == null) return new TwilioMessage(null, null, fallback);

      String callSid = nonEmpty(payload.get(KEY_CALL_SID));
      return new TwilioMessage(
        nonEmpty(payload.get(KEY_MESSAGE_TYPE)),
        nonEmpty(payload.get(KEY_MESSAGE_ID)),
        callSid == null ? fallback : callSid);
    }

    @NonNull
    private static TwilioMessage settled(@NonNull String callSid) {
      return new TwilioMessage(null, null, callSid);
    }

    private boolean isTwilioVoiceMessage() {
      return TYPE_CALL.equals(messageType)
        || TYPE_CANCEL.equals(messageType)
        || messageId != null
        || callSid != null;
    }
  }

  private static final class Tombstone {
    private final String key;
    private final long timestampMillis;

    private Tombstone(@NonNull String key, long timestampMillis) {
      this.key = key;
      this.timestampMillis = timestampMillis;
    }
  }
}
