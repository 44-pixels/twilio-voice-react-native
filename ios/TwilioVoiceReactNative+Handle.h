// FORK — KAR-287, KAR-310 (cancel)
// Owns: CXCallUpdate construction for incoming calls; synchronous dismissal
//   of CallKit UI for cancelled invites.
// Hooks into: TwilioVoiceReactNative+CallKit.m (reportNewIncomingCall),
//   TwilioVoiceReactNative+CallInvite.m (cancelledCallInviteReceived).
// Re-check on SDK bump:
//   - Whether upstream switched the handle type to CXHandleTypePhoneNumber
//     for E.164 values, or otherwise unblocked iOS Contacts lookup.
//   - Whether upstream changed the supports* flags or added new fields on
//     CXCallUpdate (mirror those changes here).
//   - Whether upstream switched cancel to reportCallWithUUID:endedAtDate:reason:
//     to close the answer-after-cancel race.

#import <CallKit/CallKit.h>

#import "TwilioVoiceReactNative.h"

@interface TwilioVoiceReactNative (Handle)

- (CXCallUpdate *)fork_callUpdateForHandleName:(NSString *)handleName;
- (void)fork_dismissIncomingCallUI:(NSUUID *)uuid;

@end
