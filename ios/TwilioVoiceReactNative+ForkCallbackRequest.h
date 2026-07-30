// FORK — KAR-873
// Owns: TwilioVoiceReactNative callback-request bridge hooks.
// Hooks into: TwilioVoiceReactNative notification subscription.
// Re-check on SDK bump: RCT event-emitter and promise-export conventions.

#import "TwilioVoiceReactNative.h"

@interface TwilioVoiceReactNative (ForkCallbackRequest)

- (void)fork_handleCallbackRequestNotification:(NSNotification *)notification;

@end
