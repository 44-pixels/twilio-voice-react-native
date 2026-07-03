// FORK — KAR-443
// Owns: Jetpack Telecom CallsManager integration for native VoIP surfaces and unified call history.
// Hooks into: ForkTelecomManager.
// Re-check on SDK bump: androidx.core:core-telecom CallsManager.addCall / CallControlScope APIs.
package com.twiliovoicereactnative

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.twilio.audioswitch.AudioDevice
import com.twilio.voice.CallException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface ForkTelecomRouteCallback {
  fun onComplete(routed: Boolean)
}

internal object ForkCoreTelecomManager {
  private const val ENDPOINT_WAIT_TIMEOUT_MILLIS = 1_500L

  private val logger = SDKLog(ForkCoreTelecomManager::class.java)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val calls = ConcurrentHashMap<UUID, CallControlScope>()
  private val callsBySid = ConcurrentHashMap<String, CallControlScope>()
  private val pendingCallSids = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
  private val telecomAnswerRequests = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
  private val telecomDisconnectRequests = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
  private val appAnswerRequests = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
  private val appActiveRequests = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
  private val appDisconnectRequests = ConcurrentHashMap<UUID, Int>()

  @JvmStatic
  fun isAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) ==
      PackageManager.PERMISSION_GRANTED

  @JvmStatic
  fun reportIncomingCall(context: Context, callRecord: CallRecordDatabase.CallRecord) {
    if (!isAvailable(context)) {
      logger.warning("Core Telecom unavailable; using notification-only incoming call path")
      clearPendingState(callRecord)
      return
    }

    val callSid = callRecord.callSid
    if (!pendingCallSids.add(callSid)) {
      logger.debug("reportIncomingCall: already reporting Core Telecom call $callSid")
      return
    }

    val appContext = context.applicationContext
    scope.launch {
      try {
        val callsManager = CallsManager(appContext)
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)

        val phoneNumber = normalizedAddress(callRecord)
        val displayName = ForkContactLookup.resolveForIncoming(
          appContext,
          phoneNumber,
          callRecord.callInvite,
        ).displayName
        val attributes = CallAttributesCompat(
          displayName = displayName,
          address = Uri.fromParts("tel", phoneNumber, null),
          direction = CallAttributesCompat.DIRECTION_INCOMING,
          callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
          callCapabilities = 0,
          isLogExcluded = false,
        )

        callsManager.addCall(
          attributes,
          onAnswer = {
            telecomAnswerRequests.add(callRecord.uuid)
            ForkTelecomManager.sendVoiceServiceAction(
              appContext,
              Constants.ACTION_ACCEPT_CALL,
              callRecord,
            )
          },
          onDisconnect = {
            telecomDisconnectRequests.add(callRecord.uuid)
            ForkTelecomManager.sendVoiceServiceAction(
              appContext,
              disconnectAction(callRecord),
              callRecord,
            )
          },
          onSetActive = {},
          onSetInactive = {},
        ) {
          calls[callRecord.uuid] = this
          callsBySid[callRecord.callSid] = this
          logger.log("reported incoming call to Core Telecom ${callRecord.callSid}")
          drainPendingState(callRecord, this)
        }
      } catch (error: Exception) {
        logger.warning(error, "Core Telecom incoming call report failed")
      } finally {
        calls.remove(callRecord.uuid)
        callsBySid.remove(callRecord.callSid)
        pendingCallSids.remove(callRecord.callSid)
        telecomAnswerRequests.remove(callRecord.uuid)
        telecomDisconnectRequests.remove(callRecord.uuid)
        appAnswerRequests.remove(callRecord.uuid)
        appActiveRequests.remove(callRecord.uuid)
        appDisconnectRequests.remove(callRecord.uuid)
      }
    }
  }

  @JvmStatic
  fun cancelIncomingCall(callSid: String?) {
    if (callSid == null || callSid.isEmpty()) return
    val callRecord = VoiceApplicationProxy.getCallRecordDatabase()
      .get(CallRecordDatabase.CallRecord(callSid)) ?: return
    disconnect(callRecord, DisconnectCause.MISSED)
  }

  @JvmStatic
  fun markAnswered(callRecord: CallRecordDatabase.CallRecord) {
    if (telecomAnswerRequests.remove(callRecord.uuid)) {
      logger.debug("markAnswered: already answered by Telecom ${callRecord.uuid}")
      return
    }

    val control = controlFor(callRecord)
    if (control == null) {
      if (pendingCallSids.contains(callRecord.callSid)) {
        logger.debug("markAnswered: queued until Core Telecom call is ready ${callRecord.uuid}")
        appAnswerRequests.add(callRecord.uuid)
      }
      return
    }

    scope.launch { answerFromApp(callRecord, control) }
  }

  @JvmStatic
  fun markActive(callRecord: CallRecordDatabase.CallRecord) {
    val control = controlFor(callRecord)
    if (control == null) {
      if (pendingCallSids.contains(callRecord.callSid)) {
        if (appAnswerRequests.contains(callRecord.uuid)) {
          logger.debug("markActive: covered by pending answer ${callRecord.uuid}")
          return
        }
        logger.debug("markActive: queued until Core Telecom call is ready ${callRecord.uuid}")
        appActiveRequests.add(callRecord.uuid)
      }
      return
    }

    scope.launch { setActiveFromApp(callRecord, control) }
  }

  @JvmStatic
  fun markRejected(callRecord: CallRecordDatabase.CallRecord) {
    disconnect(callRecord, DisconnectCause.REJECTED)
  }

  @JvmStatic
  fun markDisconnected(
    callRecord: CallRecordDatabase.CallRecord,
    callException: CallException?,
  ) {
    disconnect(
      callRecord,
      if (callException == null) DisconnectCause.LOCAL else DisconnectCause.REMOTE,
    )
  }

  @JvmStatic
  fun selectAudioDevice(
    audioDevice: AudioDevice,
    callback: ForkTelecomRouteCallback,
  ): Boolean {
    val endpointType = endpointTypeFor(audioDevice) ?: return false
    val controls = calls.values.toList()
    if (controls.size != 1) {
      logger.warning("Core Telecom audio routing requires exactly one call; found ${controls.size}")
      return false
    }
    val control = controls.first()

    scope.launch {
      try {
        val endpoints = withTimeoutOrNull(ENDPOINT_WAIT_TIMEOUT_MILLIS) {
          control.availableEndpoints.first()
        }
        val endpoint = endpoints?.firstOrNull { endpoint -> endpoint.type == endpointType }
        if (endpoint == null) {
          logger.warning("Core Telecom endpoint unavailable for type $endpointType")
          callback.onComplete(false)
          return@launch
        }

        when (val result = control.requestEndpointChange(endpoint)) {
          is CallControlResult.Success -> {
            logger.log("requested Core Telecom endpoint type $endpointType")
            callback.onComplete(true)
          }
          is CallControlResult.Error -> {
            logger.warning(
              "Core Telecom endpoint request failed for type $endpointType: ${result.errorCode}",
            )
            callback.onComplete(false)
          }
        }
      } catch (error: Exception) {
        logger.warning(error, "Core Telecom endpoint request failed")
        callback.onComplete(false)
      }
    }

    return true
  }

  private fun endpointTypeFor(audioDevice: AudioDevice): Int? = when (audioDevice) {
    is AudioDevice.Speakerphone -> CallEndpointCompat.TYPE_SPEAKER
    is AudioDevice.Earpiece -> CallEndpointCompat.TYPE_EARPIECE
    is AudioDevice.BluetoothHeadset -> CallEndpointCompat.TYPE_BLUETOOTH
    is AudioDevice.WiredHeadset -> CallEndpointCompat.TYPE_WIRED_HEADSET
    else -> null
  }

  private fun drainPendingState(
    callRecord: CallRecordDatabase.CallRecord,
    control: CallControlScope,
  ) {
    val disconnectCause = appDisconnectRequests.remove(callRecord.uuid)
    if (disconnectCause != null) {
      clearPendingState(callRecord)
      scope.launch { disconnectFromApp(callRecord, control, disconnectCause) }
      return
    }

    if (appAnswerRequests.remove(callRecord.uuid)) {
      appActiveRequests.remove(callRecord.uuid)
      scope.launch { answerFromApp(callRecord, control) }
      return
    }
    if (appActiveRequests.remove(callRecord.uuid)) {
      scope.launch { setActiveFromApp(callRecord, control) }
    }
  }

  private suspend fun answerFromApp(
    callRecord: CallRecordDatabase.CallRecord,
    control: CallControlScope,
  ) {
    try {
      when (val result = control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)) {
        is CallControlResult.Success -> {
          logger.log("answered Core Telecom call ${callRecord.uuid}")
          setActiveFromApp(callRecord, control)
        }
        is CallControlResult.Error -> logger.warning(
          "Core Telecom answer failed for ${callRecord.uuid}: ${result.errorCode}",
        )
      }
    } catch (error: Exception) {
      logger.warning(error, "Core Telecom answer failed")
    }
  }

  private suspend fun setActiveFromApp(
    callRecord: CallRecordDatabase.CallRecord,
    control: CallControlScope,
  ) {
    try {
      when (val result = control.setActive()) {
        is CallControlResult.Success -> logger.log("set Core Telecom call active ${callRecord.uuid}")
        is CallControlResult.Error -> logger.warning(
          "Core Telecom setActive failed for ${callRecord.uuid}: ${result.errorCode}",
        )
      }
    } catch (error: Exception) {
      logger.warning(error, "Core Telecom setActive failed")
    }
  }

  private fun disconnect(callRecord: CallRecordDatabase.CallRecord, cause: Int) {
    if (telecomDisconnectRequests.remove(callRecord.uuid)) return
    val control = controlFor(callRecord)
    if (control == null) {
      if (pendingCallSids.contains(callRecord.callSid)) {
        logger.debug("disconnect: queued until Core Telecom call is ready ${callRecord.uuid}")
        appDisconnectRequests[callRecord.uuid] = cause
      } else {
        clearPendingState(callRecord)
      }
      return
    }
    scope.launch { disconnectFromApp(callRecord, control, cause) }
  }

  private suspend fun disconnectFromApp(
    callRecord: CallRecordDatabase.CallRecord,
    control: CallControlScope,
    cause: Int,
  ) {
    try {
      when (val result = control.disconnect(DisconnectCause(cause))) {
        is CallControlResult.Success -> {
          logger.log("disconnected Core Telecom call ${callRecord.uuid}")
          clearCallState(callRecord)
        }
        is CallControlResult.Error -> logger.warning(
          "Core Telecom disconnect failed for ${callRecord.uuid}: ${result.errorCode}",
        )
      }
    } catch (error: Exception) {
      logger.warning(error, "Core Telecom disconnect failed")
    }
  }

  private fun clearCallState(callRecord: CallRecordDatabase.CallRecord) {
    calls.remove(callRecord.uuid)
    callsBySid.remove(callRecord.callSid)
    pendingCallSids.remove(callRecord.callSid)
    telecomAnswerRequests.remove(callRecord.uuid)
    clearPendingState(callRecord)
  }

  private fun clearPendingState(callRecord: CallRecordDatabase.CallRecord) {
    appAnswerRequests.remove(callRecord.uuid)
    appActiveRequests.remove(callRecord.uuid)
    appDisconnectRequests.remove(callRecord.uuid)
  }

  private fun controlFor(callRecord: CallRecordDatabase.CallRecord): CallControlScope? =
    calls[callRecord.uuid] ?: callsBySid[callRecord.callSid]

  private fun disconnectAction(callRecord: CallRecordDatabase.CallRecord): String =
    if (callRecord.voiceCall == null) Constants.ACTION_REJECT_CALL else Constants.ACTION_CALL_DISCONNECT

  private fun normalizedAddress(callRecord: CallRecordDatabase.CallRecord): String {
    val from = callRecord.callInvite?.from
    if (from.isNullOrEmpty()) return "unknown"
    return if (from.startsWith("client:")) from.substring("client:".length) else from
  }
}
