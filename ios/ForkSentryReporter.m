// FORK — KAR-878
// Owns: native Twilio Voice operational reporting to the host application's Sentry SDK.
// Hooks into: iOS call, registration, messaging, PushKit, CallKit, and audio failure paths.
// Re-check on SDK bump: Sentry Cocoa capture APIs, Twilio call states, and delegate callback ordering.

@import Sentry;
@import TwilioVoice;

#import "ForkSentryReporter.h"

static NSString * const kForkSentryCategory = @"twilio.voice";
static NSString * const kForkSentryOperationTag = @"twilio_voice.operation";

@interface ForkCallDiagnosticRecord : NSObject

@property (nonatomic) NSTimeInterval createdAtUptime;
@property (nonatomic) BOOL lifecycleObserved;
@property (nonatomic) BOOL ringingObserved;
@property (nonatomic) BOOL connectedObserved;
@property (nonatomic, copy, nullable) NSDictionary<NSString *, id> *pendingStuckCancellationData;

@end

@implementation ForkCallDiagnosticRecord
@end

static NSMutableDictionary<NSString *, ForkCallDiagnosticRecord *> *sCallRecords;

@implementation ForkSentryReporter

+ (void)fork_reportError:(NSString *)operation cause:(NSError *)cause {
    [self fork_reportError:operation cause:cause data:@{}];
}

+ (void)fork_reportError:(NSString *)operation
                   cause:(NSError *)cause
                    data:(NSDictionary<NSString *, id> *)data {
    if (cause == nil) {
        [SentrySDK captureMessage:operation withScopeBlock:^(SentryScope *scope) {
            [scope setLevel:kSentryLevelError];
            [self fork_enrichScope:scope operation:operation data:data];
        }];
        return;
    }

    [SentrySDK captureError:cause withScopeBlock:^(SentryScope *scope) {
        [self fork_enrichScope:scope operation:operation data:data];
    }];
}

+ (void)fork_reportWarning:(NSString *)operation cause:(NSError *)cause {
    [self fork_reportWarning:operation cause:cause data:@{}];
}

+ (void)fork_reportWarning:(NSString *)operation
                     cause:(NSError *)cause
                      data:(NSDictionary<NSString *, id> *)data {
    if (cause == nil) {
        [SentrySDK captureMessage:operation withScopeBlock:^(SentryScope *scope) {
            [scope setLevel:kSentryLevelWarning];
            [self fork_enrichScope:scope operation:operation data:data];
        }];
        return;
    }

    SentryEvent *event = [[SentryEvent alloc] initWithError:cause];
    event.level = kSentryLevelWarning;
    [SentrySDK captureEvent:event withScopeBlock:^(SentryScope *scope) {
        [self fork_enrichScope:scope operation:operation data:data];
    }];
}

+ (void)fork_addBreadcrumb:(NSString *)operation {
    [self fork_addBreadcrumb:operation data:@{}];
}

+ (void)fork_addBreadcrumb:(NSString *)operation data:(NSDictionary<NSString *, id> *)data {
    SentryBreadcrumb *breadcrumb = [[SentryBreadcrumb alloc] initWithLevel:kSentryLevelInfo
                                                                   category:kForkSentryCategory];
    breadcrumb.message = operation;
    breadcrumb.data = data;
    [SentrySDK addBreadcrumb:breadcrumb];
}

+ (void)fork_recordCallCreationForUUID:(NSUUID *)uuid {
    ForkCallDiagnosticRecord *record = [ForkCallDiagnosticRecord new];
    record.createdAtUptime = [NSProcessInfo processInfo].systemUptime;
    @synchronized(self) {
        [self fork_callRecords][uuid.UUIDString] = record;
    }
}

+ (void)fork_recordConnectResultForUUID:(NSUUID *)requestedUUID
                                  call:(TVOCall *)call
                     nativeCallMapSize:(NSUInteger)nativeCallMapSize {
    NSUUID *uuid = call != nil ? call.uuid : requestedUUID;
    NSDictionary<NSString *, id> *data = @{
        @"call_uuid": uuid.UUIDString,
        @"call_returned": @(call != nil),
        @"initial_call_state": call != nil ? [self fork_nameForCallState:call.state] : [NSNull null],
        @"native_call_map_size": @(nativeCallMapSize),
    };

    @synchronized(self) {
        ForkCallDiagnosticRecord *record = [self fork_callRecords][requestedUUID.UUIDString];
        if (call != nil && ![uuid isEqual:requestedUUID]) {
            [[self fork_callRecords] removeObjectForKey:requestedUUID.UUIDString];
            [self fork_callRecords][uuid.UUIDString] = record;
        } else if (call == nil) {
            [[self fork_callRecords] removeObjectForKey:requestedUUID.UUIDString];
        }
    }

    [self fork_addBreadcrumb:@"voice.call.connect_returned" data:data];
    if (call == nil) {
        [self fork_reportError:@"voice.call.connect_returned_nil" cause:nil data:data];
    }
}

+ (void)fork_recordLifecycle:(NSString *)operation
                        call:(TVOCall *)call
                       cause:(NSError *)cause {
    NSDictionary<NSString *, id> *data = @{
        @"call_uuid": call.uuid.UUIDString,
        @"call_state": [self fork_nameForCallState:call.state],
    };

    @synchronized(self) {
        ForkCallDiagnosticRecord *record = [self fork_callRecords][call.uuid.UUIDString];
        record.lifecycleObserved = YES;
        if ([operation isEqualToString:@"voice.call.ringing"]) record.ringingObserved = YES;
        if ([operation isEqualToString:@"voice.call.connected"]) record.connectedObserved = YES;
    }

    [self fork_addBreadcrumb:operation data:data];
    if (cause != nil) {
        NSString *errorOperation = [operation isEqualToString:@"voice.call.disconnected"]
            ? @"voice.call.disconnected_with_error"
            : operation;
        [self fork_reportError:errorOperation cause:cause data:data];
    }

    BOOL terminal = [operation isEqualToString:@"voice.call.disconnected"]
        || [operation isEqualToString:@"voice.call.connect_failed"];
    if (terminal) {
        @synchronized(self) {
            [[self fork_callRecords] removeObjectForKey:call.uuid.UUIDString];
        }
    }
}

+ (void)fork_recordEndCallActionForUUID:(NSUUID *)uuid
                                  call:(TVOCall *)call
                     nativeCallMapSize:(NSUInteger)nativeCallMapSize {
    ForkCallDiagnosticRecord *record;
    @synchronized(self) {
        record = [self fork_callRecords][uuid.UUIDString];
    }

    NSTimeInterval elapsed = record != nil
        ? [NSProcessInfo processInfo].systemUptime - record.createdAtUptime
        : 0;
    NSDictionary<NSString *, id> *data = @{
        @"call_uuid": uuid.UUIDString,
        @"call_map_contains_uuid": @(call != nil),
        @"call_state": call != nil ? [self fork_nameForCallState:call.state] : [NSNull null],
        @"ringing_observed": @(record.ringingObserved),
        @"connected_observed": @(record.connectedObserved),
        @"elapsed_since_creation_ms": record != nil ? @(elapsed * 1000.0) : [NSNull null],
        @"native_call_map_size": @(nativeCallMapSize),
        @"diagnostic_record_found": @(record != nil),
    };

    [self fork_addBreadcrumb:@"voice.callkit.end_action" data:data];
    BOOL stuckCancellation = record != nil && !record.lifecycleObserved;
    if (stuckCancellation && call != nil) {
        @synchronized(self) {
            record.pendingStuckCancellationData = data;
        }
        return;
    }

    if (stuckCancellation) {
        [self fork_reportWarning:@"voice.call.cancelled_while_stuck_connecting" cause:nil data:data];
    }
    @synchronized(self) {
        [[self fork_callRecords] removeObjectForKey:uuid.UUIDString];
    }
}

+ (void)fork_recordDisconnectBoundary:(NSString *)operation call:(TVOCall *)call {
    NSDictionary<NSString *, id> *data = @{
        @"call_uuid": call.uuid.UUIDString,
        @"call_state": [self fork_nameForCallState:call.state],
    };
    [self fork_addBreadcrumb:operation data:data];

    if (![operation isEqualToString:@"voice.call.disconnect_invocation.after"]) return;

    NSDictionary<NSString *, id> *stuckCancellationData;
    @synchronized(self) {
        ForkCallDiagnosticRecord *record = [self fork_callRecords][call.uuid.UUIDString];
        stuckCancellationData = record.pendingStuckCancellationData;
        [[self fork_callRecords] removeObjectForKey:call.uuid.UUIDString];
    }
    if (stuckCancellationData != nil) {
        [self fork_reportWarning:@"voice.call.cancelled_while_stuck_connecting"
                          cause:nil
                           data:stuckCancellationData];
    }
}

+ (NSMutableDictionary<NSString *, ForkCallDiagnosticRecord *> *)fork_callRecords {
    if (sCallRecords == nil) sCallRecords = [NSMutableDictionary dictionary];
    return sCallRecords;
}

+ (NSString *)fork_nameForCallState:(TVOCallState)state {
    switch (state) {
        case TVOCallStateConnecting:
            return @"connecting";
        case TVOCallStateRinging:
            return @"ringing";
        case TVOCallStateConnected:
            return @"connected";
        case TVOCallStateReconnecting:
            return @"reconnecting";
        case TVOCallStateDisconnected:
            return @"disconnected";
    }

    return @"unknown";
}

+ (void)fork_enrichScope:(SentryScope *)scope
               operation:(NSString *)operation
                    data:(NSDictionary<NSString *, id> *)data {
    [scope setTagValue:operation forKey:kForkSentryOperationTag];
    for (NSString *key in data) {
        [scope setExtraValue:data[key] forKey:key];
    }
}

@end
