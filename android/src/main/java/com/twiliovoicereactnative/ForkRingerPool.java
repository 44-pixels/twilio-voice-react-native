// FORK — KAR-373, KAR-787
// Owns: incoming-call ringer routing and runtime bundled-ringtone selection.
// Hooks into: MediaPlayerManager (play/stop/finalize divert SoundTable.INCOMING here).
// Re-check on SDK bump: confirm SoundTable.INCOMING still exists and that incoming
// calls still start/stop the ringer through MediaPlayerManager.
package com.twiliovoicereactnative;

import android.content.Context;
import android.media.MediaPlayer;

final class ForkRingerPool {
  private final Context context;
  private MediaPlayer player;

  ForkRingerPool(Context context) {
    this.context = context.getApplicationContext();
  }

  void play() {
    stop();
    player = ForkCallSounds.createRingtonePlayer(context, R.raw.incoming);
    if (player != null) player.start();
  }

  boolean stop() {
    if (player == null) return false;
    if (player.isPlaying()) player.stop();
    player.release();
    player = null;
    return true;
  }

  void release() {
    stop();
  }
}
