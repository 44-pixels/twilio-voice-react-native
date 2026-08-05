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
    NSUUID *effectiveUuid = [self effectiveUUIDForCallInvite:callInvite];
    NSLog(@"KAR891DBG callInviteReceived callSid=%@ effectiveUuid=%@ inviteUuid=%@", callInvite.callSid, effectiveUuid.UUIDString, callInvite.uuid.UUIDString);

    // >>> FORK KAR-891 — the user ended the CallKit call during cold start, before this
    // invite arrived. Reject it instead of binding/ringing.
    if ([[ForkVoipPushReporter sharedReporter] consumeDeclinedForUUID:effectiveUuid]) {
        [ForkSentryReporter fork_addBreadcrumb:@"voice.call_invite.declined_before_arrival"];
        [callInvite reject];
        [[ForkVoipPushReporter sharedReporter] clearReservationForCallSid:callInvite.callSid];
        // Release off the main thread — -[TVOCallInvite dealloc] blocks on rtc teardown (KAR-874).
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{ (void)callInvite; });
        return;
    }
    // <<< FORK

    self.callInviteMap[effectiveUuid.UUIDString] = callInvite;

    [self reportNewIncomingCall:callInvite];

    [self sendEventWithName:kTwilioVoiceReactNativeScopeVoice
                       body:@{
                         kTwilioVoiceReactNativeVoiceEventType: kTwilioVoiceReactNativeVoiceEventTypeValueIncomingCallInvite,
                         kTwilioVoiceReactNativeEventKeyCallInvite: [self callInviteInfo:callInvite]}];

    // >>> FORK KAR-891 — the user already answered from the CallKit UI during cold start,
    // before this invite arrived. Accept it now. Ordering matters: the JS incoming-invite
    // event above is sent first so InboundCallsService attaches its Accepted listener
    // before the accept below fires it.
    if ([[ForkVoipPushReporter sharedReporter] consumePendingAnswerForUUID:effectiveUuid]) {
        NSLog(@"KAR891DBG callInviteReceived -> invite arrived for an already-answered call, accepting now uuid=%@", effectiveUuid.UUIDString);
        [ForkSentryReporter fork_addBreadcrumb:@"voice.call_invite.deferred_answer_accepted"];
        [self performAnswerVoiceCallWithUUID:effectiveUuid completion:^(BOOL success) {
            if (!success) {
                [ForkSentryReporter fork_reportError:@"voice.call.deferred_answer_failed" cause:nil];
                [self.callKitProvider reportCallWithUUID:effectiveUuid endedAtDate:[NSDate date] reason:CXCallEndedReasonFailed];
            }
        }];
    }
    // <<< FORK
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
    // >>> FORK KAR-891 — no bound invite yet: the caller hung up during the cold-start gap
    // between the synchronous push report and -callInviteReceived:. End the reserved
    // CallKit call so it doesn't stay stuck ringing/connected, and release its state.
    if (!uuid) {
        NSUUID *reservedUuid = [[ForkVoipPushReporter sharedReporter] reservedUUIDForCallSid:cancelledCallInvite.callSid];
        if (reservedUuid != nil) {
            [ForkSentryReporter fork_addBreadcrumb:@"voice.call_invite.cancelled_before_arrival"];
            [self.callKitProvider reportCallWithUUID:reservedUuid endedAtDate:[NSDate date] reason:CXCallEndedReasonRemoteEnded];
            [[ForkVoipPushReporter sharedReporter] clearReservationForCallSid:cancelledCallInvite.callSid];
            return;
        }
        [ForkSentryReporter fork_reportError:@"voice.call_invite.cancelled_without_match" cause:nil];
        return;
    }
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
