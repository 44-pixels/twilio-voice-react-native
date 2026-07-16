// FORK — KAR-787
// Owns: React Native bridge for persisted call-sound settings and preview.
// Hooks into: TwilioVoiceReactNative and ForkCallSounds.
// Re-check on SDK bump: RCT bridge export and promise adapter conventions.

#import "ForkCallSounds.h"
#import "TwilioVoiceReactNative.h"
#import "TwilioVoiceReactNativeConstants.h"

@implementation TwilioVoiceReactNative (ForkCallSounds)

RCT_EXPORT_METHOD(voice_setCallSoundSettings:(NSString *)ringtoneMode
                  ringtoneSoundId:(NSString *)ringtoneSoundId
                  callEndedMode:(NSString *)callEndedMode
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)
{
    BOOL didSet = [ForkCallSounds setRingtoneMode:ringtoneMode
                                 ringtoneSoundId:ringtoneSoundId
                                   callEndedMode:callEndedMode];
    if (!didSet) {
        [self rejectPromiseWithName:resolver
                               name:kTwilioVoiceReactNativeErrorCodeInvalidArgumentError
                            message:@"Invalid call-sound settings or unknown bundled sound."];
        return;
    }

    [self initializeCallKitWithConfiguration:[ForkCallSounds rememberedCallKitConfiguration]];
    [self resolvePromise:resolver value:[NSNull null]];
}

RCT_EXPORT_METHOD(voice_getCallSoundSettings:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)
{
    [self resolvePromise:resolver value:[ForkCallSounds settings]];
}

RCT_EXPORT_METHOD(voice_getAvailableRingtones:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)
{
    [self resolvePromise:resolver value:[ForkCallSounds availableSounds]];
}

RCT_EXPORT_METHOD(voice_previewCallSound:(NSString *)soundId
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)
{
    if (![ForkCallSounds previewSoundId:soundId]) {
        [self rejectPromiseWithName:resolver
                               name:kTwilioVoiceReactNativeErrorCodeInvalidArgumentError
                            message:@"Unknown ringtone or ringtone preview is unavailable."];
        return;
    }
    [self resolvePromise:resolver value:[NSNull null]];
}

RCT_EXPORT_METHOD(voice_stopCallSoundPreview:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)
{
    [ForkCallSounds stopPreview];
    [self resolvePromise:resolver value:[NSNull null]];
}

@end
