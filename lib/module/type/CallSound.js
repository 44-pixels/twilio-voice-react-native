// FORK — KAR-787
// Owns: Cross-platform call-sound settings exposed to consuming applications.
// Hooks into: Voice.tsx and the Android/iOS native call-sound bridges.
// Re-check on SDK bump: Voice public API and native module promise conventions.

/**
 * Runtime call-sound configuration.
 *
 * Custom sounds are declared at build time through the Expo config plugin.
 */
export let CallSound;

(function (_CallSound) {
  const SystemRingtoneId = _CallSound.SystemRingtoneId = 'os-ringtone';
})(CallSound || (CallSound = {}));
//# sourceMappingURL=CallSound.js.map