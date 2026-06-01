// FORK — KAR-492
// Owns: stable incoming-call notification ids and PendingIntent identity.
// Hooks into: VoiceService.incomingCall / ACTION_REJECT_CALL,
//             VoiceFirebaseMessagingService.MessageHandler.onCancelledCallInvite,
//             NotificationUtility PendingIntent construction,
//             ForkFullScreenIncomingCall.pendingIntent.
// Re-check on SDK bump: whether upstream still uses random incoming-call
// notification ids and a constant PendingIntent requestCode.
//
// Android PendingIntent matching ignores Intent extras. Upstream used
// requestCode=0 for every call action, so back-to-back notifications with the
// same action could update each other's UUID extras via FLAG_UPDATE_CURRENT.
// Also, incoming notification ids were random and held only in the in-memory
// CallRecord. If the process dies after showing a call notification, decline /
// cancelled-invite paths can restart with no CallRecord and otherwise cannot
// cancel the stale notification. Derive ids from callSid and copy them into the
// intents so cleanup can still work after process death. The raw invite payload
// is attached only to Decline by NotificationUtility; launch/tap/accept intents
// must not carry a Twilio FCM payload because host app startup may replay it.
package com.twiliovoicereactnative;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class ForkNotificationIdentity {
  private static final SDKLog logger = new SDKLog(ForkNotificationIdentity.class);

  private static final String EXTRA_CALL_SID =
    "com.twiliovoicereactnative.fork.CALL_SID";
  private static final String EXTRA_NOTIFICATION_ID =
    "com.twiliovoicereactnative.fork.NOTIFICATION_ID";

  private ForkNotificationIdentity() {}

  public static int pendingIntentRequestCode(@NonNull Intent intent) {
    return stablePositiveId(
      "pending-intent",
      intent.getAction(),
      intent.getSerializableExtra(Constants.MSG_KEY_UUID),
      intent.getStringExtra(EXTRA_CALL_SID));
  }

  public static int incomingNotificationId(@NonNull CallRecordDatabase.CallRecord callRecord) {
    String callSid = callRecord.getCallSid();
    if (callSid != null && !callSid.isEmpty()) {
      return incomingNotificationId(callSid);
    }
    return stablePositiveId("incoming-call", callRecord.getUuid());
  }

  public static void attachIncomingCall(@NonNull Intent intent,
                                        @NonNull CallRecordDatabase.CallRecord callRecord) {
    String callSid = callRecord.getCallSid();
    if (callSid != null && !callSid.isEmpty()) {
      intent.putExtra(EXTRA_CALL_SID, callSid);
    }
    intent.putExtra(EXTRA_NOTIFICATION_ID, incomingNotificationId(callRecord));
  }

  @Nullable
  public static String callSidFromIntent(@Nullable Intent intent) {
    if (intent == null) return null;
    return intent.getStringExtra(EXTRA_CALL_SID);
  }

  public static int notificationIdFromIntent(@Nullable Intent intent) {
    if (intent == null) return 0;
    return intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
  }

  public static boolean cancelFromIntent(@NonNull Context context, @Nullable Intent intent) {
    int notificationId = notificationIdFromIntent(intent);
    if (notificationId > 0) {
      logger.log("cancel notification from intent id=" + notificationId);
      cancel(context, notificationId);
      return true;
    }
    return cancelForCallSid(context, callSidFromIntent(intent));
  }

  public static boolean cancelForCallSid(@Nullable Context context, @Nullable String callSid) {
    if (context == null || callSid == null || callSid.isEmpty()) return false;
    logger.log("cancel notification for callSid=" + callSid);
    cancel(context, incomingNotificationId(callSid));
    return true;
  }

  private static int incomingNotificationId(@NonNull String callSid) {
    return stablePositiveId("incoming-call", callSid);
  }

  private static void cancel(@NonNull Context context, int notificationId) {
    NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
    if (notificationManager == null) {
      logger.warning("NotificationManager unavailable for notificationId=" + notificationId);
      return;
    }
    notificationManager.cancel(notificationId);
  }

  private static int stablePositiveId(Object... values) {
    int id = Objects.hash(values) & 0x7FFFFFFF;
    return id == 0 ? 1 : id;
  }
}
