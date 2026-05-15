// FORK — KAR-443
// Owns: separate PendingIntent + action for the full-screen incoming-call slot
// so the OS auto-launch path does NOT silence the ringer, and re-posts the
// notification on the high-importance channel so the CallStyle heads-up
// surfaces on top of the activity after the system consumed the original
// heads-up to launch the activity.
// Hooks into: NotificationUtility.createIncomingCallNotification (full-screen PI),
//             VoiceService.onStartCommand (action dispatch),
//             VoiceIntentFilter.isVoiceAction (allow-list).
// Re-check on SDK bump: confirm NotificationCompat.Builder#setFullScreenIntent
// signature; confirm ACTION_FOREGROUND_AND_DEPRIORITIZE_INCOMING_CALL_NOTIFICATION
// semantics still apply only to the content-intent (tap) path; confirm
// VoiceApplicationProxy.getMainActivityClass() / VoiceService.constructMessage
// / NotificationUtility.createIncomingCallNotification signatures are unchanged.
//
// Why: upstream wires one PendingIntent to both setContentIntent and
// setFullScreenIntent carrying ACTION_FOREGROUND_AND_DEPRIORITIZE_...; its
// handler both stops the ringer AND re-posts the notification at default
// importance. The OS fires full-screen intents automatically when the screen
// is locked or, on Android 14+, when the app is backgrounded — silencing the
// ringer ~0.5 s after it started. Splitting the full-screen slot onto its
// own action routes that launch here: we still re-post the notification
// (so the user can reach Accept/Reject from the shade once the activity is
// over the lock screen) but we do NOT stop the ringer and do NOT emit the
// notificationTapped JS event, because the user did not tap anything.
// Tap behavior on setContentIntent is unchanged upstream.
package com.twiliovoicereactnative;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class ForkFullScreenIncomingCall {
  private static final SDKLog logger = new SDKLog(ForkFullScreenIncomingCall.class);

  public static final String ACTION =
    "com.twiliovoicereactnative.ACTION_INCOMING_CALL_FULL_SCREEN_LAUNCH";

  private ForkFullScreenIncomingCall() {}

  /** PendingIntent for {@code NotificationCompat.Builder#setFullScreenIntent}. */
  static PendingIntent pendingIntent(@NonNull Context context,
                                     @NonNull CallRecordDatabase.CallRecord callRecord) {
    Intent intent = VoiceService.constructMessage(
      context,
      ACTION,
      Objects.requireNonNull(VoiceApplicationProxy.getMainActivityClass()),
      callRecord.getUuid());
    return NotificationUtility.constructPendingIntentForActivity(context, intent);
  }

  /** Service-side handler for the system-fired full-screen launch.
   *  Cancels the existing call notification (which Android treats as already
   *  "delivered" once the full-screen intent fires, so a same-id re-notify
   *  is silenced) and re-posts it on the high-importance channel so the
   *  CallStyle heads-up surfaces on top of the freshly launched activity.
   *  The CallStyle Accept/Reject buttons are the only in-app UI on Android,
   *  so they must be visible without the user pulling down the shade.
   *  Leaves the ringer alone — it must keep playing until accept / reject /
   *  cancel / timeout.
   *
   *  The re-post keeps setFullScreenIntent because
   *  NotificationCompat.CallStyle.forIncomingCall requires it; without it the
   *  system ranks the call notification lower and on a locked screen it will
   *  not surface the call UI / launch the activity over the keyguard. The FSI
   *  on the re-post does not re-fire: the OS only auto-fires a full-screen
   *  intent when the app is not in foreground, and by the time this method
   *  runs the activity is foreground (keyguard cleared via
   *  FLAG_SHOW_WHEN_LOCKED in VoiceActivityProxy). */
  static void onLaunched(@NonNull Context context,
                         @Nullable CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord) {
      logger.warning("onLaunched: no call record");
      return;
    }
    logger.debug("onLaunched: " + callRecord.getUuid());
    NotificationManager nm = context.getSystemService(NotificationManager.class);
    nm.cancel(callRecord.getNotificationId());
    Notification notification = NotificationUtility.createIncomingCallNotification(
      context,
      callRecord,
      Constants.VOICE_CHANNEL_HIGH_IMPORTANCE);
    nm.notify(callRecord.getNotificationId(), notification);
  }
}
