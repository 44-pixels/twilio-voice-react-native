// FORK — KAR-873
// Owns: INStartCallIntent parsing and durable callback-request delivery.
// Hooks into: app.plugin.js, TwilioVoiceReactNative, CallKit category.
// Re-check on SDK bump: AppDelegate user-activity and CallKit intent contracts.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSNotificationName const ForkCallbackRequestReceivedNotification;

@interface ForkCallbackRequestStore : NSObject

+ (BOOL)fork_handleUserActivity:(NSUserActivity *)userActivity NS_SWIFT_NAME(handle(_:));
+ (void)fork_rememberHandle:(NSString *)handle destination:(nullable NSString *)destination;
+ (nullable NSDictionary<NSString *, NSString *> *)fork_pendingRequest;
+ (void)fork_clearPendingRequestWithId:(NSString *)requestId;

@end

NS_ASSUME_NONNULL_END
