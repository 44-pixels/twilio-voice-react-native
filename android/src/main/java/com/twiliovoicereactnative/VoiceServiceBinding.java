// FORK — KAR-295 (Sentry KAREN-APP-3G)
// Owns: safe cast of the IBinder delivered to ServiceConnection.onServiceConnected.
// Hooks into: VoiceApplicationProxy.voiceServiceObserver.onServiceConnected.
// Re-check on SDK bump: whether upstream added an instanceof guard around the
// VoiceServiceAPI cast.
//
// On certain OEM builds (seen on Moto G 2025 / Android 16) the framework returns
// a BinderProxy instead of the local VoiceServiceAPI Binder subclass, causing
// a fatal ClassCastException. App-side cannot force local binding; survive by
// guarding the cast. Voice features will be unavailable on affected devices
// since getVoiceServiceApi() will keep returning null.
package com.twiliovoicereactnative;

import android.content.ComponentName;
import android.os.IBinder;

public final class VoiceServiceBinding {
  private static final SDKLog logger = new SDKLog(VoiceServiceBinding.class);

  private VoiceServiceBinding() {}

  public static VoiceService.VoiceServiceAPI bind(ComponentName name, IBinder service) {
    if (!name.getClassName().equals(VoiceService.class.getName())) return null;
    if (service instanceof VoiceService.VoiceServiceAPI) {
      return (VoiceService.VoiceServiceAPI) service;
    }
    logger.error("VoiceService bound cross-process; got " + service.getClass().getName());
    return null;
  }
}
