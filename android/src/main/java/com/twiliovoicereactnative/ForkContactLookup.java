// FORK — KAR-479
// Owns: contact resolution for incoming-call notifications (display name,
// photo, tel: URI) against the OS Contacts provider. Android counterpart of
// the iOS CallKit-driven lookup in ForkE164.h / TwilioVoiceReactNative+CallKit.m
// (reportNewIncomingCall).
// Hooks into: NotificationUtility.createIncomingCallNotification — a single
// sentinel block around the incoming-call Person.Builder.
// Re-check on SDK bump:
//   - whether NotificationUtility still builds the incoming Person from a
//     single string returned by NotificationResource.getName();
//   - whether NotificationCompat.CallStyle.forIncomingCall is still used;
//   - whether CallInvite.getFrom() still returns the raw "+E164" or
//     "client:..." string we rely on for the E.164 candidate.
//
// Permission model: READ_CONTACTS is a runtime/dangerous permission and is
// declared by the host app (Karen ships expo-contacts, which injects it via
// its config plugin). This file never declares the permission, never prompts,
// and never throws on a miss — it consults ContextCompat.checkSelfPermission
// and falls back silently. FirebaseMessagingService.onMessageReceived runs on
// a worker thread, so the single ContentResolver query is safe synchronously;
// we deliberately do not cache results (notifications are infrequent).
package com.twiliovoicereactnative;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.twilio.voice.CallInvite;

import java.io.InputStream;

public final class ForkContactLookup {
  private static final SDKLog logger = new SDKLog(ForkContactLookup.class);

  private ForkContactLookup() {}

  /** Mirror of ForkIsE164PhoneNumber in ios/ForkE164.h. */
  static boolean isE164(@Nullable String value) {
    if (value == null) return false;
    final int length = value.length();
    if (length < 3 || length > 16 || value.charAt(0) != '+') return false;
    final char leading = value.charAt(1);
    if (leading < '1' || leading > '9') return false;
    for (int i = 2; i < length; i++) {
      final char c = value.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }

  public static final class Result {
    @NonNull public final String displayName;
    @Nullable public final IconCompat icon;
    @Nullable public final String telUri;

    Result(@NonNull String displayName, @Nullable IconCompat icon, @Nullable String telUri) {
      this.displayName = displayName;
      this.icon = icon;
      this.telUri = telUri;
    }
  }

  /**
   * Best-effort enrichment of an incoming-call display name with OS Contacts
   * data. Never throws; falls back to {@code fallbackName} on any miss.
   *
   * <p>Resolution order (mirrors iOS):
   * <ol>
   *   <li>Pick an E.164 candidate from {@code fallbackName} or
   *       {@code callInvite.getFrom()} (after stripping {@code "client:"}).</li>
   *   <li>No candidate, or {@code READ_CONTACTS} not granted → return the
   *       fallback name unchanged.</li>
   *   <li>{@link ContactsContract.PhoneLookup} miss → fallback name + tel URI.</li>
   *   <li>Hit → contact name only when the fallback was the raw E.164 (i.e.
   *       no user-supplied templated label to respect), plus optional photo
   *       and tel URI.</li>
   * </ol>
   */
  @NonNull
  public static Result resolveForIncoming(@NonNull Context ctx,
                                          @NonNull String fallbackName,
                                          @Nullable CallInvite callInvite) {
    final String e164 = pickE164(fallbackName, callInvite);
    if (e164 == null) {
      return new Result(fallbackName, null, null);
    }

    final String telUri = "tel:" + e164;

    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
        != PackageManager.PERMISSION_GRANTED) {
      logger.debug("READ_CONTACTS not granted; skipping contact lookup");
      return new Result(fallbackName, null, telUri);
    }

    final ContentResolver resolver = ctx.getContentResolver();
    final Uri lookupUri = Uri.withAppendedPath(
      ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164));
    final String[] projection = new String[] {
      ContactsContract.PhoneLookup.DISPLAY_NAME,
      ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
    };

    String contactName = null;
    String photoUri = null;
    try (Cursor cursor = resolver.query(lookupUri, projection, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        contactName = cursor.getString(0);
        photoUri = cursor.getString(1);
      }
    } catch (Exception e) {
      logger.error("PhoneLookup failed: " + e);
    }

    if (contactName == null || contactName.isEmpty()) {
      return new Result(fallbackName, null, telUri);
    }

    // Replace the display name only when the fallback was the raw E.164 itself —
    // otherwise the caller passed a deliberate templated label we must respect
    // (parity with iOS where localizedCallerName preserves the template).
    final String displayName = isE164(fallbackName) ? contactName : fallbackName;
    final IconCompat icon = loadIcon(resolver, photoUri);
    return new Result(displayName, icon, telUri);
  }

  @Nullable
  private static String pickE164(@NonNull String fallbackName, @Nullable CallInvite callInvite) {
    if (isE164(fallbackName)) return fallbackName;
    if (callInvite == null) return null;
    String from = callInvite.getFrom();
    if (from == null) return null;
    if (from.startsWith("client:")) from = from.substring("client:".length());
    return isE164(from) ? from : null;
  }

  @Nullable
  private static IconCompat loadIcon(@NonNull ContentResolver resolver, @Nullable String photoUri) {
    if (photoUri == null || photoUri.isEmpty()) return null;
    try (InputStream in = resolver.openInputStream(Uri.parse(photoUri))) {
      if (in == null) return null;
      final Bitmap bitmap = BitmapFactory.decodeStream(in);
      if (bitmap == null) return null;
      return IconCompat.createWithAdaptiveBitmap(bitmap);
    } catch (Exception e) {
      logger.debug("contact photo load failed: " + e);
      return null;
    }
  }
}
