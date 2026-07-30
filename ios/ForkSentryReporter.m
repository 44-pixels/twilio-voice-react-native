// FORK — KAR-878
// Owns: native Twilio Voice operational reporting to the host application's Sentry SDK.
// Hooks into: iOS call, registration, messaging, PushKit, CallKit, and audio failure paths.
// Re-check on SDK bump: Sentry Cocoa capture APIs and Twilio delegate error semantics.

@import Sentry;

#import "ForkSentryReporter.h"

static NSString * const kForkSentryCategory = @"twilio.voice";
static NSString * const kForkSentryOperationTag = @"twilio_voice.operation";

@implementation ForkSentryReporter

+ (void)fork_reportError:(NSString *)operation cause:(NSError *)cause {
    if (cause == nil) {
        [SentrySDK captureMessage:operation withScopeBlock:^(SentryScope *scope) {
            [scope setLevel:kSentryLevelError];
            [scope setTagValue:operation forKey:kForkSentryOperationTag];
        }];
        return;
    }

    [SentrySDK captureError:cause withScopeBlock:^(SentryScope *scope) {
        [scope setTagValue:operation forKey:kForkSentryOperationTag];
    }];
}

+ (void)fork_reportWarning:(NSString *)operation cause:(NSError *)cause {
    if (cause == nil) {
        [SentrySDK captureMessage:operation withScopeBlock:^(SentryScope *scope) {
            [scope setLevel:kSentryLevelWarning];
            [scope setTagValue:operation forKey:kForkSentryOperationTag];
        }];
        return;
    }

    SentryEvent *event = [[SentryEvent alloc] initWithError:cause];
    event.level = kSentryLevelWarning;
    [SentrySDK captureEvent:event withScopeBlock:^(SentryScope *scope) {
        [scope setTagValue:operation forKey:kForkSentryOperationTag];
    }];
}

+ (void)fork_addBreadcrumb:(NSString *)operation {
    SentryBreadcrumb *breadcrumb = [[SentryBreadcrumb alloc] initWithLevel:kSentryLevelInfo
                                                                   category:kForkSentryCategory];
    breadcrumb.message = operation;
    [SentrySDK addBreadcrumb:breadcrumb];
}

@end
