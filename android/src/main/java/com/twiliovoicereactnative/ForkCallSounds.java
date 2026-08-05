// FORK — KAR-787
// Owns: Persisted call-sound settings, bundled sound discovery, and native playback.
// Hooks into: ExpoModule, TwilioVoiceReactNativeModule, ForkCallLifecycleCoordinator,
// ForkRingerPool, and ForkCallIssueState.
// Re-check on SDK bump: native promise serialization and call audio usage attributes.
package com.twiliovoicereactnative;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ForkCallSounds {
  private static final String MODE_DEFAULT = "default";
  private static final String MODE_BUNDLED = "bundled";
  private static final String MODE_ENABLED = "enabled";
  private static final String MODE_DISABLED = "disabled";
  private static final String PREF_RINGTONE_MODE = "fork_call_sounds_ringtone_mode";
  private static final String PREF_RINGTONE_ID = "fork_call_sounds_ringtone_id";
  private static final String PREF_CALL_ENDED_MODE = "fork_call_sounds_call_ended_mode";
  private static final float CALL_SOUND_VOLUME = 0.4f;
  private static final String CATALOG_RESOURCE = "twilio_voice_call_sounds";
  private static final String SOUND_RESOURCE_PREFIX = "twilio_voice_call_sound_";
  private static final String CONNECTED_RESOURCE = "twilio_voice_connected";
  private static final String HAS_ISSUES_RESOURCE = "twilio_voice_has_issues";
  private static final String OS_RINGTONE_ID = "os-ringtone";
  private static final long CALL_ENDED_TIMEOUT_MILLIS = 3_000;

  private static final SDKLog logger = new SDKLog(ForkCallSounds.class);
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());
  private static MediaPlayer previewPlayer;
  private static CallEndedPlayback callEndedPlayback;
  private static MediaPlayer connectionStatusPlayer;

  private static final class CallEndedPlayback {
    private final MediaPlayer player;
    private final Runnable onFinished;
    private final Runnable timeout;
    private boolean finished;

    private CallEndedPlayback(MediaPlayer player, Runnable onFinished) {
      this.player = player;
      this.onFinished = onFinished;
      this.timeout = () -> {
        logger.warning("Timed out playing call-ended sound");
        finishCallEndedPlayback(this);
      };
    }
  }

  private ForkCallSounds() {}

  public static void setSettings(
    String ringtoneMode,
    String ringtoneSoundId,
    String callEndedMode,
    ModuleProxy.UniversalPromise promise
  ) {
    Context context = VoiceApplicationProxy.getApplicationContext();
    if (!isRingtoneMode(ringtoneMode) || !isCallEndedMode(callEndedMode)) {
      promise.rejectWithName(CommonConstants.ErrorCodeInvalidArgumentError, "Invalid call sound mode.");
      return;
    }
    if (!validBundledRingtone(context, ringtoneMode, ringtoneSoundId)) {
      promise.rejectWithName(CommonConstants.ErrorCodeInvalidArgumentError, "Unknown bundled call sound.");
      return;
    }

    preferences(context).edit()
      .putString(PREF_RINGTONE_MODE, ringtoneMode)
      .putString(PREF_RINGTONE_ID, ringtoneSoundId)
      .putString(PREF_CALL_ENDED_MODE, callEndedMode)
      .apply();
    promise.resolve(null);
  }

  public static void getSettings(ModuleProxy.UniversalPromise promise) {
    Context context = VoiceApplicationProxy.getApplicationContext();
    SharedPreferences preferences = preferences(context);
    promise.resolve(settingsMap(
      preferences.getString(PREF_RINGTONE_MODE, MODE_DEFAULT),
      preferences.getString(PREF_RINGTONE_ID, null),
      preferences.getString(PREF_CALL_ENDED_MODE, MODE_ENABLED)
    ));
  }

  public static void getAvailableSounds(ModuleProxy.UniversalPromise promise) {
    promise.resolve(availableSounds(VoiceApplicationProxy.getApplicationContext()));
  }

  public static void preview(String soundId, ModuleProxy.UniversalPromise promise) {
    Context context = VoiceApplicationProxy.getApplicationContext();
    if (OS_RINGTONE_ID.equals(soundId)) {
      stopPreview();
      previewPlayer = createPlayer(
        context,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        false
      );
      if (previewPlayer == null) {
        promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, "Unable to play system ringtone preview.");
        return;
      }
      previewPlayer.setOnCompletionListener(player -> stopPreview());
      previewPlayer.start();
      promise.resolve(null);
      return;
    }

    int resourceId = bundledResourceId(context, soundId);
    if (resourceId == 0) {
      promise.rejectWithName(CommonConstants.ErrorCodeInvalidArgumentError, "Unknown bundled call sound.");
      return;
    }

    stopPreview();
    previewPlayer = createPlayer(
      context,
      resourceId,
      AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
      false
    );
    if (previewPlayer == null) {
      promise.rejectWithName(CommonConstants.ErrorCodeInvalidStateError, "Unable to play call sound preview.");
      return;
    }
    previewPlayer.setOnCompletionListener(player -> stopPreview());
    previewPlayer.start();
    promise.resolve(null);
  }

  public static void stopPreview(ModuleProxy.UniversalPromise promise) {
    stopPreview();
    promise.resolve(null);
  }

  static int ringtoneResourceId(Context context, int defaultResourceId) {
    SharedPreferences preferences = preferences(context);
    String mode = preferences.getString(PREF_RINGTONE_MODE, MODE_DEFAULT);
    String soundId = MODE_BUNDLED.equals(mode)
      ? preferences.getString(PREF_RINGTONE_ID, null)
      : defaultRingtoneId(context);
    int configuredResourceId = bundledResourceId(context, soundId);
    return configuredResourceId == 0 ? defaultResourceId : configuredResourceId;
  }

  static void playCallEnded(Context context, Runnable onFinished) {
    runOnMainThread(() -> startCallEndedPlayback(context, onFinished));
  }

  private static void startCallEndedPlayback(Context context, Runnable onFinished) {
    stopConnectionStatusSound();
    finishCallEndedPlayback(callEndedPlayback);

    SharedPreferences preferences = preferences(context);
    String mode = preferences.getString(PREF_CALL_ENDED_MODE, MODE_ENABLED);
    if (MODE_DISABLED.equals(mode)) {
      onFinished.run();
      return;
    }

    int configuredResourceId = callEndedResourceId(context);
    int resourceId = configuredResourceId == 0 ? R.raw.disconnect : configuredResourceId;
    MediaPlayer player = createPlayer(
      context,
      resourceId,
      AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
      false
    );
    if (player == null) {
      onFinished.run();
      return;
    }
    if (configuredResourceId != 0) {
      player.setVolume(CALL_SOUND_VOLUME, CALL_SOUND_VOLUME);
    }

    CallEndedPlayback playback = new CallEndedPlayback(player, onFinished);
    callEndedPlayback = playback;
    player.setOnCompletionListener(completedPlayer -> finishCallEndedPlayback(playback));
    player.setOnErrorListener((failedPlayer, what, extra) -> {
      finishCallEndedPlayback(playback);
      return true;
    });
    mainHandler.postDelayed(playback.timeout, CALL_ENDED_TIMEOUT_MILLIS);

    try {
      player.start();
    } catch (RuntimeException error) {
      logger.warning(error, "Failed to start call-ended sound");
      finishCallEndedPlayback(playback);
    }
  }

  private static void finishCallEndedPlayback(CallEndedPlayback playback) {
    if (playback == null || playback.finished) return;

    playback.finished = true;
    mainHandler.removeCallbacks(playback.timeout);
    if (callEndedPlayback == playback) callEndedPlayback = null;
    playback.player.setOnCompletionListener(null);
    playback.player.setOnErrorListener(null);
    playback.player.release();
    playback.onFinished.run();
  }

  static void playConnectedSound() {
    playConnectionStatusSound("connected", CONNECTED_RESOURCE);
  }

  static void playHasIssuesSound() {
    playConnectionStatusSound("hasIssues", HAS_ISSUES_RESOURCE);
  }

  static void stopConnectionStatusSound() {
    release(connectionStatusPlayer);
    connectionStatusPlayer = null;
  }

  static MediaPlayer createRingtonePlayer(Context context, int defaultResourceId) {
    SharedPreferences preferences = preferences(context);
    String mode = preferences.getString(PREF_RINGTONE_MODE, MODE_DEFAULT);
    String soundId = preferences.getString(PREF_RINGTONE_ID, null);
    if (MODE_BUNDLED.equals(mode) && OS_RINGTONE_ID.equals(soundId)) {
      return createPlayer(
        context,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        true
      );
    }
    return createPlayer(
      context,
      ringtoneResourceId(context, defaultResourceId),
      AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
      true
    );
  }

  private static WritableMap settingsMap(
    String ringtoneMode,
    String ringtoneSoundId,
    String callEndedMode
  ) {
    WritableMap ringtone = Arguments.createMap();
    ringtone.putString("mode", ringtoneMode);
    if (MODE_BUNDLED.equals(ringtoneMode)) ringtone.putString("soundId", ringtoneSoundId);

    WritableMap callEnded = Arguments.createMap();
    callEnded.putString("mode", callEndedMode);

    WritableMap settings = Arguments.createMap();
    settings.putMap("ringtone", ringtone);
    settings.putMap("callEnded", callEnded);
    return settings;
  }

  private static WritableArray availableSounds(Context context) {
    WritableArray result = Arguments.createArray();
    WritableMap systemRingtone = Arguments.createMap();
    systemRingtone.putString("id", OS_RINGTONE_ID);
    systemRingtone.putString("displayName", "Device default");
    systemRingtone.putBoolean("isDefault", false);
    result.pushMap(systemRingtone);
    JSONArray catalog = catalog(context).optJSONArray("ringtones");
    if (catalog == null) return result;
    for (int index = 0; index < catalog.length(); index += 1) {
      JSONObject entry = catalog.optJSONObject(index);
      if (entry == null) continue;

      String id = entry.optString("id", "");
      String displayName = entry.optString("displayName", "");
      if (id.isEmpty() || displayName.isEmpty() || bundledResourceId(context, id) == 0) continue;

      WritableMap sound = Arguments.createMap();
      sound.putString("id", id);
      sound.putString("displayName", displayName);
      sound.putBoolean("isDefault", entry.optBoolean("isDefault", false));
      result.pushMap(sound);
    }
    return result;
  }

  private static boolean validBundledRingtone(Context context, String mode, String soundId) {
    if (!MODE_BUNDLED.equals(mode) || OS_RINGTONE_ID.equals(soundId)) return true;
    JSONArray ringtones = catalog(context).optJSONArray("ringtones");
    if (ringtones == null) return false;
    for (int index = 0; index < ringtones.length(); index += 1) {
      JSONObject entry = ringtones.optJSONObject(index);
      if (entry != null && soundId != null && soundId.equals(entry.optString("id"))) {
        return bundledResourceId(context, soundId) != 0;
      }
    }
    return false;
  }

  private static boolean isRingtoneMode(String mode) {
    return MODE_DEFAULT.equals(mode) || MODE_BUNDLED.equals(mode);
  }

  private static boolean isCallEndedMode(String mode) {
    return MODE_ENABLED.equals(mode) || MODE_DISABLED.equals(mode);
  }

  private static String defaultRingtoneId(Context context) {
    JSONArray ringtones = catalog(context).optJSONArray("ringtones");
    if (ringtones == null) return null;
    for (int index = 0; index < ringtones.length(); index += 1) {
      JSONObject entry = ringtones.optJSONObject(index);
      if (entry != null && entry.optBoolean("isDefault", false)) {
        return entry.optString("id", null);
      }
    }
    return null;
  }

  private static int bundledResourceId(Context context, String soundId) {
    if (soundId == null || soundId.isEmpty()) return 0;
    return context.getResources().getIdentifier(
      SOUND_RESOURCE_PREFIX + soundId,
      "raw",
      context.getPackageName()
    );
  }

  private static int callEndedResourceId(Context context) {
    JSONObject callEnded = catalog(context).optJSONObject("callEnded");
    if (callEnded == null) return 0;
    return context.getResources().getIdentifier(
      "twilio_voice_call_ended",
      "raw",
      context.getPackageName()
    );
  }

  private static void playConnectionStatusSound(String catalogKey, String resourceName) {
    stopConnectionStatusSound();
    Context context = VoiceApplicationProxy.getApplicationContext();
    if (catalog(context).optJSONObject(catalogKey) == null) return;

    int resourceId = context.getResources().getIdentifier(
      resourceName,
      "raw",
      context.getPackageName()
    );
    if (resourceId == 0) return;

    connectionStatusPlayer = createPlayer(
      context,
      resourceId,
      AudioAttributes.USAGE_VOICE_COMMUNICATION,
      false
    );
    if (connectionStatusPlayer == null) return;

    connectionStatusPlayer.setVolume(CALL_SOUND_VOLUME, CALL_SOUND_VOLUME);
    connectionStatusPlayer.setOnCompletionListener(player -> {
      player.release();
      if (connectionStatusPlayer == player) connectionStatusPlayer = null;
    });
    connectionStatusPlayer.start();
  }

  private static JSONObject catalog(Context context) {
    Resources resources = context.getResources();
    int resourceId = resources.getIdentifier(CATALOG_RESOURCE, "raw", context.getPackageName());
    if (resourceId == 0) return new JSONObject();

    try (InputStream stream = resources.openRawResource(resourceId);
         BufferedReader reader = new BufferedReader(
           new InputStreamReader(stream, StandardCharsets.UTF_8)
         )) {
      StringBuilder contents = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) contents.append(line);
      return new JSONObject(contents.toString());
    } catch (IOException | JSONException exception) {
      return new JSONObject();
    }
  }

  private static MediaPlayer createPlayer(
    Context context,
    int resourceId,
    int usage,
    boolean looping
  ) {
    Uri uri = Uri.parse("android.resource://" + context.getPackageName() + "/" + resourceId);
    return createPlayer(context, uri, usage, looping);
  }

  private static MediaPlayer createPlayer(
    Context context,
    Uri uri,
    int usage,
    boolean looping
  ) {
    MediaPlayer player = new MediaPlayer();
    try {
      player.setAudioAttributes(new AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(usage)
        .build());
      player.setDataSource(context, uri);
      player.setLooping(looping);
      player.prepare();
      return player;
    } catch (IOException | RuntimeException exception) {
      player.release();
      return null;
    }
  }

  private static void stopPreview() {
    release(previewPlayer);
    previewPlayer = null;
  }

  private static void runOnMainThread(Runnable operation) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      operation.run();
    } else {
      mainHandler.post(operation);
    }
  }

  private static void release(MediaPlayer player) {
    if (player == null) return;
    if (player.isPlaying()) player.stop();
    player.release();
  }

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(Constants.PREFERENCES_FILE, Context.MODE_PRIVATE);
  }
}
