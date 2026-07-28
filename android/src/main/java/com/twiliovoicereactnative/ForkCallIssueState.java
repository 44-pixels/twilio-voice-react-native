// FORK — KAR-857
// Owns: Per-call connection-issue state and connected/issue sound transitions.
// Hooks into: CallListenerProxy and ForkCallSounds.
// Re-check on SDK bump: Call quality warning names and reconnect callback ordering.
package com.twiliovoicereactnative;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.twilio.voice.Call;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ForkCallIssueState {
  private static final long SOUND_THROTTLE_MILLIS = 2_000;
  private static final long ENDED_CALL_MARKER_LIFETIME_MILLIS = 30_000;
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());
  private static final Map<UUID, State> states = new HashMap<>();
  private static final Set<UUID> recentlyEndedCalls = ConcurrentHashMap.newKeySet();

  private enum IssueState {
    NO_ISSUES,
    HAS_ISSUES
  }

  private static final class State {
    private boolean reconnecting;
    private boolean poorNetworkQuality;
    private long nextSoundAllowedAtMillis;
    private IssueState issueState = IssueState.NO_ISSUES;
  }

  private ForkCallIssueState() {}

  static void connected(UUID uuid) {
    runOnMainThread(() -> {
      if (recentlyEndedCalls.contains(uuid)) return;

      State state = new State();
      states.put(uuid, state);
      playIfAllowed(state, ForkCallSounds::playConnectedSound);
    });
  }

  static void reconnecting(UUID uuid) {
    runOnMainThread(() -> {
      State state = states.get(uuid);
      if (state == null) return;

      state.reconnecting = true;
      update(state);
    });
  }

  static void reconnected(UUID uuid) {
    runOnMainThread(() -> {
      State state = states.get(uuid);
      if (state == null) return;

      state.reconnecting = false;
      update(state);
    });
  }

  static void qualityWarningsChanged(
    UUID uuid,
    Set<Call.CallQualityWarning> currentWarnings
  ) {
    boolean poorNetworkQuality = hasNetworkQualityWarning(currentWarnings);
    runOnMainThread(() -> {
      State state = states.get(uuid);
      if (state == null) return;

      state.poorNetworkQuality = poorNetworkQuality;
      update(state);
    });
  }

  static void ended(UUID uuid) {
    recentlyEndedCalls.add(uuid);
    runOnMainThread(() -> {
      states.remove(uuid);
      ForkCallSounds.stopConnectionStatusSound();
      mainHandler.postDelayed(
        () -> recentlyEndedCalls.remove(uuid),
        ENDED_CALL_MARKER_LIFETIME_MILLIS
      );
    });
  }

  private static void runOnMainThread(Runnable operation) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      operation.run();
    } else {
      mainHandler.post(operation);
    }
  }

  private static boolean hasNetworkQualityWarning(
    Set<Call.CallQualityWarning> warnings
  ) {
    return warnings.contains(Call.CallQualityWarning.WARN_HIGH_RTT)
      || warnings.contains(Call.CallQualityWarning.WARN_HIGH_JITTER)
      || warnings.contains(Call.CallQualityWarning.WARN_HIGH_PACKET_LOSS)
      || warnings.contains(Call.CallQualityWarning.WARN_LOW_MOS);
  }

  private static void update(State state) {
    IssueState nextIssueState = state.reconnecting || state.poorNetworkQuality
      ? IssueState.HAS_ISSUES
      : IssueState.NO_ISSUES;
    if (state.issueState == nextIssueState) return;

    state.issueState = nextIssueState;
    if (nextIssueState == IssueState.HAS_ISSUES) {
      playIfAllowed(state, ForkCallSounds::playHasIssuesSound);
    } else {
      playIfAllowed(state, ForkCallSounds::playConnectedSound);
    }
  }

  private static void playIfAllowed(State state, Runnable playSound) {
    long now = SystemClock.elapsedRealtime();
    if (now < state.nextSoundAllowedAtMillis) return;

    state.nextSoundAllowedAtMillis = now + SOUND_THROTTLE_MILLIS;
    playSound.run();
  }
}
