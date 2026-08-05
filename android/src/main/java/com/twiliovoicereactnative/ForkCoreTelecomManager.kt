// FORK — KAR-443, KAR-873
// Owns: Jetpack Telecom lifecycle, call arbitration, hold/resume, and audio routing.
// Hooks into: ForkTelecomManager.
// Re-check on SDK bump: CallsManager.addCall callback contracts, foreground support,
// and CallControlScope endpoint/state APIs.
package com.twiliovoicereactnative

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.twilio.voice.CallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

fun interface ForkTelecomRouteCallback {
  fun onComplete(routed: Boolean)
}

internal object ForkCoreTelecomManager {
  private data class PendingActions(
    val disconnectCause: Int?,
    val answer: Boolean,
    val activate: Boolean,
  )

  const val ANSWER_PROCEED = 0
  const val ANSWER_PENDING = 1

  const val ROUTE_NOT_ACTIVE = 0
  const val ROUTE_PENDING = 1
  const val ROUTE_UNKNOWN = 2

  private val logger = SDKLog(ForkCoreTelecomManager::class.java)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val stateLock = Any()

  private var registeringUuid: UUID? = null
  private var registrationJob: Job? = null
  private var managedRecord: CallRecordDatabase.CallRecord? = null
  private var control: CallControlScope? = null
  private var direction: CallRecordDatabase.CallRecord.Direction? = null
  private var pendingAppAnswer = false
  private var pendingOutgoingActive = false
  private var pendingDisconnectCause: Int? = null
  private var appAnswerInFlight = false
  private var answerPermitUuid: UUID? = null
  private var telecomDisconnectUuid: UUID? = null
  private var audioReleaseUuid: UUID? = null
  private var audioReleaseCallback: Runnable? = null
  private var availableEndpoints: List<CallEndpointCompat> = emptyList()
  private var currentEndpoint: CallEndpointCompat? = null

  @JvmStatic
  fun isAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) ==
      PackageManager.PERMISSION_GRANTED

  @JvmStatic
  fun reportIncomingCall(context: Context, callRecord: CallRecordDatabase.CallRecord) {
    reportCall(context, callRecord, CallRecordDatabase.CallRecord.Direction.INCOMING)
  }

  @JvmStatic
  fun reportOutgoingCall(context: Context, callRecord: CallRecordDatabase.CallRecord) {
    reportCall(context, callRecord, CallRecordDatabase.CallRecord.Direction.OUTGOING)
  }

  @JvmStatic
  fun isTelecomAudioOwner(callRecord: CallRecordDatabase.CallRecord): Boolean =
    isTelecomAudioOwner(callRecord.uuid)

  @JvmStatic
  fun isTelecomAudioOwner(uuid: UUID): Boolean =
    synchronized(stateLock) {
      registeringUuid == uuid || managedRecord?.uuid == uuid
    }

  @JvmStatic
  fun stateForLog(uuid: UUID?): String = synchronized(stateLock) {
    when {
      uuid == null -> "NONE"
      registeringUuid == uuid -> "REGISTERING"
      managedRecord?.uuid == uuid && control == null -> "MANAGED_PENDING"
      managedRecord?.uuid == uuid -> "MANAGED_ACTIVE"
      else -> "NONE"
    }
  }

  @JvmStatic
  fun authorizeAnswer(callRecord: CallRecordDatabase.CallRecord): Int {
    val callControl: CallControlScope
    synchronized(stateLock) {
      if (!isAnswerable(callRecord)) return ANSWER_PENDING
      if (!ownsTelecomState(callRecord.uuid)) return ANSWER_PROCEED
      if (answerPermitUuid == callRecord.uuid) {
        answerPermitUuid = null
        return ANSWER_PROCEED
      }

      val currentControl = control
      if (currentControl == null) {
        pendingAppAnswer = true
        return ANSWER_PENDING
      }
      if (appAnswerInFlight) return ANSWER_PENDING

      appAnswerInFlight = true
      callControl = currentControl
    }

    scope.launch { answerFromApp(callRecord, callControl) }
    return ANSWER_PENDING
  }

  @JvmStatic
  fun cancelIncomingCall(callSid: String?) {
    if (callSid.isNullOrEmpty()) return
    val callRecord = VoiceApplicationProxy.getCallRecordDatabase()
      .get(CallRecordDatabase.CallRecord(callSid)) ?: return
    disconnect(callRecord, DisconnectCause.MISSED)
  }

  @JvmStatic
  fun cleanupTerminalCall(uuid: UUID) {
    cancelRegistration(uuid, terminal = true)
  }

  @JvmStatic
  fun markAnswered(callRecord: CallRecordDatabase.CallRecord) {
    // Answer authorization is completed before CallInvite.accept().
  }

  @JvmStatic
  fun markActive(callRecord: CallRecordDatabase.CallRecord) {
    val callControl = synchronized(stateLock) {
      if (managedRecord?.uuid != callRecord.uuid) return
      if (direction != CallRecordDatabase.CallRecord.Direction.OUTGOING) return
      val currentControl = control
      if (currentControl == null) pendingOutgoingActive = true
      currentControl
    } ?: return

    scope.launch { setActiveFromApp(callRecord, callControl) }
  }

  @JvmStatic
  fun markRejected(callRecord: CallRecordDatabase.CallRecord) {
    val fromTelecom = synchronized(stateLock) {
      telecomDisconnectUuid == callRecord.uuid
    }
    if (fromTelecom) {
      cancelRegistration(callRecord.uuid, terminal = true)
    } else {
      disconnect(callRecord, DisconnectCause.REJECTED)
    }
  }

  @JvmStatic
  fun markDisconnected(
    callRecord: CallRecordDatabase.CallRecord,
    callException: CallException?,
  ) {
    markDisconnected(callRecord, callException, null)
  }

  @JvmStatic
  fun markDisconnected(
    callRecord: CallRecordDatabase.CallRecord,
    callException: CallException?,
    onAudioReleased: Runnable?,
  ) {
    val disconnectFromApp = synchronized(stateLock) {
      if (!ownsTelecomState(callRecord.uuid)) return@synchronized null
      if (onAudioReleased != null) {
        audioReleaseUuid = callRecord.uuid
        audioReleaseCallback = onAudioReleased
      }
      telecomDisconnectUuid != callRecord.uuid
    }
    if (disconnectFromApp == null) {
      onAudioReleased?.run()
      return
    }
    if (!disconnectFromApp) {
      cancelRegistration(callRecord.uuid, terminal = true)
      return
    }

    disconnect(
      callRecord,
      if (callException == null) DisconnectCause.LOCAL else DisconnectCause.REMOTE,
    )
  }

  @JvmStatic
  fun audioDeviceInfo(): WritableMap = synchronized(stateLock) {
    serializeAudioDeviceInfo(availableEndpoints, currentEndpoint)
  }

  @JvmStatic
  fun selectAudioDevice(
    endpointUuid: String,
    callback: ForkTelecomRouteCallback,
  ): Int {
    val routeState = synchronized(stateLock) {
      Triple(registeringUuid != null || managedRecord != null, control, availableEndpoints)
    }
    if (!routeState.first) return ROUTE_NOT_ACTIVE
    val callControl = routeState.second ?: return ROUTE_NOT_ACTIVE
    val endpoint = routeState.third.firstOrNull { endpointUuid(it) == endpointUuid }
      ?: return ROUTE_UNKNOWN

    scope.launch {
      try {
        when (val result = callControl.requestEndpointChange(endpoint)) {
          is CallControlResult.Success -> callback.onComplete(true)
          is CallControlResult.Error -> {
            ForkSentryReporter.reportWarning("voice.telecom.audio_endpoint_change_failed", null)
            logger.warning("Core Telecom endpoint request failed: ${result.errorCode}")
            callback.onComplete(false)
          }
        }
      } catch (error: Exception) {
        ForkSentryReporter.reportWarning("voice.telecom.audio_endpoint_change_failed", error)
        logger.warning(error, "Core Telecom endpoint request failed")
        callback.onComplete(false)
      }
    }

    return ROUTE_PENDING
  }

  private fun reportCall(
    context: Context,
    callRecord: CallRecordDatabase.CallRecord,
    callDirection: CallRecordDatabase.CallRecord.Direction,
  ) {
    if (!ForkSingleCallSession.isOwner(callRecord.uuid) ||
      ForkCallLifecycleCoordinator.twilioState(callRecord.uuid)?.isTerminal() == true
    ) return

    if (!isAvailable(context)) {
      logger.warning("Core Telecom is unavailable; refusing unmanaged call")
      ForkTwilioVoiceThread.run {
        if (callRecord.voiceCall == null) {
          VoiceApplicationProxy.getVoiceServiceApi().rejectCall(callRecord)
        } else {
          disconnectTwilioCall(callRecord)
        }
      }
      return
    }

    synchronized(stateLock) {
      if (!ForkSingleCallSession.isOwner(callRecord.uuid) ||
        ForkCallLifecycleCoordinator.twilioState(callRecord.uuid)?.isTerminal() == true
      ) return
      if (registeringUuid == callRecord.uuid || managedRecord?.uuid == callRecord.uuid) return
      if (registeringUuid != null || managedRecord != null) {
        logger.warning("Core Telecom already owns another call")
        return
      }
      registeringUuid = callRecord.uuid
      managedRecord = callRecord
      direction = callDirection
    }

    val appContext = context.applicationContext
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        val callsManager = CallsManager(appContext)
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        ForkLegacyCallLog.enable(appContext)

        callsManager.addCall(
          attributes(appContext, callRecord, callDirection),
          onAnswer = {
            require(callDirection == CallRecordDatabase.CallRecord.Direction.INCOMING)
            ForkTwilioVoiceThread.runBlocking {
              synchronized(stateLock) {
                check(isAnswerable(callRecord)) { "Twilio invite is no longer answerable" }
                answerPermitUuid = callRecord.uuid
              }
              VoiceApplicationProxy.getVoiceServiceApi().acceptCall(callRecord)
              check(callRecord.voiceCall != null) { "Twilio incoming call was not accepted" }
            }
          },
          onDisconnect = {
            synchronized(stateLock) { telecomDisconnectUuid = callRecord.uuid }
            try {
              ForkTwilioVoiceThread.runBlocking {
                val api = VoiceApplicationProxy.getVoiceServiceApi()
                if (callRecord.voiceCall == null) api.rejectCall(callRecord) else api.disconnect(callRecord)
              }
            } finally {
              cancelRegistration(callRecord.uuid)
            }
          },
          onSetActive = {
            ForkSentryReporter.recordAudioSession(
              "voice.audio_session.telecom_activated",
              callRecord.uuid,
            )
            ForkTwilioVoiceThread.runBlocking {
              requireNotNull(callRecord.voiceCall).hold(false)
            }
          },
          onSetInactive = {
            ForkSentryReporter.recordAudioSession(
              "voice.audio_session.telecom_deactivated",
              callRecord.uuid,
            )
            ForkTwilioVoiceThread.runBlocking {
              requireNotNull(callRecord.voiceCall).hold(true)
            }
          },
        ) {
          val callControl = this
          ForkCallbackRequestStore.rememberCall(
            appContext,
            getCallId().uuid,
            callbackHandle(callRecord),
          )
          val pendingActions = synchronized(stateLock) {
            if (managedRecord?.uuid != callRecord.uuid) {
              PendingActions(null, false, false)
            } else {
              registeringUuid = null
              control = callControl
              val actions = PendingActions(
                pendingDisconnectCause,
                pendingAppAnswer,
                pendingOutgoingActive,
              )
              pendingDisconnectCause = null
              pendingAppAnswer = false
              pendingOutgoingActive = false
              actions
            }
          }
          launch {
            isMuted.collect { muted ->
              ForkTwilioVoiceThread.runBlocking {
                callRecord.voiceCall?.mute(muted)
              }
            }
          }
          launch {
            availableEndpoints.collect { endpoints ->
              updateAvailableEndpoints(callRecord.uuid, endpoints)
            }
          }
          launch {
            currentCallEndpoint.collect { endpoint ->
              updateCurrentEndpoint(callRecord.uuid, endpoint)
            }
          }
          when {
            pendingActions.disconnectCause != null -> scope.launch {
              disconnectFromApp(callRecord, callControl, pendingActions.disconnectCause)
            }
            pendingActions.answer -> {
              synchronized(stateLock) { appAnswerInFlight = true }
              scope.launch { answerFromApp(callRecord, callControl) }
            }
            pendingActions.activate -> scope.launch {
              setActiveFromApp(callRecord, callControl)
            }
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        ForkSentryReporter.reportError("voice.telecom.registration_failed", error)
        logger.warning(error, "Core Telecom call registration failed")
        val failedState = synchronized(stateLock) {
          val matches = ownsTelecomState(callRecord.uuid)
          val answerable = matches && isAnswerable(callRecord)
          val hasCall = matches && ForkCallRecordSnapshot.capture(callRecord).hasCall
          clearTelecomState(callRecord.uuid)
          Pair(answerable, hasCall)
        }
        ForkTwilioVoiceThread.runBlocking {
          when {
            failedState.first -> VoiceApplicationProxy.getVoiceServiceApi().rejectCall(callRecord)
            failedState.second -> disconnectTwilioCall(callRecord)
          }
        }
      } finally {
        val releaseCallback = synchronized(stateLock) {
          clearTelecomState(callRecord.uuid)
          takeAudioReleaseCallback(callRecord.uuid)
        }
        emitAudioDevicesUpdated()
        releaseCallback?.run()
        releaseOwnerIfSettled(callRecord)
      }
    }
    val started = synchronized(stateLock) {
      if (managedRecord?.uuid != callRecord.uuid) {
        false
      } else {
        registrationJob = job
        job.start()
      }
    }
    if (!started) job.cancel()
  }

  private fun callbackHandle(
    callRecord: CallRecordDatabase.CallRecord,
  ): String? = when (callRecord.direction) {
    CallRecordDatabase.CallRecord.Direction.INCOMING -> callRecord.callInvite?.from
    CallRecordDatabase.CallRecord.Direction.OUTGOING ->
      callRecord.customParameters?.get("to")?.takeIf { it.isNotEmpty() }
  }

  private fun attributes(
    context: Context,
    callRecord: CallRecordDatabase.CallRecord,
    callDirection: CallRecordDatabase.CallRecord.Direction,
  ): CallAttributesCompat {
    val address = if (callDirection == CallRecordDatabase.CallRecord.Direction.INCOMING) {
      normalizedIncomingAddress(callRecord)
    } else {
      callRecord.callRecipient.ifEmpty { "unknown" }
    }
    val displayName = if (callDirection == CallRecordDatabase.CallRecord.Direction.INCOMING) {
      ForkContactLookup.resolveForIncoming(
        context,
        address,
        callRecord.callInvite,
      ).displayName
    } else {
      callRecord.notificationDisplayName?.takeIf { it.isNotEmpty() } ?: address
    }

    return CallAttributesCompat(
      displayName = displayName,
      address = Uri.fromParts("tel", address, null),
      direction = if (callDirection == CallRecordDatabase.CallRecord.Direction.INCOMING) {
        CallAttributesCompat.DIRECTION_INCOMING
      } else {
        CallAttributesCompat.DIRECTION_OUTGOING
      },
      callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
      callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
      isLogExcluded = false,
    )
  }

  private suspend fun answerFromApp(
    callRecord: CallRecordDatabase.CallRecord,
    callControl: CallControlScope,
  ) {
    try {
      when (val result = callControl.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)) {
        is CallControlResult.Success -> ForkTwilioVoiceThread.runBlocking {
          val shouldAccept = synchronized(stateLock) {
            appAnswerInFlight = false
            if (!isAnswerable(callRecord) || !ownsTelecomState(callRecord.uuid)) {
              false
            } else {
              answerPermitUuid = callRecord.uuid
              true
            }
          }
          if (shouldAccept) VoiceApplicationProxy.getVoiceServiceApi().acceptCall(callRecord)
        }
        is CallControlResult.Error -> {
          ForkSentryReporter.reportError("voice.telecom.answer_failed", null)
          failAnswer(
            callRecord,
            "Core Telecom rejected the answer: ${result.errorCode}",
          )
        }
      }
    } catch (error: Exception) {
      ForkSentryReporter.reportError("voice.telecom.answer_failed", error)
      logger.warning(error, "Core Telecom answer failed")
      failAnswer(callRecord, "Core Telecom could not answer the call")
    }
  }

  private suspend fun failAnswer(
    callRecord: CallRecordDatabase.CallRecord,
    message: String,
  ) {
    ForkTwilioVoiceThread.runBlocking {
      val shouldReject = synchronized(stateLock) {
        appAnswerInFlight = false
        isAnswerable(callRecord) && ownsTelecomState(callRecord.uuid)
      }
      if (shouldReject) {
        callRecord.failCallAcceptedPromise(message)
        ForkCallInviteRejection.markReason(
          callRecord,
          ForkCallInviteRejection.Reason.TELECOM_ANSWER_FAILED,
        )
        VoiceApplicationProxy.getVoiceServiceApi().rejectCall(callRecord)
      }
    }
  }

  private suspend fun setActiveFromApp(
    callRecord: CallRecordDatabase.CallRecord,
    callControl: CallControlScope,
  ) {
    try {
      when (val result = callControl.setActive()) {
        is CallControlResult.Success -> Unit
        is CallControlResult.Error -> {
          ForkSentryReporter.reportError("voice.telecom.set_active_failed", null)
          logger.warning("Core Telecom setActive failed: ${result.errorCode}")
          disconnectTwilioCall(callRecord)
        }
      }
    } catch (error: Exception) {
      ForkSentryReporter.reportError("voice.telecom.set_active_failed", error)
      logger.warning(error, "Core Telecom setActive failed")
      disconnectTwilioCall(callRecord)
    }
  }

  private fun disconnectTwilioCall(callRecord: CallRecordDatabase.CallRecord) {
    val call = callRecord.voiceCall ?: return
    ForkSentryReporter.recordDisconnectBoundary(
      "voice.call.disconnect_invocation.before",
      callRecord.uuid,
      call,
    )
    ForkTwilioVoiceThread.runBlocking { call.disconnect() }
    ForkSentryReporter.recordDisconnectBoundary(
      "voice.call.disconnect_invocation.after",
      callRecord.uuid,
      call,
    )
  }

  private fun disconnect(callRecord: CallRecordDatabase.CallRecord, cause: Int) {
    val callControl = synchronized(stateLock) {
      if (!ownsTelecomState(callRecord.uuid)) return
      val currentControl = control
      if (currentControl == null) pendingDisconnectCause = cause
      currentControl
    } ?: return

    scope.launch { disconnectFromApp(callRecord, callControl, cause) }
  }

  private suspend fun disconnectFromApp(
    callRecord: CallRecordDatabase.CallRecord,
    callControl: CallControlScope,
    cause: Int,
  ) {
    try {
      when (val result = callControl.disconnect(DisconnectCause(cause))) {
        is CallControlResult.Success -> {
          // addCall's endpoint collectors remain active after Telecom ends the call.
          // Clear the completed session and cancel their owning job.
          cancelRegistration(callRecord.uuid, terminal = true)
        }
        is CallControlResult.Error -> {
          ForkSentryReporter.reportError("voice.telecom.disconnect_failed", null)
          logger.warning("Core Telecom disconnect failed: ${result.errorCode}")
          cancelRegistration(callRecord.uuid, terminal = true)
        }
      }
    } catch (error: Exception) {
      ForkSentryReporter.reportError("voice.telecom.disconnect_failed", error)
      logger.warning(error, "Core Telecom disconnect failed")
      cancelRegistration(callRecord.uuid, terminal = true)
    }
  }

  private fun cancelRegistration(uuid: UUID, terminal: Boolean = false) {
    var releaseCallback: Runnable? = null
    val job = synchronized(stateLock) {
      if (!ownsTelecomState(uuid)) return
      val currentJob = registrationJob
      clearTelecomState(uuid)
      releaseCallback = takeAudioReleaseCallback(uuid)
      currentJob
    }

    emitAudioDevicesUpdated()
    releaseCallback?.run()
    if (terminal) ForkSingleCallSession.releaseIfOwner(uuid)
    job?.cancel()
  }

  private fun updateAvailableEndpoints(
    uuid: UUID,
    endpoints: List<CallEndpointCompat>,
  ) {
    val supportedEndpoints = endpoints.filter { endpointType(it) != null }
    val changed = synchronized(stateLock) {
      if (managedRecord?.uuid != uuid || availableEndpoints == supportedEndpoints) {
        false
      } else {
        availableEndpoints = supportedEndpoints
        true
      }
    }
    if (changed) emitAudioDevicesUpdated()
  }

  private fun updateCurrentEndpoint(uuid: UUID, endpoint: CallEndpointCompat) {
    val supportedEndpoint = endpoint.takeIf { endpointType(it) != null }
    val changed = synchronized(stateLock) {
      if (managedRecord?.uuid != uuid || currentEndpoint == supportedEndpoint) {
        false
      } else {
        currentEndpoint = supportedEndpoint
        true
      }
    }
    if (changed) emitAudioDevicesUpdated()
  }

  private fun emitAudioDevicesUpdated() {
    val audioDeviceInfo = audioDeviceInfo()
    audioDeviceInfo.putString(
      CommonConstants.VoiceEventType,
      CommonConstants.VoiceEventAudioDevicesUpdated,
    )
    VoiceApplicationProxy.getJSEventEmitter().sendEvent(
      CommonConstants.ScopeVoice,
      audioDeviceInfo,
    )
  }

  private fun serializeAudioDeviceInfo(
    endpoints: List<CallEndpointCompat>,
    selectedEndpoint: CallEndpointCompat?,
  ): WritableMap {
    val serializedEndpoints = Arguments.createArray()
    endpoints.forEach { serializedEndpoints.pushMap(serializeEndpoint(it)) }

    return Arguments.createMap().apply {
      putArray(CommonConstants.AudioDeviceKeyAudioDevices, serializedEndpoints)
      selectedEndpoint?.let {
        putMap(CommonConstants.AudioDeviceKeySelectedDevice, serializeEndpoint(it))
      }
    }
  }

  private fun serializeEndpoint(endpoint: CallEndpointCompat): WritableMap =
    Arguments.createMap().apply {
      putString(CommonConstants.AudioDeviceKeyUuid, endpointUuid(endpoint))
      putString(CommonConstants.AudioDeviceKeyName, endpoint.name.toString())
      putString(CommonConstants.AudioDeviceKeyType, requireNotNull(endpointType(endpoint)))
    }

  private fun endpointUuid(endpoint: CallEndpointCompat): String =
    endpoint.identifier.uuid.toString()

  private fun endpointType(endpoint: CallEndpointCompat): String? = when (endpoint.type) {
    CallEndpointCompat.TYPE_SPEAKER -> CommonConstants.AudioDeviceKeySpeaker
    CallEndpointCompat.TYPE_BLUETOOTH -> CommonConstants.AudioDeviceKeyBluetooth
    CallEndpointCompat.TYPE_EARPIECE,
    CallEndpointCompat.TYPE_WIRED_HEADSET -> CommonConstants.AudioDeviceKeyEarpiece
    else -> null
  }

  private fun takeAudioReleaseCallback(uuid: UUID): Runnable? {
    if (audioReleaseUuid != uuid) return null
    val callback = audioReleaseCallback
    audioReleaseUuid = null
    audioReleaseCallback = null
    return callback
  }

  private fun hasLiveTwilioState(
    callRecord: CallRecordDatabase.CallRecord,
  ): Boolean = ForkCallRecordSnapshot.capture(callRecord).hasLiveTwilioState()

  private fun releaseOwnerIfSettled(callRecord: CallRecordDatabase.CallRecord) {
    if (!hasLiveTwilioState(callRecord)) {
      ForkSingleCallSession.releaseIfOwner(callRecord.uuid)
    }
  }

  private fun isAnswerable(callRecord: CallRecordDatabase.CallRecord): Boolean {
    val snapshot = ForkCallRecordSnapshot.capture(callRecord)
    return ForkSingleCallSession.isOwner(snapshot.uuid) &&
      snapshot.activeInvite &&
      !snapshot.hasCall
  }

  private fun ownsTelecomState(uuid: UUID): Boolean =
    registeringUuid == uuid || managedRecord?.uuid == uuid

  private fun clearTelecomState(uuid: UUID) {
    if (!ownsTelecomState(uuid)) return
    registeringUuid = null
    registrationJob = null
    managedRecord = null
    control = null
    direction = null
    pendingAppAnswer = false
    pendingOutgoingActive = false
    pendingDisconnectCause = null
    appAnswerInFlight = false
    answerPermitUuid = null
    telecomDisconnectUuid = null
    availableEndpoints = emptyList()
    currentEndpoint = null
  }

  private fun normalizedIncomingAddress(callRecord: CallRecordDatabase.CallRecord): String {
    val from = callRecord.callInvite?.from
    if (from.isNullOrEmpty()) return "unknown"
    return if (from.startsWith("client:")) from.substring("client:".length) else from
  }
}
