// FORK — KAR-443
// Owns: separate full-screen PendingIntent for incoming-call notifications so
// Android may show a tiny fork-only call UI instead of opening the host app's
// MainActivity when a call arrives on the lock screen.
// Hooks into: NotificationUtility.createIncomingCallNotification.
// Re-check on SDK bump: confirm NotificationCompat.Builder#setFullScreenIntent
// is still used for incoming calls and VoiceService.constructMessage signatures
// are unchanged.
//
// Why: upstream wires one PendingIntent to both setContentIntent and
// setFullScreenIntent carrying ACTION_FOREGROUND_AND_DEPRIORITIZE_...; its
// handler both stops the ringer and re-posts the notification at lower
// importance. The OS fires full-screen intents automatically when the screen is
// locked/backgrounded, so the fork uses a dedicated Activity PendingIntent for
// FSI while keeping content/Accept/Decline actions on their normal paths.
package com.twiliovoicereactnative;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

public final class ForkFullScreenIncomingCall {
  public static final String ACTION =
    "com.twiliovoicereactnative.ACTION_INCOMING_CALL_FULL_SCREEN_LAUNCH";

  private ForkFullScreenIncomingCall() {}

  /** PendingIntent for {@code NotificationCompat.Builder#setFullScreenIntent}. */
  static PendingIntent pendingIntent(@NonNull Context context,
                                     @NonNull CallRecordDatabase.CallRecord callRecord) {
    Intent intent = VoiceService.constructMessage(
      context,
      ACTION,
      ForkIncomingCallActivity.class,
      callRecord.getUuid());
    ForkNotificationIdentity.attachIncomingCall(intent, callRecord);
    return PendingIntent.getActivity(
      context.getApplicationContext(),
      ForkNotificationIdentity.pendingIntentRequestCode(intent),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
}
