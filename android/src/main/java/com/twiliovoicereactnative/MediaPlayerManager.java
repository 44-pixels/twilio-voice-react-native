package com.twiliovoicereactnative;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.HashMap;
import java.util.Map;

class MediaPlayerManager {
  public enum SoundTable {
    INCOMING,
    OUTGOING,
    DISCONNECT,
    RINGTONE
  }
  private final SoundPool soundPool;
  private final Map<SoundTable, Integer> soundMap;
  private int activeStream;
  // >>> FORK KAR-787 — see ForkCallSounds
  private final Context context;
  // <<< FORK
  // >>> FORK KAR-373 — see ForkRingerPool
  private final ForkRingerPool forkRinger;
  // <<< FORK

  MediaPlayerManager(Context context) {
    // >>> FORK KAR-787 — see ForkCallSounds
    this.context = context.getApplicationContext();
    // <<< FORK
    soundPool = (new SoundPool.Builder())
      .setMaxStreams(2)
      .setAudioAttributes(
        new AudioAttributes.Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
          .build())
      .build();
    activeStream = 0;
    soundMap = new HashMap<>();
    soundMap.put(SoundTable.INCOMING, soundPool.load(context, R.raw.incoming, 1));
    soundMap.put(SoundTable.OUTGOING, soundPool.load(context, R.raw.outgoing, 1));
    soundMap.put(SoundTable.DISCONNECT, soundPool.load(context, R.raw.disconnect, 1));
    soundMap.put(SoundTable.RINGTONE, soundPool.load(context, R.raw.ringtone, 1));
    forkRinger = new ForkRingerPool(context); // FORK KAR-373
  }

  public void play(final SoundTable sound) {
    if (sound == SoundTable.INCOMING) { forkRinger.play(); return; } // FORK KAR-373
    // >>> FORK KAR-787 — see ForkCallSounds
    if (sound == SoundTable.DISCONNECT && ForkCallSounds.handleCallEnded(context)) return;
    // <<< FORK
    activeStream = soundPool.play(
      soundMap.get(sound),
      1.f,
      1.f,
      1,
      (SoundTable.DISCONNECT== sound) ? 0 : -1,
      1.f);
  }

  public void stop() {
    if (forkRinger.stop()) return; // FORK KAR-373
    soundPool.stop(activeStream);
    activeStream = 0;
  }

  @Override
  protected void finalize() throws Throwable {
    soundPool.release();
    forkRinger.release(); // FORK KAR-373
    super.finalize();
  }
}
