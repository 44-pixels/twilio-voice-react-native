//
//  ForkVoipPushReporter.h
//  TwilioVoiceReactNative
//
// FORK — KAR-869 (Sentry KAREN-APP-5M)
// Reports an incoming call to CallKit synchronously from the PushKit delegate so
// iOS doesn't kill the app for an unhandled VoIP push on cold start. The real
// invite is reconciled onto the reserved UUID, so it stays one call.

#import <Foundation/Foundation.h>
@import CallKit;

NS_ASSUME_NONNULL_BEGIN

@interface ForkVoipPushReporter : NSObject

@property (class, nonatomic, readonly) ForkVoipPushReporter *sharedReporter;

// App-wide CallKit provider + controller, kept alive for the process lifetime so
// a push can be answered before the RN module initializes. TwilioVoiceReactNative
// adopts these and installs itself as the delegate.
@property (nonatomic, strong, readonly) CXProvider *provider;
@property (nonatomic, strong, readonly) CXCallController *callController;

// Synchronously reports an incoming call for a Twilio VoIP push payload and
// returns the reserved UUID. Duplicate pushes for a call SID reuse the same UUID.
- (nullable NSUUID *)reportIncomingCallForPushPayload:(nullable NSDictionary *)payload;

// The UUID reserved for a call SID by a prior report, if any.
- (nullable NSUUID *)reservedUUIDForCallSid:(nullable NSString *)callSid;

// Release a reservation once the invite is cancelled / rejected.
- (void)clearReservationForCallSid:(nullable NSString *)callSid;

@end

NS_ASSUME_NONNULL_END
