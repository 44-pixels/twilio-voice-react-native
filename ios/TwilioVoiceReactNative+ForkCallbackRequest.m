// FORK — KAR-873
// Owns: React Native bridge methods for durable CallKit callback requests.
// Hooks into: TwilioVoiceReactNative and ForkCallbackRequestStore.
// Re-check on SDK bump: RCT promise-export conventions.

#import "ForkCallbackRequestStore.h"
#import "TwilioVoiceReactNative+ForkCallbackRequest.h"
#import "TwilioVoiceReactNativeConstants.h"

@implementation TwilioVoiceReactNative (ForkCallbackRequest)

- (void)fork_handleCallbackRequestNotification:(NSNotification *)notification {
    NSDictionary *request = notification.userInfo;
    if (request[kTwilioVoiceReactNativeCallbackRequestKeyRequestId] == nil ||
        request[kTwilioVoiceReactNativeCallbackRequestKeyHandle] == nil) return;

    [self sendEventWithName:kTwilioVoiceReactNativeScopeVoice
                       body:@{
                         kTwilioVoiceReactNativeVoiceEventType:
                           kTwilioVoiceReactNativeVoiceEventCallbackRequested,
                         kTwilioVoiceReactNativeCallbackRequestKeyRequestId:
                           request[kTwilioVoiceReactNativeCallbackRequestKeyRequestId],
                         kTwilioVoiceReactNativeCallbackRequestKeyHandle:
                           request[kTwilioVoiceReactNativeCallbackRequestKeyHandle],
                       }];
}

RCT_REMAP_METHOD(voice_getInitialCallbackRequest,
                 fork_voice_getInitialCallbackRequest:(RCTPromiseResolveBlock)resolver
                 rejecter:(RCTPromiseRejectBlock)rejecter)
{
    NSDictionary *request = [ForkCallbackRequestStore fork_pendingRequest];
    [self resolvePromise:resolver value:request ?: [NSNull null]];
}

RCT_REMAP_METHOD(voice_clearCallbackRequest,
                 fork_voice_clearCallbackRequest:(NSString *)requestId
                 resolver:(RCTPromiseResolveBlock)resolver
                 rejecter:(RCTPromiseRejectBlock)rejecter)
{
    [ForkCallbackRequestStore fork_clearPendingRequestWithId:requestId];
    [self resolvePromise:resolver value:[NSNull null]];
}

@end
