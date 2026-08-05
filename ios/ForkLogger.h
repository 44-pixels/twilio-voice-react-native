// FORK — KAR-878
// Owns: Twilio Voice native logging mirrored to the host application's Sentry SDK.
// Hooks into: Objective-C native log sites.
// Re-check on SDK bump: Sentry Cocoa breadcrumb/event APIs and native log sites.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface ForkLogger : NSObject

+ (void)fork_debug:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)fork_info:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)fork_warning:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)fork_warningWithCause:(nullable NSError *)cause
                       format:(NSString *)format, ... NS_FORMAT_FUNCTION(2, 3);
+ (void)fork_error:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)fork_errorWithCause:(nullable NSError *)cause
                     format:(NSString *)format, ... NS_FORMAT_FUNCTION(2, 3);

@end

NS_ASSUME_NONNULL_END
