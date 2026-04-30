// FORK — KAR-287
// Predicate: is the string an E.164 phone number?
// Used by TwilioVoiceReactNative+CallKit.m (reportNewIncomingCall) to choose
// CXHandleTypePhoneNumber vs CXHandleTypeGeneric and to decide whether to set
// localizedCallerName, so iOS Contacts can perform its own lookup.
// Re-check on SDK bump: whether upstream now selects handle type itself.
//
// E.164: '+' followed by 2-15 digits, leading digit non-zero.

#import <Foundation/Foundation.h>

static inline BOOL ForkIsE164PhoneNumber(NSString *value) {
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
