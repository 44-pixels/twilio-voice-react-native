// FORK — KAR-310 (cancel)
// Owns: synchronous dismissal of CallKit UI for cancelled invites.
// Hooks into: TwilioVoiceReactNative+CallInvite.m (cancelledCallInviteReceived).
// Re-check on SDK bump: whether upstream switched cancel to
//   reportCallWithUUID:endedAtDate:reason: to close the answer-after-cancel race.

#import "TwilioVoiceReactNative.h"

@interface TwilioVoiceReactNative (Handle)

- (void)fork_dismissIncomingCallUI:(NSUUID *)uuid;

@end
