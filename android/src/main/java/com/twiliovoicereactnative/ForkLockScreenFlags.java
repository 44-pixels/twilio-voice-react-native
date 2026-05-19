// FORK — KAR-448
// Owns: lifecycle-gated application of FLAG_SHOW_WHEN_LOCKED / FLAG_TURN_SCREEN_ON
// / FLAG_KEEP_SCREEN_ON on MainActivity's window so the activity only bypasses
// the keyguard while a call is actually being handled. Upstream stamps these
// flags unconditionally in VoiceActivityProxy.onCreate, which causes the app to
// show over the keyguard on every foreground → lock → unlock cycle.
// Hooks into: VoiceActivityProxy.onCreate / onNewIntent (apply when the activity
//             is launched by a voice-action intent — covers cold start over the
//             keyguard via ForkFullScreenIncomingCall),
//             VoiceService.acceptCall (apply when an incoming call becomes
//             active in a foreground app — the activity is never re-entered in
//             that flow so the intent-gated path doesn't fire),
//             VoiceService.disconnect / rejectCall / cancelCall (clear so the
//             keyguard works normally after the call ends),
//             ExpoActivityLifecycleListener (register / unregister the
//             WeakReference the service-side hooks resolve through).
// Re-check on SDK bump: confirm VoiceActivityProxy still calls addFlags
// unconditionally (drop this fork if upstream gates them itself);
// confirm VoiceIntentFilter.isVoiceAction is reachable (we widened it to
// package-private); confirm VoiceService method names acceptCall / disconnect
// / rejectCall / cancelCall; confirm ExpoActivityLifecycleListener still exposes
// onCreate / onDestroy hooks. minSdk is 24, so Activity.setShowWhenLocked /
// setTurnScreenOn (API 27+) cannot replace the addFlags / clearFlags path.

package com.twiliovoicereactnative;

import android.app.Activity;
import android.content.Intent;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

public final class ForkLockScreenFlags {
  private static final SDKLog logger = new SDKLog(ForkLockScreenFlags.class);

  private static final int CALL_FLAGS =
    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

  // Only the lock-screen-bypass flags are cleared on call end. FLAG_KEEP_SCREEN_ON
  // is left to lapse naturally via the screen timeout: aligns with upstream's
  // intent that the screen stays on through the call, and avoids fighting any
  // other component that might be holding the same flag.
  private static final int LOCK_BYPASS_FLAGS =
    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

  private static WeakReference<Activity> activityRef = new WeakReference<>(null);

  private ForkLockScreenFlags() {}

  /** Registered from ExpoActivityLifecycleListener.onCreate so service-side
   *  hooks can resolve the MainActivity window without an explicit dependency
   *  on the host app. */
  public static void registerActivity(@NonNull Activity activity) {
    activityRef = new WeakReference<>(activity);
  }

  /** Registered from ExpoActivityLifecycleListener.onDestroy. Clears the ref
   *  iff it still points at the activity being destroyed (defensive against
   *  out-of-order callbacks during activity recreation). */
  public static void unregisterActivity(@NonNull Activity activity) {
    Activity current = activityRef.get();
    if (current == null || current == activity) {
      activityRef = new WeakReference<>(null);
    }
  }

  /** Called from VoiceActivityProxy when the activity is entered with an
   *  intent. Applies the call flags iff the intent is a voice action, so the
   *  activity bypasses the keyguard only when it was launched specifically to
   *  handle a call (typically ForkFullScreenIncomingCall.ACTION on a locked
   *  device). For any other launch — ACTION_MAIN, deep links, etc. — flags
   *  are cleared so the keyguard behaves normally on subsequent lock/unlock. */
  public static void applyIfVoiceAction(@NonNull Activity activity, @Nullable Intent intent) {
    String action = (intent != null) ? intent.getAction() : null;
    if (action != null && VoiceIntentFilter.isVoiceAction(action)) {
      logger.debug("applyIfVoiceAction: applying for action=" + action);
      addFlags(activity);
    } else {
      logger.debug("applyIfVoiceAction: clearing for action=" + action);
      clearFlags(activity);
    }
  }

  /** Called from VoiceService.acceptCall when an incoming call becomes active.
   *  Covers the foreground-app accept path: the activity is never re-entered
   *  with the accept intent (it goes straight to the service), so the
   *  intent-gated path in VoiceActivityProxy doesn't fire. */
  public static void applyForActiveCall() {
    Activity activity = activityRef.get();
    if (activity == null) {
      logger.debug("applyForActiveCall: no activity registered");
      return;
    }
    addFlags(activity);
  }

  /** Called from VoiceService on call-terminal transitions (disconnect, reject,
   *  cancel). Clears the lock-screen-bypass flags so the keyguard resumes
   *  normal behaviour. Idempotent. */
  public static void clearForEndedCall() {
    Activity activity = activityRef.get();
    if (activity == null) {
      logger.debug("clearForEndedCall: no activity registered");
      return;
    }
    clearFlags(activity);
  }

  private static void addFlags(@NonNull Activity activity) {
    Window window = activity.getWindow();
    if (window == null) return;
    window.addFlags(CALL_FLAGS);
  }

  private static void clearFlags(@NonNull Activity activity) {
    Window window = activity.getWindow();
    if (window == null) return;
    window.clearFlags(LOCK_BYPASS_FLAGS);
  }
}
