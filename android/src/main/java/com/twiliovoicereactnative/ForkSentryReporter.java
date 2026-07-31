// FORK — KAR-878
// Owns: native Twilio Voice operational reporting to the host application's Sentry SDK.
// Hooks into: Android call, registration, messaging, preflight, and platform failure paths.
// Re-check on SDK bump: Sentry Android capture APIs and Twilio callback error semantics.
package com.twiliovoicereactnative;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;

final class ForkSentryReporter {
  private static final String CATEGORY = "twilio.voice";
  private static final String OPERATION_TAG = "twilio_voice.operation";

  private ForkSentryReporter() {}

  static void reportError(@NonNull String operation, @Nullable Throwable cause) {
    if (cause == null) {
      Sentry.captureMessage(operation, SentryLevel.ERROR, scope ->
        scope.setTag(OPERATION_TAG, operation));
      return;
    }

    Sentry.captureException(cause, scope -> scope.setTag(OPERATION_TAG, operation));
  }

  static void reportWarning(@NonNull String operation, @Nullable Throwable cause) {
    if (cause == null) {
      Sentry.captureMessage(operation, SentryLevel.WARNING, scope ->
        scope.setTag(OPERATION_TAG, operation));
      return;
    }

    final SentryEvent event = new SentryEvent(cause);
    event.setLevel(SentryLevel.WARNING);
    Sentry.captureEvent(event, scope -> scope.setTag(OPERATION_TAG, operation));
  }

  static void addBreadcrumb(@NonNull String operation) {
    final Breadcrumb breadcrumb = new Breadcrumb(operation);
    breadcrumb.setCategory(CATEGORY);
    breadcrumb.setLevel(SentryLevel.INFO);
    Sentry.addBreadcrumb(breadcrumb);
  }
}
