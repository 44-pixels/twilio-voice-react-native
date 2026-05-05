// FORK — KAR-373
// Owns: separate SoundPool for the incoming-call ringer with
// USAGE_NOTIFICATION_RINGTONE so it routes to STREAM_RING (loud speaker /
// ringer volume) instead of the in-call earpiece.
// Hooks into: MediaPlayerManager (play/stop/finalize divert SoundTable.INCOMING here).
// Re-check on SDK bump: confirm SoundTable.INCOMING still exists and that
// R.raw.incoming is still the correct asset; verify play/stop semantics
// (loop=-1, no early termination) still match the rest of the pool.
//
// Why a separate pool: SoundPool's AudioAttributes are set at construction
// and shared by every loaded sample. The upstream pool uses
// USAGE_VOICE_COMMUNICATION — correct for OUTGOING/DISCONNECT/RINGTONE
// (played during an active call) but wrong for the pre-accept ringer.
package com.twiliovoicereactnative;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

final class ForkRingerPool {
  private final SoundPool pool;
  private final int soundId;
  private int activeStream;

  ForkRingerPool(Context context) {
    pool = new SoundPool.Builder()
      .setMaxStreams(1)
      .setAudioAttributes(new AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .build())
      .build();
    soundId = pool.load(context, R.raw.incoming, 1);
  }

  void play() {
    activeStream = pool.play(soundId, 1.f, 1.f, 1, -1, 1.f);
  }

  /** @return true if a stream was active and got stopped. */
  boolean stop() {
    if (activeStream == 0) return false;
    pool.stop(activeStream);
    activeStream = 0;
    return true;
  }

  void release() {
    pool.release();
  }
}
