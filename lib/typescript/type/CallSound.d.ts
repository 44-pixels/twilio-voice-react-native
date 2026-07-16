/**
 * Runtime call-sound configuration.
 *
 * Custom sounds are declared at build time through the Expo config plugin.
 */
export declare namespace CallSound {
    /** Static identifier for the ringtone configured in the device settings. */
    const SystemRingtoneId = "os-ringtone";
    /** A sound bundled into the application by the Expo config plugin. */
    type Bundled = {
        mode: 'bundled';
        soundId: string;
    };
    /** Incoming-call ringtone selection. */
    type RingtoneSetting = {
        mode: 'default';
    } | Bundled;
    /** Whether the configured call-ended sound is enabled. */
    type CallEndedSetting = {
        mode: 'enabled';
    } | {
        mode: 'disabled';
    };
    /** Persisted call-sound settings. */
    type Settings = {
        ringtone: RingtoneSetting;
        callEnded: CallEndedSetting;
    };
    /** A selectable ringtone installed in the native application. */
    type AvailableSound = {
        id: string;
        displayName: string;
        isDefault: boolean;
    };
}
