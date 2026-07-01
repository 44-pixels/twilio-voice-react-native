// FORK — KAR-443
// Owns: Android Telecom call-log callback trampoline into the host app deep link.
// Hooks into: AndroidManifest.xml/app.plugin.js.
// Re-check on SDK bump: TelecomManager.ACTION_CALL_BACK semantics.
package com.twiliovoicereactnative;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

public final class ForkCallBackActivity extends Activity {
  static final String ACTION_CALL_BACK = "android.telecom.action.CALL_BACK";
  static final String META_DATA_DEEPLINK_BASE_URL =
    "com.twiliovoicereactnative.CALL_BACK_DEEPLINK_BASE_URL";

  private static final SDKLog logger = new SDKLog(ForkCallBackActivity.class);

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    handle(getIntent());
    finish();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    handle(intent);
    finish();
  }

  private void handle(@Nullable Intent intent) {
    if (intent == null || !ACTION_CALL_BACK.equals(intent.getAction())) return;

    String deepLinkBaseUrl = deepLinkBaseUrl();
    if (deepLinkBaseUrl == null || deepLinkBaseUrl.length() == 0) {
      logger.warning("missing Telecom callback deep link base URL");
      return;
    }

    Uri deepLink = Uri.parse(deepLinkBaseUrl);
    Intent deepLinkIntent = new Intent(Intent.ACTION_VIEW, deepLink)
      .setPackage(getPackageName())
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

    try {
      startActivity(deepLinkIntent);
    } catch (ActivityNotFoundException | SecurityException e) {
      logger.warning(e, "failed to open Telecom callback deep link " + deepLink);
    }
  }

  @Nullable
  private String deepLinkBaseUrl() {
    try {
      ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(
        getPackageName(),
        PackageManager.GET_META_DATA);
      if (applicationInfo.metaData == null) return null;
      return applicationInfo.metaData.getString(META_DATA_DEEPLINK_BASE_URL);
    } catch (PackageManager.NameNotFoundException e) {
      logger.warning(e, "failed to read Telecom callback deep link metadata");
      return null;
    }
  }
}
