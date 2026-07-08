// FORK — KAR-443
// Owns: single-thread boundary for Twilio Voice Android SDK calls.
// Hooks into: ForkVoiceMessageGuard, ForkInvitePayloadStore, VoiceModuleProxy,
// CallModuleProxy, CallInviteModuleProxy, VoiceService, and notification actions.
// Re-check on SDK bump: whether Twilio Voice Android SDK calls are still
// required to be made from one Looper thread.
//
// Twilio Voice Android SDK native objects are not thread-safe. In particular,
// native Call.disconnect can segfault if a Call/CallInvite is created from one
// thread and later controlled from another. Keep every Voice/Call/CallInvite
// SDK method behind this boundary.
package com.twiliovoicereactnative;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

final class ForkTwilioVoiceThread {
  interface BooleanCall {
    boolean run();
  }

  interface ValueCall<T> {
    T run();
  }

  private static final SDKLog logger = new SDKLog(ForkTwilioVoiceThread.class);
  private static final Handler handler = new Handler(Looper.getMainLooper());

  private ForkTwilioVoiceThread() {}

  static void run(@NonNull Runnable action) {
    if (isOnThread()) {
      action.run();
      return;
    }

    handler.post(action);
  }

  static void runBlocking(@NonNull Runnable action) {
    if (isOnThread()) {
      action.run();
      return;
    }

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    handler.post(() -> {
      try {
        action.run();
      } catch (RuntimeException error) {
        failure.set(error);
      } finally {
        latch.countDown();
      }
    });

    await(latch);
    RuntimeException error = failure.get();
    if (error != null) throw error;
  }

  static boolean callBlocking(@NonNull BooleanCall action) {
    return Boolean.TRUE.equals(callValueBlocking(() -> action.run()));
  }

  static <T> T callValueBlocking(@NonNull ValueCall<T> action) {
    if (isOnThread()) return action.run();

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    handler.post(() -> {
      try {
        result.set(action.run());
      } catch (RuntimeException error) {
        failure.set(error);
      } finally {
        latch.countDown();
      }
    });

    await(latch);
    RuntimeException error = failure.get();
    if (error != null) throw error;
    return result.get();
  }

  private static boolean isOnThread() {
    return Looper.myLooper() == Looper.getMainLooper();
  }

  private static void await(@NonNull CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      logger.warning(error, "interrupted while waiting for Twilio Voice thread");
    }
  }
}
