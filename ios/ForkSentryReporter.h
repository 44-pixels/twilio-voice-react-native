// FORK — KAR-878
// Owns: native Twilio Voice operational reporting to the host application's Sentry SDK.
// Hooks into: iOS call, registration, messaging, PushKit, CallKit, and audio failure paths.
// Re-check on SDK bump: Sentry Cocoa capture APIs, Twilio call states, and delegate callback ordering.

#import <Foundation/Foundation.h>

@class TVOCall;

NS_ASSUME_NONNULL_BEGIN

@interface ForkSentryReporter : NSObject

+ (void)fork_reportError:(NSString *)operation cause:(nullable NSError *)cause;
+ (void)fork_reportError:(NSString *)operation
                   cause:(nullable NSError *)cause
                    data:(NSDictionary<NSString *, id> *)data;
+ (void)fork_reportWarning:(NSString *)operation cause:(nullable NSError *)cause;
+ (void)fork_reportWarning:(NSString *)operation
                     cause:(nullable NSError *)cause
                      data:(NSDictionary<NSString *, id> *)data;
+ (void)fork_addBreadcrumb:(NSString *)operation;
+ (void)fork_addBreadcrumb:(NSString *)operation data:(NSDictionary<NSString *, id> *)data;

+ (void)fork_recordCallCreationForUUID:(NSUUID *)uuid;
+ (void)fork_recordConnectResultForUUID:(NSUUID *)requestedUUID
                                  call:(nullable TVOCall *)call
                     nativeCallMapSize:(NSUInteger)nativeCallMapSize;
+ (void)fork_recordLifecycle:(NSString *)operation
                        call:(TVOCall *)call
                       cause:(nullable NSError *)cause;
+ (void)fork_recordEndCallActionForUUID:(NSUUID *)uuid
                                  call:(nullable TVOCall *)call
                     nativeCallMapSize:(NSUInteger)nativeCallMapSize;
+ (void)fork_recordDisconnectBoundary:(NSString *)operation call:(TVOCall *)call;

@end

NS_ASSUME_NONNULL_END
