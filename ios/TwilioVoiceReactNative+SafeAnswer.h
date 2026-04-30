// FORK — KAR-310 (answer)
// Owns: end-to-end handling of CXAnswerCallAction.
// Hooks into: TwilioVoiceReactNative+CallKit.m (provider:performAnswerCallAction:).
// Re-check on SDK bump:
//   - Whether upstream added a nil-invite guard in performAnswerVoiceCallWithUUID:.
//   - Whether upstream propagates answer-failure back to the CXAction (fail vs.
//     fulfill). If so, drop this fork file and restore the original call site.
//   - This wrapper duplicates the body of performAnswerVoiceCallWithUUID:; if
//     upstream changes that body (e.g. TVOAcceptOptions construction), mirror
//     the change here.
//
// Background: if a cancelled-invite push lands between CallKit showing the
// incoming UI and the user tapping Answer, callInviteMap[uuid] is cleared by
// cancelledCallInviteReceived:. Upstream only guards with NSAssert (compiled
// out in Release); passing nil into TVOAcceptOptions throws
// NSInvalidArgumentException. Additionally, performAnswerVoiceCallWithUUID:
// invokes its completion only on failure, and the CXAction is unconditionally
// fulfilled — leaving CallKit in the wrong state on failure.

#import <CallKit/CallKit.h>

#import "TwilioVoiceReactNative.h"

@interface TwilioVoiceReactNative (SafeAnswer)

- (void)fork_performAnswerCallAction:(CXAnswerCallAction *)action;

@end
