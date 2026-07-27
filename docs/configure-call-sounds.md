# Configure call sounds

The SDK can select an incoming-call ringtone, play connection-status feedback, and control an app-provided call-ended sound at runtime. Sounds must be bundled into the native application through the Expo config plugin.

## Add sounds to an Expo build

```js
// app.config.js
module.exports = {
  expo: {
    plugins: [
      [
        '@twilio/voice-react-native-sdk',
        {
          callSounds: {
            ringtones: [
              {
                id: 'classic',
                displayName: 'Classic',
                source: './assets/sounds/classic.wav',
                default: true,
              },
              {
                id: 'soft',
                displayName: 'Soft',
                source: './assets/sounds/soft.wav',
              },
            ],
            connected: {
              source: './assets/sounds/connected.wav',
            },
            hasIssues: {
              source: './assets/sounds/has-issues.wav',
            },
            callEnded: {
              source: './assets/sounds/end-call.wav',
            },
          },
        },
      ],
    ],
  },
};
```

Changing this catalog requires a native rebuild. It is unavailable in Expo Go. The `connected`, `hasIssues`, and `callEnded` sounds are optional.

The SDK plays `connected` when a call initially connects and when connection issues clear. It plays `hasIssues` when either Twilio reconnects or reports poor network quality. State transitions take effect immediately, while status sounds are throttled to at most one every two seconds to avoid rapid sound repetition.

Sound IDs must start with a lowercase letter and contain only lowercase letters, numbers, and underscores. At most one ringtone can have `default: true`. Users whose persisted ringtone mode is `default` automatically receive a newly configured default after upgrading; users who explicitly selected a ringtone keep that selection. If no ringtone is marked as default, the SDK's built-in ringtone is used. Use audio files supported by both target platforms.

## Build a settings UI

```ts
import {CallSound} from '@twilio/voice-react-native-sdk';

const sounds = await voice.getAvailableRingtones();
// Always includes the Device default item:
// {id: CallSound.SystemRingtoneId, displayName: 'Device default', ...}.

await voice.previewCallSound(sounds[0].id);
await voice.stopCallSoundPreview();

await voice.setCallSoundSettings({
  ringtone: {mode: 'bundled', soundId: sounds[0].id},
  callEnded: {mode: 'enabled'},
});

const settings = await voice.getCallSoundSettings();
```

Selections are persisted natively, so incoming calls use them even when React Native has not started. Select `{mode: 'bundled', soundId: CallSound.SystemRingtoneId}` to use the ringtone configured in the phone's operating-system settings. The actual system ringtone cannot be previewed on iOS; CallKit plays it for incoming calls.

Use `{mode: 'default'}` for the platform ringtone. Set `callEnded` to `{mode: 'disabled'}` to suppress the configured app-provided call-ended sound. On iOS, this does not disable feedback produced by CallKit itself.
