// FORK — KAR-878
// Owns: Twilio Voice native logging mirrored to the host application's Sentry SDK.
// Hooks into: SDKLog and native Android log bypasses.
// Re-check on SDK bump: Sentry Android breadcrumb/event APIs and SDKLog behavior.
package com.twiliovoicereactnative;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import io.sentry.Breadcrumb;
import io.sentry.IScope;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;

final class ForkLogger {
  private static final String CATEGORY = "twilio.voice.log";
  private static final String LOGGER_TAG = "twilio_voice.logger";
  private static final String REDACTED = "[REDACTED]";
  private static final Pattern JWT = Pattern.compile(
    "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
  private static final Pattern DEVICE_TOKEN = Pattern.compile(
    "(?i)((?:fcm|apns|device)(?: with)? token[=: ]{1,3})[a-z0-9:_-]{20,}");
  private static final Pattern NAMED_SECRET = Pattern.compile(
    "(?i)((?:access[_ -]?token|authorization|api[_ -]?key|secret|password)[=: ]{1,3})\\S+");

  private final String logTag;

  ForkLogger(@NonNull Class<?> source) {
    logTag = source.getSimpleName();
  }

  void debug(@NonNull String message) {
    if (!BuildConfig.DEBUG) return;

    final String safeMessage = redact(message);
    Log.d(logTag, safeMessage);
    addBreadcrumb(safeMessage, SentryLevel.DEBUG);
  }

  void info(@NonNull String message) {
    final String safeMessage = redact(message);
    Log.i(logTag, safeMessage);
    addBreadcrumb(safeMessage, SentryLevel.INFO);
  }

  void warning(@NonNull String message) {
    warning(null, message);
  }

  void warning(@Nullable Throwable cause, @NonNull String message) {
    final String safeMessage = redact(message);
    if (cause == null) {
      Log.w(logTag, safeMessage);
    } else {
      Log.w(logTag, safeMessage, cause);
    }
    capture(safeMessage, cause, SentryLevel.WARNING);
  }

  void error(@NonNull String message) {
    error(null, message);
  }

  void error(@Nullable Throwable cause, @NonNull String message) {
    final String safeMessage = redact(message);
    if (cause == null) {
      Log.e(logTag, safeMessage);
    } else {
      Log.e(logTag, safeMessage, cause);
    }
    capture(safeMessage, cause, SentryLevel.ERROR);
  }

  private void capture(@NonNull String message,
                       @Nullable Throwable cause,
                       @NonNull SentryLevel level) {
    addBreadcrumb(message, level);
    if (cause == null) {
      Sentry.captureMessage(message, level, scope -> enrichScope(scope, message, level));
      return;
    }

    final SentryEvent event = new SentryEvent(cause);
    event.setLevel(level);
    Sentry.captureEvent(event, scope -> enrichScope(scope, message, level));
  }

  private void addBreadcrumb(@NonNull String message, @NonNull SentryLevel level) {
    final Breadcrumb breadcrumb = new Breadcrumb(message);
    breadcrumb.setCategory(CATEGORY);
    breadcrumb.setLevel(level);
    breadcrumb.setData("logger", logTag);
    Sentry.addBreadcrumb(breadcrumb);
  }

  private void enrichScope(@NonNull IScope scope,
                           @NonNull String message,
                           @NonNull SentryLevel level) {
    scope.setTag(LOGGER_TAG, logTag);
    final Map<String, Object> context = new HashMap<>();
    context.put("logger", logTag);
    context.put("level", level.name().toLowerCase());
    context.put("message", message);
    scope.setContexts("twilio_voice_log", context);
  }

  @NonNull
  private static String redact(@NonNull String message) {
    final String withoutJwt = JWT.matcher(message).replaceAll(REDACTED);
    final String withoutDeviceToken = DEVICE_TOKEN.matcher(withoutJwt).replaceAll("$1" + REDACTED);
    return NAMED_SECRET.matcher(withoutDeviceToken).replaceAll("$1" + REDACTED);
  }
}
