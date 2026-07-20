// FORK — KAR-443
// Owns: active-call foreground promotion independent of notification permission.
// Hooks into: VoiceService.
// Re-check on SDK bump: active-call service types and Core Telecom foreground requirements.
package com.twiliovoicereactnative;

import android.app.Notification;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;

final class ForkActiveCallForeground {
  private static final SDKLog logger = new SDKLog(ForkActiveCallForeground.class);

  private ForkActiveCallForeground() {}

  static boolean start(@NonNull VoiceService service,
                       int notificationId,
                       @NonNull Notification notification) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        service.startForeground(
          notificationId,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            | ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
      } else {
        service.startForeground(notificationId, notification);
      }
      return true;
    } catch (RuntimeException error) {
      logger.warning(error, "Failed to start active call foreground service");
      return false;
    }
  }
}
