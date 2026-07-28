// FORK — KAR-857
// Owns: Per-call connection-issue state and connected/issue sound transitions.
// Hooks into: TwilioVoiceReactNative+CallKit.m and ForkCallSounds.
// Re-check on SDK bump: Call quality warning names and reconnect callback ordering.

#import <Foundation/Foundation.h>

@interface ForkCallIssueState : NSObject

+ (void)fork_connectedCall:(NSUUID *)uuid;
+ (void)fork_reconnectingCall:(NSUUID *)uuid;
+ (void)fork_reconnectedCall:(NSUUID *)uuid;
+ (void)fork_call:(NSUUID *)uuid qualityWarningsChanged:(NSSet<NSNumber *> *)currentWarnings;
+ (void)fork_endedCall:(NSUUID *)uuid;

@end
