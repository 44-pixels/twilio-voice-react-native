import { Constants } from '../constants';
import { createNativeAudioDevicesInfo } from './AudioDevice';
import { createNativeCallInviteInfo } from './CallInvite';
import { createNativeErrorInfo } from './Error';

/**
 * Reusable default native call events.
 */
export const mockVoiceNativeEvents = {
  audioDevicesUpdated: {
    name: Constants.VoiceEventAudioDevicesUpdated,
    nativeEvent: {
      type: Constants.VoiceEventAudioDevicesUpdated,
      ...createNativeAudioDevicesInfo(),
    },
  },
  callInvite: {
    name: Constants.VoiceEventTypeValueIncomingCallInvite,
    nativeEvent: {
      type: Constants.VoiceEventTypeValueIncomingCallInvite,
      callInvite: createNativeCallInviteInfo(),
    },
  },
  // >>> FORK KAR-873 — see type/CallbackRequest.ts
  callbackRequested: {
    name: Constants.VoiceEventCallbackRequested,
    nativeEvent: {
      type: Constants.VoiceEventCallbackRequested,
      requestId: 'mock-callback-request-id',
      handle: '+15551234567',
    },
  },
  // <<< FORK
  error: {
    name: Constants.VoiceEventError,
    nativeEvent: {
      type: Constants.VoiceEventError,
      error: createNativeErrorInfo(),
    },
  },
  registered: {
    name: Constants.VoiceEventRegistered,
    nativeEvent: {
      type: Constants.VoiceEventRegistered,
    },
  },
  unregistered: {
    name: Constants.VoiceEventUnregistered,
    nativeEvent: {
      type: Constants.VoiceEventUnregistered,
    },
  },
};
