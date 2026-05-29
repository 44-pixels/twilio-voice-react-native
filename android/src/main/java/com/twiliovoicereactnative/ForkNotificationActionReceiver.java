// FORK — KAR-492
// Owns: explicit notification action delivery after the app task is killed.
// Hooks into: AndroidManifest.xml receiver declaration and
//             NotificationUtility.createIncomingCallNotification Decline action.
// Re-check on SDK bump: whether upstream still uses a Service PendingIntent for
// incoming-call Decline.
//
// getService PendingIntents can fail to start VoiceService once the app is no
// longer foreground. A manifest BroadcastReceiver is the lightweight component
// Android is expected to spin up for notification actions, so Decline remains
// deliverable after the task is swiped away.
package com.twiliovoicereactnative;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ForkNotificationActionReceiver extends BroadcastReceiver {
  private static final SDKLog logger = new SDKLog(ForkNotificationActionReceiver.class);

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) {
      logger.warning("notification action missing intent");
      return;
    }
    logger.log("notification action received action=" + intent.getAction());
    if (Constants.ACTION_REJECT_CALL.equals(intent.getAction())) {
      PendingResult pendingResult = goAsync();
      AtomicBoolean finished = new AtomicBoolean(false);
      Runnable finish = () -> {
        if (!finished.compareAndSet(false, true)) return;
        try {
          pendingResult.finish();
        } catch (IllegalStateException e) {
          logger.warning(e, "notification action pending result already finished");
        }
      };
      ForkRejectCallAction.rejectFromIntent(context, intent, finish);
      new Handler(Looper.getMainLooper()).postDelayed(finish, 5000);
      return;
    }
    logger.warning("unsupported notification action=" + intent.getAction());
  }

  static PendingIntent rejectPendingIntent(@NonNull Context context, @NonNull Intent intent) {
    return PendingIntent.getBroadcast(
      context.getApplicationContext(),
      ForkNotificationIdentity.pendingIntentRequestCode(intent),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
}
