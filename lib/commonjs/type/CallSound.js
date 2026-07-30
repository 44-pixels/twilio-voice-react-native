"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.CallSound = void 0;
// FORK — KAR-787
// Owns: Cross-platform call-sound settings exposed to consuming applications.
// Hooks into: Voice.tsx and the Android/iOS native call-sound bridges.
// Re-check on SDK bump: Voice public API and native module promise conventions.

/**
 * Runtime call-sound configuration.
 *
 * Custom sounds are declared at build time through the Expo config plugin.
 */
let CallSound;
exports.CallSound = CallSound;

(function (_CallSound) {
  const SystemRingtoneId = _CallSound.SystemRingtoneId = 'os-ringtone';
})(CallSound || (exports.CallSound = CallSound = {}));
//# sourceMappingURL=CallSound.js.map