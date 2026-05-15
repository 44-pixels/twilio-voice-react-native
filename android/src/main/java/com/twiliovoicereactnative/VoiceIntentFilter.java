// FORK — KAR-292 (upstream twilio/twilio-voice-react-native#587)
// Owns: filtering and forwarding of intents from VoiceActivityProxy to VoiceService.
// Hooks into: VoiceActivityProxy.handleIntent.
// Re-check on SDK bump: Constants.java for new ACTION_* values to add to the
// allow-list, and whether upstream merged a fix in VoiceActivityProxy.
//
// Activity onCreate can be reached via launcher (ACTION_MAIN) or deep link
// (ACTION_VIEW) while the process is backgrounded. Forwarding those to
// VoiceService.startService throws IllegalStateException /
// ForegroundServiceStartNotAllowedException on Android 8+, and SecurityException
// on some Samsung builds. Restrict forwarding to known voice actions and swallow
// any residual RuntimeException.
package com.twiliovoicereactnative;

import android.content.Context;
import android.content.Intent;

public final class VoiceIntentFilter {
  private static final SDKLog logger = new SDKLog(VoiceIntentFilter.class);

  private VoiceIntentFilter() {}

  public static void filterAndForward(Context appContext, Intent intent) {
    String action = intent.getAction();
    if (action == null) return;
    if (action.equals(Constants.ACTION_PUSH_APP_TO_FOREGROUND)) return;
    if (!isVoiceAction(action)) {
      logger.debug("filterAndForward: ignoring non-voice action=" + action);
      return;
    }
    Intent copied = new Intent(intent);
    copied.setClass(appContext, VoiceService.class);
    copied.setFlags(0);
    try {
      appContext.startService(copied);
    } catch (RuntimeException e) {
      logger.warning(e, "startService failed for action=" + action);
    }
  }

  private static boolean isVoiceAction(String action) {
    return Constants.ACTION_ACCEPT_CALL.equals(action)
      || Constants.ACTION_REJECT_CALL.equals(action)
      || Constants.ACTION_CANCEL_ACTIVE_CALL_NOTIFICATION.equals(action)
      || Constants.ACTION_INCOMING_CALL.equals(action)
      || Constants.ACTION_CANCEL_CALL.equals(action)
      || Constants.ACTION_CALL_DISCONNECT.equals(action)
      || Constants.ACTION_RAISE_OUTGOING_CALL_NOTIFICATION.equals(action)
      || Constants.ACTION_FOREGROUND_AND_DEPRIORITIZE_INCOMING_CALL_NOTIFICATION.equals(action)
      || ForkFullScreenIncomingCall.ACTION.equals(action);
  }
}
