// FORK — KAR-310 (answer). See TwilioVoiceReactNative+SafeAnswer.h.

@import TwilioVoice;

#import "TwilioVoiceReactNative+SafeAnswer.h"
#import "TwilioVoiceReactNativeConstants.h"

@implementation TwilioVoiceReactNative (SafeAnswer)

- (void)fork_performAnswerCallAction:(CXAnswerCallAction *)action {
    [TwilioVoiceReactNative twilioAudioDevice].enabled = NO;
    [TwilioVoiceReactNative twilioAudioDevice].block();

    NSUUID *uuid = action.callUUID;
    TVOCallInvite *callInvite = self.callInviteMap[uuid.UUIDString];
    if (!callInvite) {
        NSLog(@"fork_performAnswerCallAction: no invite for uuid=%@, failing action", uuid.UUIDString);
        [action fail];
        return;
    }

    TVOAcceptOptions *acceptOptions = [TVOAcceptOptions optionsWithCallInvite:callInvite block:^(TVOAcceptOptionsBuilder *builder) {
        builder.uuid = uuid;
        builder.callMessageDelegate = self;
    }];

    TVOCall *call = [callInvite acceptWithOptions:acceptOptions delegate:self];
    if (!call) {
        NSLog(@"fork_performAnswerCallAction: acceptWithOptions returned nil, failing action");
        [action fail];
        return;
    }

    self.callMap[call.uuid.UUIDString] = call;

    [self sendEventWithName:kTwilioVoiceReactNativeScopeCallInvite
                       body:@{
                         kTwilioVoiceReactNativeCallInviteEventKeyType: kTwilioVoiceReactNativeCallInviteEventTypeValueAccepted,
                         kTwilioVoiceReactNativeCallInviteEventKeyCallSid: callInvite.callSid,
                         kTwilioVoiceReactNativeEventKeyCallInvite: [self callInviteInfo:callInvite]}];

    [action fulfill];
}

@end
