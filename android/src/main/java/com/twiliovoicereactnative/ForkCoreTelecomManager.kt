// FORK — KAR-443
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
import com.twilio.audioswitch.AudioDevice
import com.twilio.voice.CallException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

  private const val ENDPOINT_WAIT_TIMEOUT_MILLIS = 1_500L
  private const val CALL_ENDED_WAIT_TIMEOUT_MILLIS = 4_000L

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
  private var telecomDisconnectFinished: CompletableDeferred<Unit>? = null

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
    if (!fromTelecom) disconnect(callRecord, DisconnectCause.REJECTED)
  }

  @JvmStatic
  fun markDisconnected(
    callRecord: CallRecordDatabase.CallRecord,
    callException: CallException?,
  ) {
    val telecomCompletion = synchronized(stateLock) {
      if (telecomDisconnectUuid == callRecord.uuid) telecomDisconnectFinished else null
    }
    if (telecomCompletion != null) {
      telecomCompletion.complete(Unit)
      return
    }

    val disconnectedByTelecom = synchronized(stateLock) {
      telecomDisconnectUuid == callRecord.uuid
    }
    if (disconnectedByTelecom) return

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
    val routeState = synchronized(stateLock) {
      Pair(registeringUuid != null || managedRecord != null, control)
    }
    if (!routeState.first) return false
    val callControl = routeState.second
    if (callControl == null) {
      callback.onComplete(false)
      return true
    }

    scope.launch {
      try {
        val endpoints = withTimeoutOrNull(ENDPOINT_WAIT_TIMEOUT_MILLIS) {
          callControl.availableEndpoints.first()
        }
        val endpoint = endpoints?.firstOrNull { it.type == endpointType }
        if (endpoint == null) {
          logger.warning("Core Telecom endpoint unavailable for type $endpointType")
          callback.onComplete(false)
          return@launch
        }

        when (val result = callControl.requestEndpointChange(endpoint)) {
          is CallControlResult.Success -> callback.onComplete(true)
          is CallControlResult.Error -> {
            logger.warning("Core Telecom endpoint request failed: ${result.errorCode}")
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

  private fun reportCall(
    context: Context,
    callRecord: CallRecordDatabase.CallRecord,
    callDirection: CallRecordDatabase.CallRecord.Direction,
  ) {
    if (!isAvailable(context)) return

    synchronized(stateLock) {
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
      var preserveFallbackOwner = false
      try {
        val callsManager = CallsManager(appContext)
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)

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
            val waitsForCallEndedSound = callRecord.voiceCall != null
            val callEnded = if (waitsForCallEndedSound) CompletableDeferred<Unit>() else null
            synchronized(stateLock) {
              telecomDisconnectUuid = callRecord.uuid
              telecomDisconnectFinished = callEnded
            }
            ForkTwilioVoiceThread.runBlocking {
              val api = VoiceApplicationProxy.getVoiceServiceApi()
              if (callRecord.voiceCall == null) api.rejectCall(callRecord) else api.disconnect(callRecord)
            }
            if (callEnded != null) {
              val didFinish = withTimeoutOrNull(CALL_ENDED_WAIT_TIMEOUT_MILLIS) {
                callEnded.await()
                true
              } ?: false
              if (!didFinish) logger.warning("Timed out waiting for call-ended sound")
            }
          },
          onSetActive = {
            ForkTwilioVoiceThread.runBlocking {
              requireNotNull(callRecord.voiceCall).hold(false)
            }
          },
          onSetInactive = {
            ForkTwilioVoiceThread.runBlocking {
              requireNotNull(callRecord.voiceCall).hold(true)
            }
          },
        ) {
          val callControl = this
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
      } catch (error: Exception) {
        logger.warning(error, "Core Telecom call registration failed")
        val fallbackState = synchronized(stateLock) {
          val matches = ownsTelecomState(callRecord.uuid)
          val resumeAnswer = matches && pendingAppAnswer && isAnswerable(callRecord)
          val liveIncoming = matches && isAnswerable(callRecord)
          val liveOutgoing = matches &&
            callDirection == CallRecordDatabase.CallRecord.Direction.OUTGOING &&
            callRecord.voiceCall != null &&
            callRecord.voiceCall?.state != com.twilio.voice.Call.State.DISCONNECTED
          clearTelecomState(callRecord.uuid)
          Pair(resumeAnswer, liveIncoming || liveOutgoing)
        }
        preserveFallbackOwner = fallbackState.second
        if (fallbackState.first) {
          ForkTwilioVoiceThread.runBlocking {
            VoiceApplicationProxy.getVoiceServiceApi().acceptCall(callRecord)
          }
        } else if (fallbackState.second && callRecord.voiceCall != null) {
          VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().activate()
        }
      } finally {
        synchronized(stateLock) { clearTelecomState(callRecord.uuid) }
        if (!preserveFallbackOwner) ForkSingleCallSession.release(callRecord.uuid)
      }
    }
    synchronized(stateLock) {
      if (managedRecord?.uuid == callRecord.uuid) registrationJob = job
    }
    job.start()
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
        is CallControlResult.Error -> failAnswer(
          callRecord,
          "Core Telecom rejected the answer: ${result.errorCode}",
        )
      }
    } catch (error: Exception) {
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
          logger.warning("Core Telecom setActive failed: ${result.errorCode}")
          ForkTwilioVoiceThread.runBlocking { callRecord.voiceCall?.disconnect() }
        }
      }
    } catch (error: Exception) {
      logger.warning(error, "Core Telecom setActive failed")
      ForkTwilioVoiceThread.runBlocking { callRecord.voiceCall?.disconnect() }
    }
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
          synchronized(stateLock) { clearTelecomState(callRecord.uuid) }
          ForkSingleCallSession.release(callRecord.uuid)
        }
        is CallControlResult.Error -> {
          logger.warning("Core Telecom disconnect failed: ${result.errorCode}")
          cancelRegistration(callRecord.uuid)
        }
      }
    } catch (error: Exception) {
      logger.warning(error, "Core Telecom disconnect failed")
      cancelRegistration(callRecord.uuid)
    }
  }

  private fun cancelRegistration(uuid: UUID) {
    val job = synchronized(stateLock) {
      if (!ownsTelecomState(uuid)) return
      registrationJob
    }
    job?.cancel()
  }

  private fun endpointTypeFor(audioDevice: AudioDevice): Int? = when (audioDevice) {
    is AudioDevice.Speakerphone -> CallEndpointCompat.TYPE_SPEAKER
    is AudioDevice.Earpiece -> CallEndpointCompat.TYPE_EARPIECE
    is AudioDevice.BluetoothHeadset -> CallEndpointCompat.TYPE_BLUETOOTH
    is AudioDevice.WiredHeadset -> CallEndpointCompat.TYPE_WIRED_HEADSET
    else -> null
  }

  private fun isAnswerable(callRecord: CallRecordDatabase.CallRecord): Boolean =
    ForkSingleCallSession.isOwner(callRecord.uuid) &&
      callRecord.callInvite != null &&
      callRecord.callInviteState == CallRecordDatabase.CallRecord.CallInviteState.ACTIVE &&
      callRecord.voiceCall == null

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
    telecomDisconnectFinished?.complete(Unit)
    telecomDisconnectFinished = null
  }

  private fun normalizedIncomingAddress(callRecord: CallRecordDatabase.CallRecord): String {
    val from = callRecord.callInvite?.from
    if (from.isNullOrEmpty()) return "unknown"
    return if (from.startsWith("client:")) from.substring("client:".length) else from
  }
}
