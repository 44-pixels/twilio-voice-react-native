package com.twiliovoicereactnative

import android.app.Activity
import android.os.Bundle
import expo.modules.core.interfaces.ReactActivityLifecycleListener

import android.Manifest
import android.content.Intent
import android.widget.Toast
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver

class ExpoActivityLifecycleListener : ReactActivityLifecycleListener {
    private var voiceActivityProxy: VoiceActivityProxy? = null
    // >>> FORK KAR-591 — re-post the incoming heads-up on window-focus-gained, see ForkIncomingCallFocus.java
    private var windowFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var decorView: View? = null
    // <<< FORK

    override fun onCreate(activity: Activity?, savedInstanceState: Bundle?) {
        if (activity != null) {
            // >>> FORK KAR-448 — see ForkLockScreenFlags.java
            ForkLockScreenFlags.registerActivity(activity)
            // <<< FORK
            // >>> FORK KAR-591 — arm on tap + re-post the heads-up when the window gains focus (settled + unlocked), see ForkIncomingCallFocus.java
            activity.intent?.let { ForkIncomingCallFocus.armFromIntent(it) }
            val appCtx = activity.applicationContext
            activity.window?.decorView?.let { decor ->
                val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus) ForkIncomingCallFocus.onWindowFocusGained(appCtx)
                }
                decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                windowFocusListener = listener
                decorView = decor
            }
            // <<< FORK
            voiceActivityProxy = VoiceActivityProxy(
                activity
            ) { permission ->
                if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
                    Toast.makeText(
                        activity,
                        "Microphone permissions needed. Please allow in your application settings.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) &&
                    Manifest.permission.BLUETOOTH_CONNECT.equals(permission)
                ) {
                    Toast.makeText(
                        activity,
                        "Bluetooth permissions needed. Please allow in your application settings.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if ((Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) &&
                    Manifest.permission.POST_NOTIFICATIONS.equals(permission)
                ) {
                    Toast.makeText(
                        activity,
                        "Notification permissions needed. Please allow in your application settings.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        voiceActivityProxy?.onCreate(savedInstanceState)

        return super.onCreate(activity, savedInstanceState)
    }

    override fun onNewIntent(intent: Intent?): Boolean {
        // >>> FORK KAR-591 — arm the heads-up re-post on notification tap, see ForkIncomingCallFocus.java
        intent?.let { ForkIncomingCallFocus.armFromIntent(it) }
        // <<< FORK
        voiceActivityProxy?.onNewIntent(intent)

        return super.onNewIntent(intent)
    }

    override fun onDestroy(activity: Activity?) {
        voiceActivityProxy?.onDestroy()
        // >>> FORK KAR-448 — see ForkLockScreenFlags.java
        if (activity != null) {
            ForkLockScreenFlags.unregisterActivity(activity)
        }
        // <<< FORK
        // >>> FORK KAR-591 — detach the window-focus listener, see ForkIncomingCallFocus.java
        windowFocusListener?.let { listener ->
            decorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
        }
        windowFocusListener = null
        decorView = null
        // <<< FORK

        return super.onDestroy(activity)
    }
}
