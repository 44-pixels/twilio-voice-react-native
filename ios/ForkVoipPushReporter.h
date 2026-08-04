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

// YES if this UUID was reported from a push and the invite is still expected.
- (BOOL)hasReservationForUUID:(nullable NSUUID *)uuid;

// Release a reservation (and any pending-answer / declined state) once the
// invite is cancelled / rejected.
- (void)clearReservationForCallSid:(nullable NSString *)callSid;

// >>> FORK KAR-891 — bridge a CallKit answer/end that lands during cold start,
// before the TVOCallInvite has been delivered to the RN module.
//
// The CallKit call is answerable (or endable) the instant the push is reported,
// but the invite arrives several async hops later. These record the user's intent
// against the reserved UUID so the module can act on it when the invite binds.

// User tapped Answer before the invite arrived. Keeps the call alive and starts a
// safety timeout that ends the call if no invite ever binds.
- (void)markPendingAnswerForUUID:(nullable NSUUID *)uuid;

// Consumes a pending-answer (returns YES if one was set), cancelling its timeout.
- (BOOL)consumePendingAnswerForUUID:(nullable NSUUID *)uuid;

// User tapped End before the invite arrived. Drops any reservation/pending-answer
// and remembers to reject the invite when it binds.
- (void)markDeclinedForUUID:(nullable NSUUID *)uuid;

// Consumes a declined marker (returns YES if one was set).
- (BOOL)consumeDeclinedForUUID:(nullable NSUUID *)uuid;
// <<< FORK

@end

NS_ASSUME_NONNULL_END
