// FORK — KAR-857
// Owns: Per-call connection-issue state and connected/issue sound transitions.
// Hooks into: TwilioVoiceReactNative+CallKit.m and ForkCallSounds.
// Re-check on SDK bump: Call quality warning names and reconnect callback ordering.

@import TwilioVoice;

#import "ForkCallIssueState.h"
#import "ForkCallSounds.h"

typedef NS_ENUM(NSInteger, ForkIssueState) {
    ForkIssueStateNoIssues,
    ForkIssueStateHasIssues,
};

@interface ForkCallIssueRecord : NSObject

@property (nonatomic, strong) NSUUID *uuid;
@property (nonatomic) BOOL reconnecting;
@property (nonatomic) BOOL poorNetworkQuality;
@property (nonatomic) NSTimeInterval nextSoundAllowedAt;
@property (nonatomic) ForkIssueState issueState;

@end

@implementation ForkCallIssueRecord
@end

static const NSTimeInterval ForkSoundThrottleSeconds = 2.0;
static const NSTimeInterval ForkEndedCallMarkerLifetimeSeconds = 30.0;
static NSMutableDictionary<NSString *, ForkCallIssueRecord *> *sStates;
static NSMutableSet<NSString *> *sRecentlyEndedCallUUIDs;

@implementation ForkCallIssueState

+ (void)fork_connectedCall:(NSUUID *)uuid {
    [self fork_onMainQueue:^{
        if ([self fork_hasRecentlyEnded:uuid]) return;

        ForkCallIssueRecord *record = [ForkCallIssueRecord new];
        record.uuid = uuid;
        record.issueState = ForkIssueStateNoIssues;
        [self fork_states][uuid.UUIDString] = record;
        [self fork_playIfAllowed:record sound:^{
            [ForkCallSounds fork_playConnectedSoundForCall:uuid];
        }];
    }];
}

+ (void)fork_reconnectingCall:(NSUUID *)uuid {
    [self fork_onMainQueue:^{
        ForkCallIssueRecord *record = [self fork_states][uuid.UUIDString];
        if (!record) return;

        record.reconnecting = YES;
        [self fork_updateRecord:record];
    }];
}

+ (void)fork_reconnectedCall:(NSUUID *)uuid {
    [self fork_onMainQueue:^{
        ForkCallIssueRecord *record = [self fork_states][uuid.UUIDString];
        if (!record) return;

        record.reconnecting = NO;
        [self fork_updateRecord:record];
    }];
}

+ (void)fork_call:(NSUUID *)uuid qualityWarningsChanged:(NSSet<NSNumber *> *)currentWarnings {
    BOOL poorNetworkQuality = [self fork_hasNetworkQualityWarning:currentWarnings];
    [self fork_onMainQueue:^{
        ForkCallIssueRecord *record = [self fork_states][uuid.UUIDString];
        if (!record) return;

        record.poorNetworkQuality = poorNetworkQuality;
        [self fork_updateRecord:record];
    }];
}

+ (void)fork_endedCall:(NSUUID *)uuid {
    [self fork_markRecentlyEnded:uuid];
    [self fork_onMainQueue:^{
        [[self fork_states] removeObjectForKey:uuid.UUIDString];
        [ForkCallSounds fork_stopConnectionStatusSoundForCall:uuid];
        [self fork_scheduleEndedMarkerRemoval:uuid];
    }];
}

+ (void)fork_onMainQueue:(dispatch_block_t)block {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

+ (NSMutableDictionary<NSString *, ForkCallIssueRecord *> *)fork_states {
    if (!sStates) sStates = [NSMutableDictionary dictionary];
    return sStates;
}

+ (BOOL)fork_hasNetworkQualityWarning:(NSSet<NSNumber *> *)warnings {
    return [warnings containsObject:@(TVOCallQualityWarningHighRtt)]
        || [warnings containsObject:@(TVOCallQualityWarningHighJitter)]
        || [warnings containsObject:@(TVOCallQualityWarningHighPacketsLostFraction)]
        || [warnings containsObject:@(TVOCallQualityWarningLowMos)];
}

+ (void)fork_updateRecord:(ForkCallIssueRecord *)record {
    ForkIssueState nextIssueState = record.reconnecting || record.poorNetworkQuality
        ? ForkIssueStateHasIssues
        : ForkIssueStateNoIssues;
    if (record.issueState == nextIssueState) return;

    record.issueState = nextIssueState;
    if (nextIssueState == ForkIssueStateHasIssues) {
        [self fork_playIfAllowed:record sound:^{
            [ForkCallSounds fork_playHasIssuesSoundForCall:record.uuid];
        }];
    } else {
        [self fork_playIfAllowed:record sound:^{
            [ForkCallSounds fork_playConnectedSoundForCall:record.uuid];
        }];
    }
}

+ (void)fork_playIfAllowed:(ForkCallIssueRecord *)record sound:(dispatch_block_t)playSound {
    NSTimeInterval now = [NSProcessInfo processInfo].systemUptime;
    if (now < record.nextSoundAllowedAt) return;

    record.nextSoundAllowedAt = now + ForkSoundThrottleSeconds;
    playSound();
}

+ (void)fork_markRecentlyEnded:(NSUUID *)uuid {
    @synchronized(self) {
        if (!sRecentlyEndedCallUUIDs) sRecentlyEndedCallUUIDs = [NSMutableSet set];
        [sRecentlyEndedCallUUIDs addObject:uuid.UUIDString];
    }
}

+ (BOOL)fork_hasRecentlyEnded:(NSUUID *)uuid {
    @synchronized(self) {
        return [sRecentlyEndedCallUUIDs containsObject:uuid.UUIDString];
    }
}

+ (void)fork_scheduleEndedMarkerRemoval:(NSUUID *)uuid {
    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW, (int64_t)(ForkEndedCallMarkerLifetimeSeconds * NSEC_PER_SEC)),
        dispatch_get_main_queue(),
        ^{
            @synchronized(self) {
                [sRecentlyEndedCallUUIDs removeObject:uuid.UUIDString];
            }
        }
    );
}

@end
