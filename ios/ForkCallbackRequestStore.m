// FORK — KAR-873
// Owns: CallKit intent parsing, destination mappings, and pending requests.
// Hooks into: ForkCallbackRequestStore.h and TwilioVoiceReactNative categories.
// Re-check on SDK bump: INStartCallIntent and INCallRecord participant shapes.

@import Intents;

#import "ForkCallbackRequestStore.h"
#import "TwilioVoiceReactNativeConstants.h"

NSNotificationName const ForkCallbackRequestReceivedNotification =
    @"TwilioVoiceCallbackRequestReceived";

static NSString * const kPendingCallbackRequestDefaultsKey =
    @"TwilioVoicePendingCallbackRequest";
static NSString * const kCallbackDestinationsDefaultsKey =
    @"TwilioVoiceCallbackDestinations";
static NSUInteger const kMaximumCallbackDestinations = 200;

@implementation ForkCallbackRequestStore

+ (BOOL)fork_handleUserActivity:(NSUserActivity *)userActivity {
    NSString *callKitHandle = [self fork_handleFromIntent:userActivity.interaction.intent];
    if (callKitHandle.length == 0) return NO;

    NSDictionary<NSString *, NSString *> *destinations =
        [[NSUserDefaults standardUserDefaults]
            dictionaryForKey:kCallbackDestinationsDefaultsKey];
    NSString *handle = destinations[callKitHandle] ?: callKitHandle;
    NSDictionary<NSString *, NSString *> *request = @{
        kTwilioVoiceReactNativeCallbackRequestKeyRequestId: [NSUUID UUID].UUIDString,
        kTwilioVoiceReactNativeCallbackRequestKeyHandle: handle,
    };

    @synchronized(self) {
        [[NSUserDefaults standardUserDefaults] setObject:request
                                                  forKey:kPendingCallbackRequestDefaultsKey];
    }

    dispatch_async(dispatch_get_main_queue(), ^{
        [[NSNotificationCenter defaultCenter]
            postNotificationName:ForkCallbackRequestReceivedNotification
                          object:nil
                        userInfo:request];
    });
    return YES;
}

+ (void)fork_rememberHandle:(NSString *)handle destination:(nullable NSString *)destination {
    if (handle.length == 0 || destination.length == 0) return;

    @synchronized(self) {
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        NSMutableDictionary<NSString *, NSString *> *destinations =
            [[defaults dictionaryForKey:kCallbackDestinationsDefaultsKey] mutableCopy] ?:
                [NSMutableDictionary dictionary];
        if (destinations.count >= kMaximumCallbackDestinations &&
            destinations[handle] == nil) {
            [destinations removeObjectForKey:destinations.allKeys.firstObject];
        }
        destinations[handle] = destination;
        [defaults setObject:destinations forKey:kCallbackDestinationsDefaultsKey];
    }
}

+ (nullable NSDictionary<NSString *, NSString *> *)fork_pendingRequest {
    @synchronized(self) {
        return [[NSUserDefaults standardUserDefaults]
            dictionaryForKey:kPendingCallbackRequestDefaultsKey];
    }
}

+ (void)fork_clearPendingRequestWithId:(NSString *)requestId {
    @synchronized(self) {
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        NSDictionary<NSString *, NSString *> *request =
            [defaults dictionaryForKey:kPendingCallbackRequestDefaultsKey];
        if ([request[kTwilioVoiceReactNativeCallbackRequestKeyRequestId]
                isEqualToString:requestId]) {
            [defaults removeObjectForKey:kPendingCallbackRequestDefaultsKey];
        }
    }
}

+ (nullable NSString *)fork_handleFromIntent:(nullable INIntent *)intent {
    NSArray<INPerson *> *contacts = nil;

    if (@available(iOS 13.0, *)) {
        if ([intent isKindOfClass:[INStartCallIntent class]]) {
            INStartCallIntent *startCallIntent = (INStartCallIntent *)intent;
            contacts = startCallIntent.contacts;
            if (@available(iOS 14.5, *)) {
                if (contacts.count == 0) {
                    contacts = startCallIntent.callRecordToCallBack.participants;
                }
            }
        }
    }

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    if (contacts.count == 0 && [intent isKindOfClass:[INStartAudioCallIntent class]]) {
        contacts = ((INStartAudioCallIntent *)intent).contacts;
    }
#pragma clang diagnostic pop

    INPerson *person = contacts.firstObject;
    NSString *handle = person.personHandle.value;
    return handle.length > 0 ? handle : nil;
}

@end
