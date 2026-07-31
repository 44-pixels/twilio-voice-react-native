//
//  TwilioVoiceReactNative+CallInvite.m
//  TwilioVoiceReactNative
//
//  Copyright © 2023 Twilio, Inc. All rights reserved.
//

@import TwilioVoice;

#import "TwilioVoiceReactNative.h"
#import "TwilioVoiceReactNativeConstants.h"
// >>> FORK KAR-878 — see ForkSentryReporter.h
#import "ForkSentryReporter.h"
// <<< FORK
// >>> FORK KAR-310 — see TwilioVoiceReactNative+Handle.m
#import "TwilioVoiceReactNative+Handle.h"
// <<< FORK
// >>> FORK KAR-869 — see ForkVoipPushReporter.h
#import "ForkVoipPushReporter.h"
// <<< FORK

@interface TwilioVoiceReactNative (CallInvite) <TVONotificationDelegate>

@end

@implementation TwilioVoiceReactNative (CallInvite)

- (void)callInviteReceived:(TVOCallInvite *)callInvite {
    // >>> FORK KAR-878 — see ForkSentryReporter.h
    [ForkSentryReporter fork_addBreadcrumb:@"voice.call_invite.received"];
    // <<< FORK
    // >>> FORK KAR-869 — key by the effective UUID (the one reported to CallKit) so
    // answer/end actions and JS resolve to this invite.
    self.callInviteMap[[self effectiveUUIDForCallInvite:callInvite].UUIDString] = callInvite;

    [self reportNewIncomingCall:callInvite];

    [self sendEventWithName:kTwilioVoiceReactNativeScopeVoice
                       body:@{
                         kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeVoiceEventTypeValueIncomingCallInvite,
                         kTwilioVoiceReactNativeEventKeyCallInvite: [self callInviteInfo:callInvite]}];
}

- (void)cancelledCallInviteReceived:(TVOCancelledCallInvite *)cancelledCallInvite error:(NSError *)error {
    // >>> FORK KAR-878 — see ForkSentryReporter.h
    [ForkSentryReporter fork_addBreadcrumb:@"voice.call_invite.cancelled"];
    // <<< FORK
    NSString *uuid;
    for (NSString *uuidKey in [self.callInviteMap allKeys]) {
        TVOCallInvite *callInvite = self.callInviteMap[uuidKey];
        if ([callInvite.callSid isEqualToString:cancelledCallInvite.callSid]) {
            uuid = uuidKey;
            break;
        }
    }
    // >>> FORK KAR-878 — see ForkSentryReporter.h
    if (!uuid) { [ForkSentryReporter fork_reportError:@"voice.call_invite.cancelled_without_match" cause:nil]; return; }
    // <<< FORK
    NSAssert(uuid, @"No matching call invite");
    self.cancelledCallInviteMap[uuid] = cancelledCallInvite;

    [self sendEventWithName:kTwilioVoiceReactNativeScopeCallInvite
                       body:@{
                         kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeCallInviteEventTypeValueCancelled,
                         kTwilioVoiceReactNativeCallInviteEventKeyCallSid: cancelledCallInvite.callSid,
                         kTwilioVoiceReactNativeEventKeyCancelledCallInvite: [self cancelledCallInviteInfo:cancelledCallInvite],
                         kTwilioVoiceReactNativeVoiceErrorKeyError: @{
                           kTwilioVoiceReactNativeVoiceErrorKeyCode: @(error.code),
                           kTwilioVoiceReactNativeVoiceErrorKeyMessage: [error localizedDescription]}}];

    // >>> FORK KAR-874 — release the invite off the main thread. -[TVOCallInvite
    // dealloc] does a blocking cross-thread rtc teardown (rtc::Event::Wait) that
    // otherwise freezes the UI for seconds when a ringing invite is cancelled.
    TVOCallInvite *forkCancelledInvite = self.callInviteMap[uuid];
    [self.callInviteMap removeObjectForKey:uuid];
    if (forkCancelledInvite) {
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{ (void)forkCancelledInvite; });
    }
    // <<< FORK

    // >>> FORK KAR-869 — invite cancelled: release its push reservation
    [[ForkVoipPushReporter sharedReporter] clearReservationForCallSid:cancelledCallInvite.callSid];
    // <<< FORK

    // >>> FORK KAR-310 — see TwilioVoiceReactNative+Handle.m
    [self fork_dismissIncomingCallUI:[[NSUUID alloc] initWithUUIDString:uuid]];
    // <<< FORK
}

@end
