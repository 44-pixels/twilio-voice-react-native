//
//  TwilioVoiceReactNative+CallKit.m
//  TwilioVoiceReactNative
//
//  Copyright © 2022 Twilio, Inc. All rights reserved.
//

@import CallKit;
@import TwilioVoice;

#import "TwilioVoiceReactNative.h"
#import "TwilioVoiceReactNativeConstants.h"
// >>> FORK KAR-787 — see ForkCallSounds.h
#import "ForkCallSounds.h"
// <<< FORK
// >>> FORK KAR-857 — see ForkCallIssueState.h
#import "ForkCallIssueState.h"
// <<< FORK
// >>> FORK KAR-381 — see ForkE164.h
#import "ForkE164.h"
// <<< FORK
// >>> FORK KAR-873 — see ForkCallbackRequestStore
#import "ForkCallbackRequestStore.h"
// <<< FORK
// >>> FORK KAR-869 — see ForkVoipPushReporter.h
#import "ForkVoipPushReporter.h"
// <<< FORK

NSString * const kDefaultCallKitConfigurationName = @"Twilio Voice React Native";

@interface TwilioVoiceReactNative (CallKit) <CXProviderDelegate, TVOCallDelegate, AVAudioPlayerDelegate>

@end

@implementation TwilioVoiceReactNative (CallKit)

#pragma mark - CallKit helper methods

- (void)initializeCallKit {
    [self initializeCallKitWithConfiguration:nil];
}

- (void)initializeCallKitWithConfiguration:(NSDictionary *)configuration {
    // >>> FORK KAR-787 — see ForkCallSounds
    [ForkCallSounds rememberCallKitConfiguration:configuration];
    // <<< FORK
    CXProviderConfiguration *callKitConfiguration = [CXProviderConfiguration new];

    if (configuration[kTwilioVoiceReactNativeCallKitMaximumCallGroups]) {
        callKitConfiguration.maximumCallGroups = [configuration[kTwilioVoiceReactNativeCallKitMaximumCallGroups] intValue];
    } else {
        callKitConfiguration.maximumCallGroups = 1;
    }

    if (configuration[kTwilioVoiceReactNativeCallKitMaximumCallsPerCallGroup]) {
        callKitConfiguration.maximumCallsPerCallGroup = [configuration[kTwilioVoiceReactNativeCallKitMaximumCallsPerCallGroup] intValue];
    } else {
        callKitConfiguration.maximumCallsPerCallGroup = 1;
    }

    float version = [[UIDevice currentDevice].systemVersion floatValue];
    if (version > 11.0 && configuration[kTwilioVoiceReactNativeCallKitIncludesCallsInRecents]) {
        callKitConfiguration.includesCallsInRecents = [configuration[kTwilioVoiceReactNativeCallKitIncludesCallsInRecents] boolValue];
    }

    if (configuration[kTwilioVoiceReactNativeCallKitSupportedHandleTypes]) {
        NSSet *supportedHandleTypes = [NSSet setWithArray:configuration[kTwilioVoiceReactNativeCallKitSupportedHandleTypes]];
        callKitConfiguration.supportedHandleTypes = supportedHandleTypes;
    } else {
        callKitConfiguration.supportedHandleTypes = [NSSet setWithArray:@[@(CXHandleTypeGeneric), @(CXHandleTypePhoneNumber)]];
    }

    if (configuration[kTwilioVoiceReactNativeCallKitIconTemplateImageData] && [configuration[kTwilioVoiceReactNativeCallKitIconTemplateImageData] isKindOfClass:[NSString class]]) {
        UIImage *icon = [UIImage imageNamed:configuration[kTwilioVoiceReactNativeCallKitIconTemplateImageData]];
        callKitConfiguration.iconTemplateImageData = UIImagePNGRepresentation(icon);
    }

    if (configuration[kTwilioVoiceReactNativeCallKitRingtoneSound] && [configuration[kTwilioVoiceReactNativeCallKitRingtoneSound] isKindOfClass:[NSString class]]) {
        callKitConfiguration.ringtoneSound = configuration[kTwilioVoiceReactNativeCallKitRingtoneSound];
    }
    // >>> FORK KAR-787 — see ForkCallSounds
    callKitConfiguration.ringtoneSound = [ForkCallSounds ringtoneSoundOverridingDefault:callKitConfiguration.ringtoneSound];
    // <<< FORK

    // >>> FORK KAR-869 — adopt the shared provider (so a
    // call reported from the push handler is answerable here) instead of a second one.
    ForkVoipPushReporter *reporter = [ForkVoipPushReporter sharedReporter];
    if (@available(iOS 14.0, *)) {
        reporter.provider.configuration = callKitConfiguration;
    }
    self.callKitProvider = reporter.provider;
    [self.callKitProvider setDelegate:self queue:nil];
    self.callKitCallController = reporter.callController;
    // <<< FORK
}

- (NSString *)getDisplayName:(NSString *)template
            customParameters:(NSDictionary<NSString *, NSString *> *)customParameters {
    NSString *processedTemplate = template;
    for (NSString *paramKey in customParameters) {
        NSString *paramValue = customParameters[paramKey];
        NSString *wrappedParamKey = [NSString stringWithFormat:@"${%@}", paramKey];
        processedTemplate = [processedTemplate stringByReplacingOccurrencesOfString:wrappedParamKey withString:paramValue];
    }
    return processedTemplate;
}

- (void)reportNewIncomingCall:(TVOCallInvite *)callInvite {
    NSString *handleName = callInvite.from;
    if (handleName == nil) {
        handleName = @"Unknown Caller";
    }

    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSString *preferenceKey = @"incomingCallContactHandleTemplate";
    if ([preferences objectForKey:preferenceKey] != nil) {
        const NSString *preferenceVal = [preferences stringForKey:preferenceKey];
        if ([preferenceVal length] > 0) {
            handleName = [self getDisplayName:preferenceVal customParameters:[callInvite customParameters]];
        }
    }

    // >>> FORK KAR-381 — see ForkE164.h
    // If the template result is E.164 → use it as PhoneNumber handle (Contacts lookup runs).
    // Else (template gave a name like "Dave") → fall back to callInvite.from for the handle
    // when it's E.164, and surface the name via localizedCallerName as Contacts-miss fallback.
    BOOL forkHandleIsE164 = ForkIsE164PhoneNumber(handleName);
    NSString *forkPhoneFallback = callInvite.from;
    BOOL forkFallbackIsE164 = !forkHandleIsE164 && ForkIsE164PhoneNumber(forkPhoneFallback);
    CXHandleType forkHandleType = (forkHandleIsE164 || forkFallbackIsE164) ? CXHandleTypePhoneNumber : CXHandleTypeGeneric;
    NSString *forkHandleValue = forkHandleIsE164 ? handleName : (forkFallbackIsE164 ? forkPhoneFallback : handleName);
    CXHandle *callHandle = [[CXHandle alloc] initWithType:forkHandleType value:forkHandleValue];
    // >>> FORK KAR-873 — see ForkCallbackRequestStore
    [ForkCallbackRequestStore fork_rememberHandle:forkHandleValue destination:callInvite.from];
    // <<< FORK

    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = callHandle;
    if (!forkHandleIsE164) {
        callUpdate.localizedCallerName = handleName;
    }
    // <<< FORK
    callUpdate.supportsDTMF = YES;
    callUpdate.supportsHolding = YES;
    callUpdate.supportsGrouping = NO;
    callUpdate.supportsUngrouping = NO;
    callUpdate.hasVideo = NO;

    // >>> FORK KAR-869 — if the push was already reported
    // synchronously, enrich that call instead of reporting a second one.
    NSUUID *reservedUuid = [[ForkVoipPushReporter sharedReporter] reservedUUIDForCallSid:callInvite.callSid];
    if (reservedUuid != nil) {
        [self.callKitProvider reportCallWithUUID:reservedUuid updated:callUpdate];
    } else {
        [self.callKitProvider reportNewIncomingCallWithUUID:callInvite.uuid update:callUpdate completion:^(NSError *error) {
            if (!error) {
                NSLog(@"Incoming call successfully reported.");
            } else {
                NSLog(@"Failed to report incoming call: %@.", error);
            }
        }];
    }
    // <<< FORK
}

// >>> FORK KAR-869
- (NSUUID *)effectiveUUIDForCallInvite:(TVOCallInvite *)callInvite {
    NSUUID *reservedUuid = [[ForkVoipPushReporter sharedReporter] reservedUUIDForCallSid:callInvite.callSid];
    return reservedUuid != nil ? reservedUuid : callInvite.uuid;
}
// <<< FORK

- (void)answerCallInvite:(NSUUID *)uuid
              completion:(void(^)(BOOL success, NSError *error))completionHandler {
    self.callKitCompletionCallback = completionHandler;
    CXAnswerCallAction *answerCallAction = [[CXAnswerCallAction alloc] initWithCallUUID:uuid];
    CXTransaction *transaction = [[CXTransaction alloc] initWithAction:answerCallAction];

    [self.callKitCallController requestTransaction:transaction completion:^(NSError *error) {
        if (error) {
            NSLog(@"Failed to submit answer-call transaction request: %@", error);
        } else {
            NSLog(@"Answer-call transaction successfully done");
        }
    }];
}

- (void)endCallWithUuid:(NSUUID *)uuid {
    CXEndCallAction *endCallAction = [[CXEndCallAction alloc] initWithCallUUID:uuid];
    CXTransaction *transaction = [[CXTransaction alloc] initWithAction:endCallAction];

    [self.callKitCallController requestTransaction:transaction completion:^(NSError *error) {
        if (error) {
            NSLog(@"Failed to submit end-call transaction request: %@", error);
        } else {
            NSLog(@"End-call transaction successfully done");
        }
    }];
}

- (void)makeCallWithAccessToken:(NSString *)accessToken
                         params:(NSDictionary *)params
                  contactHandle:(NSString *)contactHandle {
    self.accessToken = accessToken;
    self.twimlParams = params;

    NSString *handle = @"Default Contact";
    if ([contactHandle length] > 0) {
        handle = contactHandle;
    }

    CXHandle *callHandle = [[CXHandle alloc] initWithType:CXHandleTypeGeneric value:handle];
    // >>> FORK KAR-873 — see ForkCallbackRequestStore
    [ForkCallbackRequestStore fork_rememberHandle:handle destination:self.twimlParams[@"to"]];
    // <<< FORK
    NSUUID *uuid = [NSUUID UUID];
    CXStartCallAction *startCallAction = [[CXStartCallAction alloc] initWithCallUUID:uuid handle:callHandle];
    CXTransaction *transaction = [[CXTransaction alloc] initWithAction:startCallAction];

    [self.callKitCallController requestTransaction:transaction completion:^(NSError *error) {
        if (error) {
            NSLog(@"StartCallAction transaction request failed: %@", [error localizedDescription]);
        } else {
            NSLog(@"StartCallAction transaction request successful");

            CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];

            callUpdate.remoteHandle = callHandle;
            callUpdate.supportsDTMF = YES;
            callUpdate.supportsHolding = YES;
            callUpdate.supportsGrouping = NO;
            callUpdate.supportsUngrouping = NO;
            callUpdate.hasVideo = NO;

            [self.callKitProvider reportCallWithUUID:uuid updated:callUpdate];
        }
    }];
}

- (void)performVoiceCallWithUUID:(NSUUID *)uuid
                          client:(NSString *)client
                      completion:(void(^)(BOOL success, NSError *error))completionHandler {
    TVOConnectOptions *connectOptions = [TVOConnectOptions optionsWithAccessToken:self.accessToken block:^(TVOConnectOptionsBuilder *builder) {
        builder.params = self.twimlParams;
        builder.uuid = uuid;
        builder.callMessageDelegate = self;
    }];
    TVOCall *call = [TwilioVoiceSDK connectWithOptions:connectOptions delegate:self];
    if (call) {
        self.callMap[call.uuid.UUIDString] = call;
        [self resolvePromise:self.callPromiseResolver value:[self callInfo:call]];
    }
    self.callKitCompletionCallback = completionHandler;
}

- (void)performAnswerVoiceCallWithUUID:(NSUUID *)uuid
                            completion:(void(^)(BOOL success))completionHandler {
    NSAssert(self.callInviteMap[uuid.UUIDString], @"No call invite");

    TVOCallInvite *callInvite = self.callInviteMap[uuid.UUIDString];
    TVOAcceptOptions *acceptOptions = [TVOAcceptOptions optionsWithCallInvite:callInvite block:^(TVOAcceptOptionsBuilder *builder) {
        builder.uuid = uuid;
        builder.callMessageDelegate = self;
    }];

    TVOCall *call = [callInvite acceptWithOptions:acceptOptions delegate:self];

    if (!call) {
        completionHandler(NO);
    } else {
        self.callMap[call.uuid.UUIDString] = call;
    }

    [self sendEventWithName:kTwilioVoiceReactNativeScopeCallInvite
                       body:@{
                         kTwilioVoiceReactNativeCallInviteEventKeyType: kTwilioVoiceReactNativeCallInviteEventTypeValueAccepted,
                         kTwilioVoiceReactNativeCallInviteEventKeyCallSid: callInvite.callSid,
                         kTwilioVoiceReactNativeEventKeyCallInvite: [self callInviteInfo:callInvite]}];
}

- (void)updateCall:(NSString *)uuid callerHandle:(NSString *)handle {
    CXHandle *callHandle = [[CXHandle alloc] initWithType:CXHandleTypeGeneric value:handle];
    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = callHandle;
    callUpdate.localizedCallerName = handle;
    callUpdate.supportsDTMF = YES;
    callUpdate.supportsHolding = YES;
    callUpdate.supportsGrouping = NO;
    callUpdate.supportsUngrouping = NO;
    callUpdate.hasVideo = NO;

    dispatch_async(dispatch_get_main_queue(), ^{
        [self.callKitProvider reportCallWithUUID:[[NSUUID alloc] initWithUUIDString:uuid] updated:callUpdate];
    });
}

#pragma mark - CXProviderDelegate

- (void)providerDidReset:(CXProvider *)provider {
    [TwilioVoiceReactNative twilioAudioDevice].enabled = NO;
}

- (void)providerDidBegin:(CXProvider *)provider {

}

- (void)provider:(CXProvider *)provider didActivateAudioSession:(AVAudioSession *)audioSession {
    [TwilioVoiceReactNative twilioAudioDevice].enabled = YES;
}

- (void)provider:(CXProvider *)provider didDeactivateAudioSession:(AVAudioSession *)audioSession {
    [TwilioVoiceReactNative twilioAudioDevice].enabled = NO;
}

- (void)provider:(CXProvider *)provider performEndCallAction:(CXEndCallAction *)action {
    if (self.callMap[action.callUUID.UUIDString]) {
        TVOCall *call = self.callMap[action.callUUID.UUIDString];
        [call disconnect];
    } else if (self.callInviteMap[action.callUUID.UUIDString]) {
        TVOCallInvite *callInvite = self.callInviteMap[action.callUUID.UUIDString];
        [callInvite reject];
        [self sendEventWithName:kTwilioVoiceReactNativeScopeCallInvite
                           body:@{
                             kTwilioVoiceReactNativeCallInviteEventKeyType: kTwilioVoiceReactNativeCallInviteEventTypeValueRejected,
                             kTwilioVoiceReactNativeCallInviteEventKeyCallSid: callInvite.callSid,
                             kTwilioVoiceReactNativeEventKeyCallInvite: [self callInviteInfo:callInvite]}];
        [self.callInviteMap removeObjectForKey:action.callUUID.UUIDString];
        // >>> FORK KAR-869 — invite rejected: release its push reservation
        [[ForkVoipPushReporter sharedReporter] clearReservationForCallSid:callInvite.callSid];
        // <<< FORK
    }

    [action fulfill];
}

- (void)provider:(CXProvider *)provider performStartCallAction:(CXStartCallAction *)action {
    [TwilioVoiceReactNative twilioAudioDevice].enabled = NO;
    [TwilioVoiceReactNative twilioAudioDevice].block();

    [self.callKitProvider reportOutgoingCallWithUUID:action.callUUID startedConnectingAtDate:[NSDate date]];

    __weak typeof(self) weakSelf = self;
    [self performVoiceCallWithUUID:action.callUUID client:nil completion:^(BOOL success, NSError *error) {
        __strong typeof(self) strongSelf = weakSelf;
        if (success) {
            NSLog(@"performVoiceCallWithUUID successful");
            [strongSelf.callKitProvider reportOutgoingCallWithUUID:action.callUUID connectedAtDate:[NSDate date]];
        } else {
            NSLog(@"performVoiceCallWithUUID failed");
        }
    }];

    [action fulfill];
}

- (void)provider:(CXProvider *)provider performAnswerCallAction:(CXAnswerCallAction *)action {
    // >>> FORK KAR-310 — fail action if invite already cancelled (answer-after-cancel race)
    if (!self.callInviteMap[action.callUUID.UUIDString]) { [action fail]; return; }
    // <<< FORK
    [TwilioVoiceReactNative twilioAudioDevice].enabled = NO;
    [TwilioVoiceReactNative twilioAudioDevice].block();

    // >>> FORK KAR-310 — capture failure to propagate to CXAction.
    // Assumes upstream completion is invoked synchronously; verify on SDK bump.
    __block BOOL forkAnswerFailed = NO;
    // <<< FORK
    [self performAnswerVoiceCallWithUUID:action.callUUID completion:^(BOOL success) {
        if (success) {
            NSLog(@"performAnswerVoiceCallWithUUID successful");
        } else {
            NSLog(@"performAnswerVoiceCallWithUUID failed");
            // >>> FORK KAR-310
            forkAnswerFailed = YES;
            // <<< FORK
        }
    }];

    // >>> FORK KAR-310 — was: [action fulfill];
    if (forkAnswerFailed) { [action fail]; } else { [action fulfill]; }
    // <<< FORK
}

- (void)provider:(CXProvider *)provider performSetHeldCallAction:(CXSetHeldCallAction *)action {
    if (self.callMap[action.callUUID.UUIDString]) {
        TVOCall *call = self.callMap[action.callUUID.UUIDString];
        [call setOnHold:action.isOnHold];
        [action fulfill];
    } else {
        [action fail];
    }
}

- (void)provider:(CXProvider *)provider performSetMutedCallAction:(CXSetMutedCallAction *)action {
    if (self.callMap[action.callUUID.UUIDString]) {
        TVOCall *call = self.callMap[action.callUUID.UUIDString];
        [call setMuted:action.isMuted];
        [action fulfill];
    } else {
        [action fail];
    }
}

- (void)provider:(CXProvider *)provider performPlayDTMFCallAction:(CXPlayDTMFCallAction *)action {
    if (self.callMap[action.callUUID.UUIDString]) {
        TVOCall *call = self.callMap[action.callUUID.UUIDString];
        [call sendDigits:action.digits];
        [action fulfill];
    } else {
        [action fail];
    }
}

#pragma mark - TVOCallDelegate

- (void)callDidStartRinging:(TVOCall *)call {
    [self playRingback];

    [self sendEventWithName:kTwilioVoiceReactNativeScopeCall
                       body:@{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventRinging,
                              kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call]}];
}

- (void)callDidConnect:(TVOCall *)call {
    self.callConnectMap[call.uuid.UUIDString] = [self getSimplifiedISO8601FormattedTimestamp:[NSDate date]];

    [self stopRingback];
    // >>> FORK KAR-857 — see ForkCallIssueState.h
    [ForkCallIssueState fork_connectedCall:call.uuid];
    // <<< FORK

    [self sendEventWithName:kTwilioVoiceReactNativeScopeCall
                       body:@{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventConnected,
                              kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call]}];

    if (self.callKitCompletionCallback) {
        self.callKitCompletionCallback(YES, nil);
        self.callKitCompletionCallback = nil;
    }
}

- (void)call:(TVOCall *)call didDisconnectWithError:(NSError *)error {
    // >>> FORK KAR-857 — see ForkCallIssueState.h
    [ForkCallIssueState fork_endedCall:call.uuid];
    // <<< FORK
    // >>> FORK KAR-787 — see ForkCallSounds
    [ForkCallSounds fork_playCallEndedSoundForCall:call.uuid];
    // <<< FORK
    NSDictionary *messageBody = [NSDictionary dictionary];
    if (error) {
        messageBody = @{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventDisconnected,
                        kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call],
                        kTwilioVoiceReactNativeVoiceErrorKeyError: @{kTwilioVoiceReactNativeVoiceErrorKeyCode: @(error.code),
                                                                     kTwilioVoiceReactNativeVoiceErrorKeyMessage: [error localizedDescription]}};
    } else {
        messageBody = @{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventDisconnected,
                        kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call]};
    }

    [self sendEventWithName:kTwilioVoiceReactNativeScopeCall body:messageBody];

    if (!self.userInitiatedDisconnect) {
        CXCallEndedReason reason = CXCallEndedReasonRemoteEnded;
        if (error) {
            reason = CXCallEndedReasonFailed;
        }

        [self.callKitProvider reportCallWithUUID:call.uuid endedAtDate:[NSDate date] reason:reason];
    }

    [self callDisconnected:call];
}

- (void)call:(TVOCall *)call didFailToConnectWithError:(NSError *)error {
    // >>> FORK KAR-857 — see ForkCallIssueState.h
    [ForkCallIssueState fork_endedCall:call.uuid];
    // <<< FORK
    // >>> FORK KAR-787 — see ForkCallSounds
    [ForkCallSounds fork_playCallEndedSoundForCall:call.uuid];
    // <<< FORK
    [self sendEventWithName:kTwilioVoiceReactNativeScopeCall
                       body:@{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventConnectFailure,
                              kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call],
                              kTwilioVoiceReactNativeVoiceErrorKeyError: @{kTwilioVoiceReactNativeVoiceErrorKeyCode: @(error.code),
                                                                           kTwilioVoiceReactNativeVoiceErrorKeyMessage: [error localizedDescription]}}];

    if (self.callKitCompletionCallback) {
        self.callKitCompletionCallback(NO, error);
        self.callKitCompletionCallback = nil;
    }
    [self.callKitProvider reportCallWithUUID:call.uuid endedAtDate:[NSDate date] reason:CXCallEndedReasonFailed];

    [self callDisconnected:call];
}

- (void)callDisconnected:(TVOCall *)call {
    for (NSString *uuidKey in [self.callMap allKeys]) {
        TVOCall *activeCall = self.callMap[uuidKey];
        if (activeCall == call) {
            [self.callMap removeObjectForKey:uuidKey];
            [self.callConnectMap removeObjectForKey:call.uuid.UUIDString];
            break;
        }
    }

    // Remove the corresponding call invite only when the incoming call is finished.
    [self.callInviteMap removeObjectForKey:call.uuid.UUIDString];

    [self stopRingback];
    self.userInitiatedDisconnect = NO;

    // >>> FORK KAR-874 — hold the call in a background block so its final release
    // (and -[TVOCall dealloc]'s blocking rtc teardown, rtc::Event::Wait) happens
    // off the main thread instead of freezing the UI on disconnect/connect-failure.
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{ (void)call; });
    // <<< FORK
}

- (void)call:(TVOCall *)call isReconnectingWithError:(NSError *)error {
    // >>> FORK KAR-857 — see ForkCallIssueState.h
    [ForkCallIssueState fork_reconnectingCall:call.uuid];
    // <<< FORK
    [self sendEventWithName:kTwilioVoiceReactNativeScopeCall
                       body:@{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventReconnecting,
                              kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call],
                              kTwilioVoiceReactNativeVoiceErrorKeyError: @{kTwilioVoiceReactNativeVoiceErrorKeyCode: @(error.code),
                                                                           kTwilioVoiceReactNativeVoiceErrorKeyMessage: [error localizedDescription]}}];
}

- (void)callDidReconnect:(TVOCall *)call {
    // >>> FORK KAR-857 — see ForkCallIssueState.h
    [ForkCallIssueState fork_reconnectedCall:call.uuid];
    // <<< FORK
    [self sendEventWithName:kTwilioVoiceReactNativeScopeCall
                       body:@{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventReconnected,
                              kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call]}];
}

- (void)call:(TVOCall *)call
didReceiveQualityWarnings:(NSSet<NSNumber *> *)currentWarnings
previousWarnings:(NSSet<NSNumber *> *)previousWarnings {
    // >>> FORK KAR-857 — see ForkCallIssueState.h
    [ForkCallIssueState fork_call:call.uuid qualityWarningsChanged:currentWarnings];
    // <<< FORK
    NSMutableArray<NSString *> *currentWarningEvents = [NSMutableArray array];
    for (NSNumber *warning in currentWarnings) {
        NSString *event = [self warningNameWithNumber:warning];
        [currentWarningEvents addObject:event];
    }

    NSMutableArray<NSString *> *previousWarningEvents = [NSMutableArray array];
    for (NSNumber *warning in previousWarnings) {
        NSString *event = [self warningNameWithNumber:warning];
        [previousWarningEvents addObject:event];
    }

    [self sendEventWithName:kTwilioVoiceReactNativeScopeCall
                       body:@{kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallEventQualityWarningsChanged,
                              kTwilioVoiceReactNativeEventKeyCall: [self callInfo:call],
                              kTwilioVoiceReactNativeCallEventCurrentWarnings: currentWarningEvents,
                              kTwilioVoiceReactNativeCallEventPreviousWarnings: previousWarningEvents}];
}

#pragma mark - Ringback

- (void)playRingback {
    NSString *ringtonePath = [[NSBundle mainBundle] pathForResource:@"ringtone" ofType:@"wav"];
    if ([ringtonePath length] <= 0) {
        NSLog(@"Can't find sound file");
        return;
    }

    NSError *error;
    self.ringbackPlayer = [[AVAudioPlayer alloc] initWithContentsOfURL:[NSURL URLWithString:ringtonePath] error:&error];
    if (error != nil) {
        NSLog(@"Failed to initialize audio player: %@", error);
    } else {
        self.ringbackPlayer.delegate = self;
        self.ringbackPlayer.numberOfLoops = -1;

        self.ringbackPlayer.volume = 1.0f;
        [self.ringbackPlayer play];
    }
}

- (void)stopRingback {
    if (!self.ringbackPlayer.isPlaying) {
        return;
    }

    [self.ringbackPlayer stop];
}

- (void)audioPlayerDidFinishPlaying:(AVAudioPlayer *)player successfully:(BOOL)flag {
    if (flag) {
        NSLog(@"Audio player finished playing successfully");
    } else {
        NSLog(@"Audio player finished playing with some error");
    }
}

- (void)audioPlayerDecodeErrorDidOccur:(AVAudioPlayer *)player error:(NSError *)error {
    NSLog(@"Decode error occurred: %@", error);
}

#pragma mark - Warning event conversion

- (NSString *)getSimplifiedISO8601FormattedTimestamp:(NSDate *)date {
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    NSLocale *locale = [NSLocale currentLocale];
    [formatter setLocale:locale];
    [formatter setDateFormat:@"yyyy'-'MM'-'dd'T'HH':'mm':'ss'.'SSSZ"];

    return [formatter stringFromDate:date];
}

@end
