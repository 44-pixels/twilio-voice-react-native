//
//  ForkVoipPushReporter.m
//  TwilioVoiceReactNative
//
// FORK — KAR-869 (Sentry KAREN-APP-5M). See ForkVoipPushReporter.h.
//

#import "ForkVoipPushReporter.h"
#import "ForkSentryReporter.h"
// >>> FORK KAR-891 — the fallback delegate answers pushes before the RN module
// initializes, so it needs the shared Twilio audio device.
#import "TwilioVoiceReactNative.h"
@import TwilioVoice;
// <<< FORK

// Twilio VoIP push payload keys.
static NSString * const kForkTwilioPushCallSidKey = @"twi_call_sid";
static NSString * const kForkTwilioPushFromKey = @"twi_from";

// >>> FORK KAR-891 — how long an answered-but-not-yet-bound call is held before we
// give up and end it (guards against an invite that never arrives on cold start).
static const int64_t kForkDeferredAnswerTimeoutSeconds = 10;
// <<< FORK

@interface ForkVoipPushReporter () <CXProviderDelegate>
@property (nonatomic, strong) CXProvider *provider;
@property (nonatomic, strong) CXCallController *callController;
// call SID (NSString) -> reserved NSUUID
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSUUID *> *reservationsByCallSid;
// >>> FORK KAR-891 — UUIDs the user answered / declined before the invite arrived.
@property (nonatomic, strong) NSMutableSet<NSUUID *> *pendingAnswerUUIDs;
@property (nonatomic, strong) NSMutableSet<NSUUID *> *declinedUUIDs;
// <<< FORK
@property (nonatomic, strong) NSObject *lock;
@end

@implementation ForkVoipPushReporter

+ (ForkVoipPushReporter *)sharedReporter {
    static ForkVoipPushReporter *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[ForkVoipPushReporter alloc] init];
    });
    return instance;
}

- (instancetype)init {
    if (self = [super init]) {
        _reservationsByCallSid = [NSMutableDictionary dictionary];
        // >>> FORK KAR-891
        _pendingAnswerUUIDs = [NSMutableSet set];
        _declinedUUIDs = [NSMutableSet set];
        // <<< FORK
        _lock = [NSObject new];

        CXProviderConfiguration *configuration = [self defaultConfiguration];
        _provider = [[CXProvider alloc] initWithConfiguration:configuration];
        // Fallback delegate until TwilioVoiceReactNative takes over.
        [_provider setDelegate:self queue:nil];
        _callController = [CXCallController new];
    }
    return self;
}

- (CXProviderConfiguration *)defaultConfiguration {
    CXProviderConfiguration *configuration = [CXProviderConfiguration new];
    configuration.maximumCallGroups = 1;
    configuration.maximumCallsPerCallGroup = 1;
    configuration.supportedHandleTypes =
        [NSSet setWithArray:@[@(CXHandleTypeGeneric), @(CXHandleTypePhoneNumber)]];
    return configuration;
}

#pragma mark - Synchronous reporting

- (NSUUID *)reportIncomingCallForPushPayload:(NSDictionary *)payload {
    NSString *callSid = nil;
    NSString *from = nil;
    if ([payload isKindOfClass:[NSDictionary class]]) {
        id sid = payload[kForkTwilioPushCallSidKey];
        if ([sid isKindOfClass:[NSString class]]) {
            callSid = sid;
        }
        id fromValue = payload[kForkTwilioPushFromKey];
        if ([fromValue isKindOfClass:[NSString class]]) {
            from = fromValue;
        }
    }

    // Reuse the reservation on duplicate pushes; always report something, even
    // without a call SID, or iOS kills the app.
    NSUUID *uuid = nil;
    @synchronized (self.lock) {
        if (callSid != nil) {
            uuid = self.reservationsByCallSid[callSid];
        }
        if (uuid == nil) {
            uuid = [NSUUID UUID];
            if (callSid != nil) {
                self.reservationsByCallSid[callSid] = uuid;
            }
        }
    }

    NSString *handleName = (from.length > 0) ? from : @"Incoming call";
    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = [[CXHandle alloc] initWithType:CXHandleTypeGeneric value:handleName];
    callUpdate.localizedCallerName = handleName;
    callUpdate.supportsDTMF = YES;
    callUpdate.supportsHolding = YES;
    callUpdate.supportsGrouping = NO;
    callUpdate.supportsUngrouping = NO;
    callUpdate.hasVideo = NO;

    [self.provider reportNewIncomingCallWithUUID:uuid update:callUpdate completion:^(NSError *error) {
        if (error) {
            [ForkSentryReporter fork_reportError:@"voice.callkit.synchronous_incoming_call_report_failed" cause:error];
            NSLog(@"[ForkVoipPushReporter] Failed to report incoming call synchronously: %@", error);
        }
    }];

    return uuid;
}

- (NSUUID *)reservedUUIDForCallSid:(NSString *)callSid {
    if (callSid.length == 0) {
        return nil;
    }
    @synchronized (self.lock) {
        return self.reservationsByCallSid[callSid];
    }
}

- (BOOL)hasReservationForUUID:(NSUUID *)uuid {
    if (uuid == nil) {
        return NO;
    }
    @synchronized (self.lock) {
        return [[self.reservationsByCallSid allValues] containsObject:uuid];
    }
}

- (void)clearReservationForCallSid:(NSString *)callSid {
    if (callSid.length == 0) {
        return;
    }
    @synchronized (self.lock) {
        // >>> FORK KAR-891 — drop any answer/decline intent tracked against the
        // same UUID so a released call leaves no dangling state.
        NSUUID *uuid = self.reservationsByCallSid[callSid];
        if (uuid != nil) {
            [self.pendingAnswerUUIDs removeObject:uuid];
            [self.declinedUUIDs removeObject:uuid];
        }
        // <<< FORK
        [self.reservationsByCallSid removeObjectForKey:callSid];
    }
}

#pragma mark - FORK KAR-891 — cold-start answer/decline bridging

- (void)markPendingAnswerForUUID:(NSUUID *)uuid {
    if (uuid == nil) {
        return;
    }
    @synchronized (self.lock) {
        [self.pendingAnswerUUIDs addObject:uuid];
    }

    // End the call if no invite binds within the timeout — otherwise CallKit would
    // show a silently "connected" call forever.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, kForkDeferredAnswerTimeoutSeconds * NSEC_PER_SEC),
                   dispatch_get_main_queue(), ^{
        BOOL stillPending;
        @synchronized (self.lock) {
            stillPending = [self.pendingAnswerUUIDs containsObject:uuid];
        }
        if (stillPending) {
            [ForkSentryReporter fork_reportWarning:@"voice.callkit.deferred_answer_timed_out" cause:nil];
            [self.provider reportCallWithUUID:uuid endedAtDate:[NSDate date] reason:CXCallEndedReasonFailed];
            @synchronized (self.lock) {
                [self.pendingAnswerUUIDs removeObject:uuid];
            }
        }
    });
}

- (BOOL)consumePendingAnswerForUUID:(NSUUID *)uuid {
    if (uuid == nil) {
        return NO;
    }
    @synchronized (self.lock) {
        if ([self.pendingAnswerUUIDs containsObject:uuid]) {
            [self.pendingAnswerUUIDs removeObject:uuid];
            return YES;
        }
        return NO;
    }
}

- (void)markDeclinedForUUID:(NSUUID *)uuid {
    if (uuid == nil) {
        return;
    }
    @synchronized (self.lock) {
        [self.pendingAnswerUUIDs removeObject:uuid];
        [self.declinedUUIDs addObject:uuid];
    }
}

- (BOOL)consumeDeclinedForUUID:(NSUUID *)uuid {
    if (uuid == nil) {
        return NO;
    }
    @synchronized (self.lock) {
        if ([self.declinedUUIDs containsObject:uuid]) {
            [self.declinedUUIDs removeObject:uuid];
            return YES;
        }
        return NO;
    }
}

#pragma mark - CXProviderDelegate (fallback only)

// The RN module installs itself as the provider delegate in -initializeCallKit and
// then handles every action. These methods only run in the window before that —
// when a push has been reported but JS hasn't booted yet — and just bridge the
// user's intent so the module can act on it once the invite arrives.

- (void)provider:(CXProvider *)provider performAnswerCallAction:(CXAnswerCallAction *)action {
    if ([self hasReservationForUUID:action.callUUID]) {
        [ForkSentryReporter fork_addBreadcrumb:@"voice.callkit.answer_action_before_module_init"];
        [self markPendingAnswerForUUID:action.callUUID];
        // Prime the audio device so media routes once the invite is accepted.
        [TwilioVoiceReactNative twilioAudioDevice].enabled = NO;
        [TwilioVoiceReactNative twilioAudioDevice].block();
        [action fulfill];
    } else {
        [action fail];
    }
}

- (void)provider:(CXProvider *)provider performEndCallAction:(CXEndCallAction *)action {
    [self markDeclinedForUUID:action.callUUID];
    [action fulfill];
}

- (void)provider:(CXProvider *)provider didActivateAudioSession:(AVAudioSession *)audioSession {
    [TwilioVoiceReactNative twilioAudioDevice].enabled = YES;
}

- (void)provider:(CXProvider *)provider didDeactivateAudioSession:(AVAudioSession *)audioSession {
    [TwilioVoiceReactNative twilioAudioDevice].enabled = NO;
}

- (void)providerDidReset:(CXProvider *)provider {
    @synchronized (self.lock) {
        [self.reservationsByCallSid removeAllObjects];
        // >>> FORK KAR-891
        [self.pendingAnswerUUIDs removeAllObjects];
        [self.declinedUUIDs removeAllObjects];
        // <<< FORK
    }
}

@end
