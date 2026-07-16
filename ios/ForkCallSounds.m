// FORK — KAR-787
// Owns: Persisted bundled ringtone/call-ended selection and native playback.
// Hooks into: TwilioVoiceReactNative+CallKit.m and TwilioVoiceReactNative+ForkCallSounds.m.
// Re-check on SDK bump: CallKit provider initialization and disconnect callbacks.

#import <AVFoundation/AVFoundation.h>

#import "ForkCallSounds.h"

static NSString * const ForkModeDefault = @"default";
static NSString * const ForkModeBundled = @"bundled";
static NSString * const ForkModeEnabled = @"enabled";
static NSString * const ForkModeDisabled = @"disabled";
static NSString * const ForkRingtoneModeKey = @"forkCallSoundsRingtoneMode";
static NSString * const ForkRingtoneIdKey = @"forkCallSoundsRingtoneId";
static NSString * const ForkCallEndedModeKey = @"forkCallSoundsCallEndedMode";
static NSString * const ForkCatalogName = @"twilio_voice_call_sounds";
static NSString * const ForkOSRingtoneId = @"os-ringtone";

static NSDictionary *sRememberedCallKitConfiguration;
static AVAudioPlayer *sPreviewPlayer;
static AVAudioPlayer *sCallEndedPlayer;
static NSString *sPreviewPreviousCategory;
static NSString *sPreviewPreviousMode;
static AVAudioSessionCategoryOptions sPreviewPreviousOptions;

@implementation ForkCallSounds

+ (void)rememberCallKitConfiguration:(NSDictionary *)configuration {
    sRememberedCallKitConfiguration = configuration ? [configuration copy] : @{};
}

+ (NSDictionary *)rememberedCallKitConfiguration {
    return sRememberedCallKitConfiguration ?: @{};
}

+ (NSString *)ringtoneSoundOverridingDefault:(NSString *)defaultSound {
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSString *mode = [preferences stringForKey:ForkRingtoneModeKey] ?: ForkModeDefault;
    NSString *soundId = [preferences stringForKey:ForkRingtoneIdKey];
    if ([mode isEqualToString:ForkModeBundled] && [soundId isEqualToString:ForkOSRingtoneId]) {
        return nil;
    }
    NSDictionary *entry = [mode isEqualToString:ForkModeBundled]
        ? [self catalogEntryForId:soundId]
        : [self defaultRingtoneEntry];
    return entry[@"fileName"] ?: defaultSound;
}

+ (NSDictionary *)settings {
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSString *ringtoneMode = [preferences stringForKey:ForkRingtoneModeKey] ?: ForkModeDefault;
    NSString *callEndedMode = [preferences stringForKey:ForkCallEndedModeKey] ?: ForkModeEnabled;

    NSMutableDictionary *ringtone = [@{@"mode": ringtoneMode} mutableCopy];
    NSString *ringtoneSoundId = [preferences stringForKey:ForkRingtoneIdKey];
    if ([ringtoneMode isEqualToString:ForkModeBundled] && ringtoneSoundId) {
        ringtone[@"soundId"] = ringtoneSoundId;
    }

    NSDictionary *callEnded = @{@"mode": callEndedMode};

    return @{@"ringtone": ringtone, @"callEnded": callEnded};
}

+ (NSArray *)availableSounds {
    NSMutableArray *available = [NSMutableArray arrayWithObject:@{
        @"id": ForkOSRingtoneId,
        @"displayName": @"Device default",
        @"isDefault": @NO,
    }];
    for (NSDictionary *entry in [self ringtoneCatalog]) {
        NSString *soundId = entry[@"id"];
        NSString *displayName = entry[@"displayName"];
        NSString *fileName = entry[@"fileName"];
        if (soundId.length == 0 || displayName.length == 0 || ![self soundURLForFileName:fileName]) {
            continue;
        }
        NSNumber *isDefault = [entry[@"isDefault"] isKindOfClass:[NSNumber class]]
            ? entry[@"isDefault"]
            : @NO;
        [available addObject:@{
            @"id": soundId,
            @"displayName": displayName,
            @"isDefault": isDefault,
        }];
    }
    return available;
}

+ (BOOL)setRingtoneMode:(NSString *)ringtoneMode
        ringtoneSoundId:(NSString *)ringtoneSoundId
          callEndedMode:(NSString *)callEndedMode {
    BOOL validRingtoneMode = [ringtoneMode isEqualToString:ForkModeDefault]
        || [ringtoneMode isEqualToString:ForkModeBundled];
    BOOL validCallEndedMode = [callEndedMode isEqualToString:ForkModeEnabled]
        || [callEndedMode isEqualToString:ForkModeDisabled];
    if (!validRingtoneMode || !validCallEndedMode) {
        return NO;
    }
    BOOL isSystemRingtone = [ringtoneSoundId isEqualToString:ForkOSRingtoneId];
    if ([ringtoneMode isEqualToString:ForkModeBundled]
        && !isSystemRingtone
        && ![self catalogEntryForId:ringtoneSoundId]) {
        return NO;
    }

    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    [preferences setObject:ringtoneMode forKey:ForkRingtoneModeKey];
    [self setOptionalPreference:ringtoneSoundId forKey:ForkRingtoneIdKey];
    [preferences setObject:callEndedMode forKey:ForkCallEndedModeKey];
    return YES;
}

+ (BOOL)previewSoundId:(NSString *)soundId {
    NSDictionary *entry = [self catalogEntryForId:soundId];
    NSURL *soundURL = [self soundURLForFileName:entry[@"fileName"]];
    if (!soundURL) {
        return NO;
    }

    [self stopPreview];

    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    sPreviewPreviousCategory = audioSession.category;
    sPreviewPreviousMode = audioSession.mode;
    sPreviewPreviousOptions = audioSession.categoryOptions;

    NSError *sessionError = nil;
    [audioSession setCategory:AVAudioSessionCategoryPlayback
                         mode:AVAudioSessionModeDefault
                      options:0
                        error:&sessionError];
    if (!sessionError) {
        [audioSession setActive:YES error:&sessionError];
    }
    if (sessionError) {
        [self restorePreviewAudioSession];
        return NO;
    }

    NSError *playerError = nil;
    sPreviewPlayer = [[AVAudioPlayer alloc] initWithContentsOfURL:soundURL error:&playerError];
    if (playerError || ![sPreviewPlayer prepareToPlay] || ![sPreviewPlayer play]) {
        [self stopPreview];
        return NO;
    }
    return YES;
}

+ (void)stopPreview {
    [sPreviewPlayer stop];
    sPreviewPlayer = nil;
    [self restorePreviewAudioSession];
}

+ (void)restorePreviewAudioSession {
    if (!sPreviewPreviousCategory || !sPreviewPreviousMode) {
        return;
    }

    [[AVAudioSession sharedInstance] setCategory:sPreviewPreviousCategory
                                            mode:sPreviewPreviousMode
                                         options:sPreviewPreviousOptions
                                           error:nil];
    sPreviewPreviousCategory = nil;
    sPreviewPreviousMode = nil;
    sPreviewPreviousOptions = 0;
}

+ (void)playCallEndedSound {
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSString *mode = [preferences stringForKey:ForkCallEndedModeKey] ?: ForkModeEnabled;
    if (![mode isEqualToString:ForkModeEnabled]) {
        return;
    }

    NSDictionary *entry = [self catalog][@"callEnded"];
    NSURL *soundURL = [self soundURLForFileName:entry[@"fileName"]];
    if (!soundURL) {
        return;
    }

    [sCallEndedPlayer stop];
    sCallEndedPlayer = [[AVAudioPlayer alloc] initWithContentsOfURL:soundURL error:nil];
    [sCallEndedPlayer play];
}

+ (NSDictionary *)catalog {
    NSURL *catalogURL = [[NSBundle mainBundle] URLForResource:ForkCatalogName withExtension:@"json"];
    if (!catalogURL) {
        return @{};
    }

    NSData *data = [NSData dataWithContentsOfURL:catalogURL];
    if (!data) {
        return @{};
    }

    id value = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [value isKindOfClass:[NSDictionary class]] ? value : @{};
}

+ (NSArray *)ringtoneCatalog {
    NSArray *ringtones = [self catalog][@"ringtones"];
    return [ringtones isKindOfClass:[NSArray class]] ? ringtones : @[];
}

+ (NSDictionary *)defaultRingtoneEntry {
    for (NSDictionary *entry in [self ringtoneCatalog]) {
        if ([entry[@"isDefault"] boolValue]) {
            return entry;
        }
    }
    return nil;
}

+ (NSDictionary *)catalogEntryForId:(NSString *)soundId {
    if (soundId.length == 0) {
        return nil;
    }
    for (NSDictionary *entry in [self ringtoneCatalog]) {
        if ([entry[@"id"] isEqualToString:soundId]) {
            return entry;
        }
    }
    return nil;
}

+ (void)setOptionalPreference:(NSString *)value forKey:(NSString *)key {
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    if (value) {
        [preferences setObject:value forKey:key];
    } else {
        [preferences removeObjectForKey:key];
    }
}

+ (NSURL *)soundURLForFileName:(NSString *)fileName {
    if (fileName.length == 0) {
        return nil;
    }
    return [[NSBundle mainBundle] URLForResource:fileName withExtension:nil];
}

@end
