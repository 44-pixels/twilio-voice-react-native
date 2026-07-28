// FORK — KAR-787
// Owns: Persisted bundled call-sound settings and native playback.
// Hooks into: TwilioVoiceReactNative+CallKit.m, TwilioVoiceReactNative+ForkCallSounds.m,
// and ForkCallIssueState.m.
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
+ (void)fork_playCallEndedSoundForCall:(NSUUID *)uuid;
+ (void)fork_playConnectedSoundForCall:(NSUUID *)uuid;
+ (void)fork_playHasIssuesSoundForCall:(NSUUID *)uuid;
+ (void)fork_stopConnectionStatusSoundForCall:(NSUUID *)uuid;

@end
