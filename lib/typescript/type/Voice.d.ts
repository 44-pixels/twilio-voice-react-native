import type { Constants } from '../constants';
import type { NativeAudioDevicesUpdatedEvent } from './AudioDevice';
import type { NativeCallInviteInfo } from './CallInvite';
import type { NativeErrorEvent } from './Error';
export interface NativeCallbackRequestedEvent {
    [Constants.VoiceEventType]: Constants.VoiceEventCallbackRequested;
    [Constants.CallbackRequestKeyRequestId]: string;
    [Constants.CallbackRequestKeyHandle]: string;
}
export interface NativeRegisteredEvent {
    type: Constants.VoiceEventRegistered;
}
export interface NativeUnregisteredEvent {
    type: Constants.VoiceEventUnregistered;
}
export interface NativePushTokenChangedEvent {
    type: Constants.VoiceEventPushTokenChanged;
    token: string;
}
export interface NativeCallInviteIncomingEvent {
    [Constants.VoiceEventType]: Constants.VoiceEventTypeValueIncomingCallInvite;
    callInvite: NativeCallInviteInfo;
}
export type NativeVoiceEvent = NativeAudioDevicesUpdatedEvent | NativeCallbackRequestedEvent | NativeCallInviteIncomingEvent | NativeErrorEvent | NativeRegisteredEvent | NativeUnregisteredEvent | NativePushTokenChangedEvent;
export type NativeVoiceEventType = Constants.VoiceEventAudioDevicesUpdated | Constants.VoiceEventCallbackRequested | Constants.VoiceEventTypeValueIncomingCallInvite | Constants.VoiceEventError | Constants.VoiceEventRegistered | Constants.VoiceEventUnregistered | Constants.VoiceEventPushTokenChanged;
