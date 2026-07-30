import type { Constants } from '../constants';
import type { NativeAudioDevicesUpdatedEvent } from './AudioDevice';
import type { NativeCallInviteInfo } from './CallInvite';
import type { NativeErrorEvent } from './Error';

// >>> FORK KAR-873 — see CallbackRequest.ts
export interface NativeCallbackRequestedEvent {
  [Constants.VoiceEventType]: Constants.VoiceEventCallbackRequested;
  [Constants.CallbackRequestKeyRequestId]: string;
  [Constants.CallbackRequestKeyHandle]: string;
}
// <<< FORK

export interface NativeRegisteredEvent {
  type: Constants.VoiceEventRegistered;
}

export interface NativeUnregisteredEvent {
  type: Constants.VoiceEventUnregistered;
}

export interface NativeCallInviteIncomingEvent {
  [Constants.VoiceEventType]: Constants.VoiceEventTypeValueIncomingCallInvite;
  callInvite: NativeCallInviteInfo;
}

export type NativeVoiceEvent =
  | NativeAudioDevicesUpdatedEvent
  // >>> FORK KAR-873 — see CallbackRequest.ts
  | NativeCallbackRequestedEvent
  // <<< FORK
  | NativeCallInviteIncomingEvent
  | NativeErrorEvent
  | NativeRegisteredEvent
  | NativeUnregisteredEvent;

export type NativeVoiceEventType =
  | Constants.VoiceEventAudioDevicesUpdated
  // >>> FORK KAR-873 — see CallbackRequest.ts
  | Constants.VoiceEventCallbackRequested
  // <<< FORK
  | Constants.VoiceEventTypeValueIncomingCallInvite
  | Constants.VoiceEventError
  | Constants.VoiceEventRegistered
  | Constants.VoiceEventUnregistered;
