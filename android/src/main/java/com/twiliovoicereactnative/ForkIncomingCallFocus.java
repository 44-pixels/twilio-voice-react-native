// FORK — KAR-591
// Owns: incoming-call notification-tap behavior. Keeps the CallStyle notification as a
// peeking heads-up (Accept/Decline) with the ringer playing, instead of deprioritizing it.
// Hooks into: ExpoActivityLifecycleListener (arm on tap + re-post on window-focus-gained).
//             VoiceService just skips the upstream deprioritize + ringer stop.
// Re-check on SDK bump: KAR-443 onLaunched still promotes via startForeground;
// accept/reject/cancel still clear the notification + stop the ringer.
//
// Peek recipe (all required, learned on OnePlus/OxygenOS): CallStyle needs FSI or FGS;
// FGS notifications don't peek; a heads-up posted before the tapped app has settled +
// unlocked won't pin AND its FSI re-fires (double MainActivity launch). onResume is too
// early on OxygenOS cold starts; window-focus-gained fires once the activity is drawn, on
// top and unlocked — the right moment. So arm on the tap and re-post on window focus: drop
// FGS (REMOVE — DETACH crashes), re-post CallStyle+FSI (FSI stays dormant once unlocked).
package com.twiliovoicereactnative;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import java.util.UUID;

final class ForkIncomingCallFocus {
  private static final SDKLog logger = new SDKLog(ForkIncomingCallFocus.class);
  private static volatile UUID pendingPeekUuid;

  private ForkIncomingCallFocus() {}

  /** Arm a peek re-post on the notification tap (activity layer, before the window gains
   *  focus, so the focus callback can consume it). */
  static void armFromIntent(@NonNull Intent intent) {
    if (Constants.ACTION_FOREGROUND_AND_DEPRIORITIZE_INCOMING_CALL_NOTIFICATION.equals(intent.getAction())) {
      pendingPeekUuid = ForkCallRecordLookup.readUuid(intent);
      logger.debug("armFromIntent: armed " + pendingPeekUuid);
    }
  }

  /** From the activity's window-focus-gained callback — the activity is now drawn, on top
   *  and unlocked (the only state where the heads-up pins and the FSI stays dormant). */
  static void onWindowFocusGained(@NonNull Context context) {
    UUID uuid = pendingPeekUuid;
    logger.debug("onWindowFocusGained: armed=" + (uuid != null));
    if (uuid == null) {
      return;
    }
    pendingPeekUuid = null;
    repostPeek(context.getApplicationContext(), uuid);
  }

  private static void repostPeek(@NonNull Context appCtx, @NonNull UUID uuid) {
    CallRecordDatabase.CallRecord record = ForkCallRecordLookup.getOrNull(uuid);
    if (record == null
        || record.getCallInvite() == null
        || record.getCallInviteState() != CallRecordDatabase.CallRecord.CallInviteState.ACTIVE) {
      return; // answered / declined / cancelled
    }
    VoiceService.removeForegroundNotificationIfRunning(); // drop FGS so it can peek
    Notification notification = NotificationUtility.createIncomingCallNotification(
      appCtx, record, Constants.VOICE_CHANNEL_HIGH_IMPORTANCE);
    appCtx.getSystemService(NotificationManager.class).notify(record.getNotificationId(), notification);
    logger.debug("repostPeek: re-posted for " + uuid);
  }
}
