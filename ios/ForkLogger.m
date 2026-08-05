// FORK — KAR-878
// Owns: Twilio Voice native logging mirrored to the host application's Sentry SDK.
// Hooks into: Objective-C native log sites.
// Re-check on SDK bump: Sentry Cocoa breadcrumb/event APIs and native log sites.

@import Sentry;

#import "ForkLogger.h"

static NSString * const kForkLoggerCategory = @"twilio.voice.log";
static NSString * const kForkLoggerTag = @"twilio_voice.logger";
static NSString * const kForkLoggerName = @"TwilioVoiceReactNative";
static NSString * const kForkLoggerRedacted = @"[REDACTED]";

@implementation ForkLogger

+ (void)fork_debug:(NSString *)format, ... {
    va_list arguments;
    va_start(arguments, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:arguments];
    va_end(arguments);
    [self fork_emit:message level:kSentryLevelDebug cause:nil capture:NO];
}

+ (void)fork_info:(NSString *)format, ... {
    va_list arguments;
    va_start(arguments, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:arguments];
    va_end(arguments);
    [self fork_emit:message level:kSentryLevelInfo cause:nil capture:NO];
}

+ (void)fork_warning:(NSString *)format, ... {
    va_list arguments;
    va_start(arguments, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:arguments];
    va_end(arguments);
    [self fork_emit:message level:kSentryLevelWarning cause:nil capture:YES];
}

+ (void)fork_warningWithCause:(NSError *)cause format:(NSString *)format, ... {
    va_list arguments;
    va_start(arguments, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:arguments];
    va_end(arguments);
    [self fork_emit:message level:kSentryLevelWarning cause:cause capture:YES];
}

+ (void)fork_error:(NSString *)format, ... {
    va_list arguments;
    va_start(arguments, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:arguments];
    va_end(arguments);
    [self fork_emit:message level:kSentryLevelError cause:nil capture:YES];
}

+ (void)fork_errorWithCause:(NSError *)cause format:(NSString *)format, ... {
    va_list arguments;
    va_start(arguments, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:arguments];
    va_end(arguments);
    [self fork_emit:message level:kSentryLevelError cause:cause capture:YES];
}

+ (void)fork_emit:(NSString *)message
            level:(SentryLevel)level
            cause:(NSError *)cause
          capture:(BOOL)capture {
    NSString *safeMessage = [self fork_redact:message];
    NSLog(@"%@", safeMessage);

    SentryBreadcrumb *breadcrumb = [[SentryBreadcrumb alloc] initWithLevel:level
                                                                   category:kForkLoggerCategory];
    breadcrumb.message = safeMessage;
    breadcrumb.data = @{@"logger": kForkLoggerName};
    [SentrySDK addBreadcrumb:breadcrumb];

    if (!capture) return;

    void (^scopeBlock)(SentryScope *) = ^(SentryScope *scope) {
        [scope setTagValue:kForkLoggerName forKey:kForkLoggerTag];
        [scope setExtraValue:safeMessage forKey:@"twilio_voice_log_message"];
    };
    if (cause == nil) {
        [SentrySDK captureMessage:safeMessage withScopeBlock:^(SentryScope *scope) {
            [scope setLevel:level];
            scopeBlock(scope);
        }];
        return;
    }

    SentryEvent *event = [[SentryEvent alloc] initWithError:cause];
    event.level = level;
    [SentrySDK captureEvent:event withScopeBlock:scopeBlock];
}

+ (NSString *)fork_redact:(NSString *)message {
    NSString *safeMessage = message;
    NSArray<NSDictionary<NSString *, NSString *> *> *rules = @[
        @{
            @"pattern": @"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])",
            @"replacement": kForkLoggerRedacted,
        },
        @{
            @"pattern": @"(?i)((?:fcm|apns|device)(?: with)? token[=: ]{1,3})[a-z0-9:_-]{20,}",
            @"replacement": [@"$1" stringByAppendingString:kForkLoggerRedacted],
        },
        @{
            @"pattern": @"(?i)((?:access[_ -]?token|authorization|api[_ -]?key|secret|password)[=: ]{1,3})\\S+",
            @"replacement": [@"$1" stringByAppendingString:kForkLoggerRedacted],
        },
    ];

    for (NSDictionary<NSString *, NSString *> *rule in rules) {
        NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:rule[@"pattern"]
                                                                               options:0
                                                                                 error:nil];
        safeMessage = [regex stringByReplacingMatchesInString:safeMessage
                                                      options:0
                                                        range:NSMakeRange(0, safeMessage.length)
                                                 withTemplate:rule[@"replacement"]];
    }
    return safeMessage;
}

@end
