//
//  ForkVoipPushReporter.m
//  TwilioVoiceReactNative
//
// FORK — KAR-869 (Sentry KAREN-APP-5M). See ForkVoipPushReporter.h.
//

#import "ForkVoipPushReporter.h"

// Twilio VoIP push payload keys.
static NSString * const kForkTwilioPushCallSidKey = @"twi_call_sid";
static NSString * const kForkTwilioPushFromKey = @"twi_from";

@interface ForkVoipPushReporter () <CXProviderDelegate>
@property (nonatomic, strong) CXProvider *provider;
@property (nonatomic, strong) CXCallController *callController;
// call SID (NSString) -> reserved NSUUID
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSUUID *> *reservationsByCallSid;
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

- (void)clearReservationForCallSid:(NSString *)callSid {
    if (callSid.length == 0) {
        return;
    }
    @synchronized (self.lock) {
        [self.reservationsByCallSid removeObjectForKey:callSid];
    }
}

#pragma mark - CXProviderDelegate (fallback only)

- (void)providerDidReset:(CXProvider *)provider {
    @synchronized (self.lock) {
        [self.reservationsByCallSid removeAllObjects];
    }
}

@end
