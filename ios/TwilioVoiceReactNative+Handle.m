// FORK — KAR-310 (cancel). See TwilioVoiceReactNative+Handle.h.

@import CallKit;

#import "TwilioVoiceReactNative+Handle.h"

@implementation TwilioVoiceReactNative (Handle)

// Upstream routes cancel through endCallWithUuid: which enqueues a
// CXEndCallAction transaction. The incoming UI stays visible until that
// transaction is processed, leaving a window where the user can tap Answer
// on an already-cancelled invite. reportCallWithUUID:endedAtDate:reason:
// dismisses the UI synchronously and closes the race.
- (void)fork_dismissIncomingCallUI:(NSUUID *)uuid {
    [self.callKitProvider reportCallWithUUID:uuid
                                 endedAtDate:[NSDate date]
                                      reason:CXCallEndedReasonRemoteEnded];
}

@end
