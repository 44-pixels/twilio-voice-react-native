// FORK — KAR-873
// Owns: Legacy Android system call-log opt-in for Core-Telecom calls.
// Hooks into: ForkCoreTelecomManager.
// Re-check on SDK bump: Core-Telecom PhoneAccount handle ID/component names and whether
// EXTRA_LOG_SELF_MANAGED_CALLS remains necessary below API 36.
// Registration failure is non-fatal because OEM Telecom behavior must not prevent calls.
package com.twiliovoicereactnative

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

internal object ForkLegacyCallLog {
  private const val MINIMUM_SUPPORTED_SDK = Build.VERSION_CODES.P
  private const val MAXIMUM_SUPPORTED_SDK = Build.VERSION_CODES.VANILLA_ICE_CREAM
  private const val CORE_TELECOM_ACCOUNT_ID = "Jetpack"
  private const val LEGACY_CONNECTION_SERVICE =
    "androidx.core.telecom.internal.JetpackConnectionService"

  private val logger = SDKLog(ForkLegacyCallLog::class.java)

  fun enable(context: Context) {
    if (Build.VERSION.SDK_INT !in MINIMUM_SUPPORTED_SDK..MAXIMUM_SUPPORTED_SDK) return

    try {
      val telecomManager = context.getSystemService(TelecomManager::class.java)
      val accountHandle = telecomManager.ownSelfManagedPhoneAccounts
        .firstOrNull { isCoreTelecomAccount(context, it) }
      if (accountHandle == null) {
        logger.warning("Core-Telecom PhoneAccount unavailable for legacy call logging")
        return
      }

      val account = telecomManager.getPhoneAccount(accountHandle)
      if (account == null) {
        logger.warning("Core-Telecom PhoneAccount details unavailable for legacy call logging")
        return
      }

      val existingExtras = account.extras
      if (existingExtras?.getBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS) == true) return

      val extras = if (existingExtras == null) Bundle() else Bundle(existingExtras)
      extras.putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true)
      telecomManager.registerPhoneAccount(account.toBuilder().setExtras(extras).build())
    } catch (error: RuntimeException) {
      logger.warning(error, "Could not enable legacy Telecom call logging")
    }
  }

  private fun isCoreTelecomAccount(
    context: Context,
    accountHandle: PhoneAccountHandle,
  ): Boolean {
    val expectedClassName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      context.packageName
    } else {
      LEGACY_CONNECTION_SERVICE
    }

    return accountHandle.id == CORE_TELECOM_ACCOUNT_ID &&
      accountHandle.componentName.packageName == context.packageName &&
      accountHandle.componentName.className == expectedClassName
  }
}
