// FORK — KAR-878
// Owns: native Twilio Voice operational reporting to the host application's Sentry SDK.
// Hooks into: iOS call, registration, messaging, PushKit, CallKit, and audio failure paths.
// Re-check on SDK bump: Sentry Cocoa capture APIs and Twilio delegate error semantics.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface ForkSentryReporter : NSObject

+ (void)fork_reportError:(NSString *)operation cause:(nullable NSError *)cause;
+ (void)fork_reportWarning:(NSString *)operation cause:(nullable NSError *)cause;
+ (void)fork_addBreadcrumb:(NSString *)operation;

@end

NS_ASSUME_NONNULL_END
