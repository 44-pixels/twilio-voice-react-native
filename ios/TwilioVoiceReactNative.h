//
//  TwilioVoiceReactNative.h
//  TwilioVoiceReactNative
//
//  Copyright © 2022 Twilio, Inc. All rights reserved.
//

#import <AVFoundation/AVFoundation.h>

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@class CXCallController;
@class CXProvider;
@class TVOCall;
@class TVOCallInvite;
@class TVOCancelledCallInvite;
@class TVODefaultAudioDevice;
@class TVOPreflightTest;

FOUNDATION_EXPORT NSString * const kTwilioVoiceReactNativeEventKeyCall;
FOUNDATION_EXPORT NSString * const kTwilioVoiceReactNativeEventKeyCallInvite;
FOUNDATION_EXPORT NSString * const kTwilioVoiceReactNativeEventKeyCancelledCallInvite;

@interface TwilioVoiceReactNative : RCTEventEmitter <RCTBridgeModule>

@property (nonatomic, readonly, strong) NSMutableDictionary<NSString *, TVOCall *> *callMap;
@property (nonatomic, readonly, strong) NSMutableDictionary<NSString *, NSString *> *callConnectMap;
@property (nonatomic, readonly, strong) NSMutableDictionary<NSString *, TVOCallInvite *> *callInviteMap;
@property (nonatomic, readonly, strong) NSMutableDictionary<NSString *, TVOCancelledCallInvite *> *cancelledCallInviteMap;

@property (nonatomic, strong) CXProvider *callKitProvider;
@property (nonatomic, strong) CXCallController *callKitCallController;

@property (nonatomic, copy) NSString *accessToken;
@property (nonatomic, copy) NSDictionary *twimlParams;
@property (nonatomic, strong) void(^callKitCompletionCallback)(BOOL, NSError *error);
@property (nonatomic, strong) RCTPromiseResolveBlock callPromiseResolver;

@property (nonatomic, strong) TVOPreflightTest *preflightTest;
@property (nonatomic, copy) NSString *preflightTestUuid;
@property (nonatomic, strong) NSMutableArray *preflightTestEvents;

// Indicates if the disconnect is triggered from app UI, instead of the system Call UI
@property (nonatomic, assign) BOOL userInitiatedDisconnect;

@property (nonatomic, strong) AVAudioPlayer *ringbackPlayer;
// >>> FORK KAR-882 — true while ringing, so ringback can be (re)started once the CallKit audio session activates.
@property (nonatomic, assign) BOOL ringbackActive;
// <<< FORK

// >>> FORK KAR-891 — UUID of an answered inbound call whose accept is waiting for CallKit
// to activate the audio session (see -performAnswerCallAction: / -didActivateAudioSession:).
// On cold start the call is answerable before the media path is ready; accepting into a
// not-ready session lets the call be torn down (caller Decline, backend no_answer).
// Declared in the main interface (not the (CallKit) category) so it is auto-synthesized —
// a category property has no ivar storage and would crash with an unrecognized selector.
@property (nonatomic, strong, nullable) NSUUID *forkPendingAudioAcceptUuid;
// <<< FORK

+ (TVODefaultAudioDevice *)twilioAudioDevice;

- (NSString *)warningNameWithNumber:(NSNumber *)warning;
- (NSMutableArray *)callQualityWarningsArrayFromSet:(NSSet<NSNumber *> *)qualityWarnings;

@end

@interface TwilioVoiceReactNative (EventEmitter)

// Override so we can check the event observer before emitting events
- (void)sendEventWithName:(NSString *)eventName body:(id)body;

@end

@interface TwilioVoiceReactNative (CallKit)

- (void)initializeCallKit;
- (void)initializeCallKitWithConfiguration:(NSDictionary *)configuration;
- (void)makeCallWithAccessToken:(NSString *)accessToken
                         params:(NSDictionary *)params
                  contactHandle:(NSString *)contactHandle;
- (void)reportNewIncomingCall:(TVOCallInvite *)callInvite;
- (void)endCallWithUuid:(NSUUID *)uuid;
/* Initiate the answering from the app UI */
- (void)answerCallInvite:(NSUUID *)uuid
              completion:(void(^)(BOOL success, NSError *error))completionHandler;
- (void)updateCall:(NSString *)uuid callerHandle:(NSString *)handle;

/* Utility */
- (NSDictionary *)callInfo:(TVOCall *)call;
- (NSDictionary *)callInviteInfo:(TVOCallInvite *)callInvite;
- (NSDictionary *)cancelledCallInviteInfo:(TVOCancelledCallInvite *)cancelledCallInvite;

// >>> FORK KAR-869 — CallKit UUID for an invite: the UUID reserved at push time (see
// ForkVoipPushReporter), else the invite's own. Single identity for the call.
- (NSUUID *)effectiveUUIDForCallInvite:(TVOCallInvite *)callInvite;
// <<< FORK

// >>> FORK KAR-891 — exposed so -callInviteReceived: can accept an invite that the user
// answered from the CallKit UI during cold start, before the invite arrived.
- (void)performAnswerVoiceCallWithUUID:(NSUUID *)uuid
                            completion:(void(^)(BOOL success))completionHandler;

@end

@interface TwilioVoiceReactNative (PromiseAdapter)

- (void)resolvePromise:(RCTPromiseResolveBlock)resolver value:(id)value;
- (void)rejectPromiseWithCode:(RCTPromiseResolveBlock)resolver code:(NSNumber *)code message:(NSString *)message;
- (void)rejectPromiseWithName:(RCTPromiseResolveBlock)resolver name:(NSString *)name message:(NSString *)message;

@end
