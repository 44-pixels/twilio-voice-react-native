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
import androidx.core.telecom.CallsManager
import com.twilio.voice.CallException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object ForkCoreTelecomManager {
  private val logger = SDKLog(ForkCoreTelecomManager::class.java)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val calls = ConcurrentHashMap<UUID, CallControlScope>()
  private val telecomAnswerRequests = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
  private val telecomDisconnectRequests = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())

  @JvmStatic
  fun isAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) ==
      PackageManager.PERMISSION_GRANTED

  @JvmStatic
  fun reportIncomingCall(context: Context, callRecord: CallRecordDatabase.CallRecord) {
    if (!isAvailable(context)) {
      logger.warning("Core Telecom unavailable; using notification-only incoming call path")
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
          logger.log("reported incoming call to Core Telecom ${callRecord.callSid}")
        }
      } catch (error: Exception) {
        logger.warning(error, "Core Telecom incoming call report failed")
      } finally {
        calls.remove(callRecord.uuid)
        telecomAnswerRequests.remove(callRecord.uuid)
        telecomDisconnectRequests.remove(callRecord.uuid)
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
    val control = calls[callRecord.uuid] ?: return
    if (telecomAnswerRequests.remove(callRecord.uuid)) return
    scope.launch { control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }
  }

  @JvmStatic
  fun markActive(callRecord: CallRecordDatabase.CallRecord) {
    val control = calls[callRecord.uuid] ?: return
    scope.launch { control.setActive() }
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

  private fun disconnect(callRecord: CallRecordDatabase.CallRecord, cause: Int) {
    if (telecomDisconnectRequests.remove(callRecord.uuid)) return
    val control = calls[callRecord.uuid] ?: return
    scope.launch {
      control.disconnect(DisconnectCause(cause))
      calls.remove(callRecord.uuid)
      telecomAnswerRequests.remove(callRecord.uuid)
    }
  }

  private fun disconnectAction(callRecord: CallRecordDatabase.CallRecord): String =
    if (callRecord.voiceCall == null) Constants.ACTION_REJECT_CALL else Constants.ACTION_CALL_DISCONNECT

  private fun normalizedAddress(callRecord: CallRecordDatabase.CallRecord): String {
    val from = callRecord.callInvite?.from
    if (from.isNullOrEmpty()) return "unknown"
    return if (from.startsWith("client:")) from.substring("client:".length) else from
  }
}
