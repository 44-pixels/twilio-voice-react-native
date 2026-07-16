// FORK — KAR-787
// Owns: Persisted bundled ringtone/call-ended selection and native playback.
// Hooks into: TwilioVoiceReactNative+CallKit.m and TwilioVoiceReactNative+ForkCallSounds.m.
// Re-check on SDK bump: CallKit provider initialization and disconnect callbacks.

#import <Foundation/Foundation.h>

@class AVAudioPlayer;

@interface ForkCallSounds : NSObject

+ (void)rememberCallKitConfiguration:(NSDictionary *)configuration;
+ (NSDictionary *)rememberedCallKitConfiguration;
+ (NSString *)ringtoneSoundOverridingDefault:(NSString *)defaultSound;
+ (NSDictionary *)settings;
+ (NSArray *)availableSounds;
+ (BOOL)setRingtoneMode:(NSString *)ringtoneMode
        ringtoneSoundId:(NSString *)ringtoneSoundId
          callEndedMode:(NSString *)callEndedMode;
+ (BOOL)previewSoundId:(NSString *)soundId;
+ (void)stopPreview;
+ (void)playCallEndedSound;

@end
