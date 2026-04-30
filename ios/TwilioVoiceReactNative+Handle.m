// FORK — KAR-287, KAR-310 (cancel). See TwilioVoiceReactNative+Handle.h.

#import "TwilioVoiceReactNative+Handle.h"

// E.164: '+' followed by 2-15 digits, leading digit non-zero.
static BOOL ForkIsE164PhoneNumber(NSString *value) {
    NSUInteger length = [value length];
    if (length < 3 || length > 16 || ![value hasPrefix:@"+"]) return NO;
    unichar leading = [value characterAtIndex:1];
    if (leading < '1' || leading > '9') return NO;
    NSCharacterSet *digits = [NSCharacterSet decimalDigitCharacterSet];
    for (NSUInteger i = 2; i < length; i++) {
        if (![digits characterIsMember:[value characterAtIndex:i]]) return NO;
    }
    return YES;
}

@implementation TwilioVoiceReactNative (Handle)

// KAR-287: when handleName looks like an E.164 phone number, report it as
// CXHandleTypePhoneNumber and omit localizedCallerName so CallKit and iOS
// Contacts can match and display the native contact name. Non-E.164 values
// keep the original generic-handle behavior.
- (CXCallUpdate *)fork_callUpdateForHandleName:(NSString *)handleName {
    BOOL isPhoneNumber = ForkIsE164PhoneNumber(handleName);
    CXHandleType handleType = isPhoneNumber ? CXHandleTypePhoneNumber : CXHandleTypeGeneric;
    CXHandle *callHandle = [[CXHandle alloc] initWithType:handleType value:handleName];

    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = callHandle;
    if (!isPhoneNumber) {
        callUpdate.localizedCallerName = handleName;
    }
    callUpdate.supportsDTMF = YES;
    callUpdate.supportsHolding = YES;
    callUpdate.supportsGrouping = NO;
    callUpdate.supportsUngrouping = NO;
    callUpdate.hasVideo = NO;
    return callUpdate;
}

// KAR-310 (cancel): upstream routes cancel through endCallWithUuid: which
// enqueues a CXEndCallAction transaction. The incoming UI stays visible until
// that transaction is processed, leaving a window where the user can tap
// Answer on an already-cancelled invite. reportCallWithUUID:endedAtDate:reason:
// dismisses the UI synchronously and closes the race.
- (void)fork_dismissIncomingCallUI:(NSUUID *)uuid {
    [self.callKitProvider reportCallWithUUID:uuid
                                 endedAtDate:[NSDate date]
                                      reason:CXCallEndedReasonRemoteEnded];
}

@end
