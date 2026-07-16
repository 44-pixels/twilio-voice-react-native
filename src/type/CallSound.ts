// FORK — KAR-787
// Owns: Cross-platform call-sound settings exposed to consuming applications.
// Hooks into: Voice.tsx and the Android/iOS native call-sound bridges.
// Re-check on SDK bump: Voice public API and native module promise conventions.

/**
 * Runtime call-sound configuration.
 *
 * Custom sounds are declared at build time through the Expo config plugin.
 */
export namespace CallSound {
  /** Static identifier for the ringtone configured in the device settings. */
  export const SystemRingtoneId = 'os-ringtone';

  /** A sound bundled into the application by the Expo config plugin. */
  export type Bundled = {
    mode: 'bundled';
    soundId: string;
  };

  /** Incoming-call ringtone selection. */
  export type RingtoneSetting = {mode: 'default'} | Bundled;

  /**
   * Whether the single call-ended sound configured at build time is enabled.
   * CallKit may still produce system feedback independently on iOS.
   */
  export type CallEndedSetting = {mode: 'enabled'} | {mode: 'disabled'};

  /** Persisted call-sound settings. */
  export type Settings = {
    ringtone: RingtoneSetting;
    callEnded: CallEndedSetting;
  };

  /** A selectable ringtone installed in the native application at build time. */
  export type AvailableSound = {
    id: string;
    displayName: string;
    isDefault: boolean;
  };
}
