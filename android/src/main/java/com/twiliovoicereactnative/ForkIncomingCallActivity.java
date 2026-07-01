// FORK — KAR-443
// Owns: tiny lock-screen full-screen-intent target for incoming calls.
// Hooks into: ForkFullScreenIncomingCall and AndroidManifest.xml/app.plugin.js.
// Re-check on SDK bump: VoiceService action names and MSG_KEY_UUID extra.
package com.twiliovoicereactnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

public final class ForkIncomingCallActivity extends Activity {
  private static final SDKLog logger = new SDKLog(ForkIncomingCallActivity.class);
  private static final Set<WeakReference<ForkIncomingCallActivity>> activeActivities =
    new CopyOnWriteArraySet<>();

  @Nullable
  private UUID callUuid;
  @Nullable
  private String callSid;

  static void finishFor(@Nullable CallRecordDatabase.CallRecord callRecord) {
    if (callRecord == null) return;
    finishForCallSid(callRecord.getCallSid());
  }

  static void finishForCallSid(@Nullable String callSid) {
    if (callSid == null || callSid.length() == 0) return;
    for (WeakReference<ForkIncomingCallActivity> reference : activeActivities) {
      ForkIncomingCallActivity activity = reference.get();
      if (activity == null) {
        activeActivities.remove(reference);
      } else if (callSid.equals(activity.callSid)) {
        activity.runOnUiThread(activity::finishFromCallStateChange);
      }
    }
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Window window = getWindow();
    window.addFlags(
      WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_FULLSCREEN);

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    }

    View decorView = window.getDecorView();
    decorView.setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

    bindCallIdentity(getIntent());
    showCallOrFinish();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    bindCallIdentity(intent);
    showCallOrFinish();
  }

  @Override
  protected void onStart() {
    super.onStart();
    activeActivities.add(new WeakReference<>(this));
    if (!hasActiveIncomingInvite()) finish();
  }

  @Override
  protected void onStop() {
    removeActivityReferences();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    removeActivityReferences();
    super.onDestroy();
  }

  private void bindCallIdentity(@NonNull Intent intent) {
    Object uuidExtra = intent.getSerializableExtra(Constants.MSG_KEY_UUID);
    callUuid = uuidExtra instanceof UUID ? (UUID) uuidExtra : null;
    CallRecordDatabase.CallRecord callRecord = callRecord();
    callSid = callRecord == null
      ? ForkNotificationIdentity.callSidFromIntent(intent)
      : callRecord.getCallSid();
  }

  private void showCallOrFinish() {
    CallRecordDatabase.CallRecord callRecord = callRecord();
    setContentView(content(callRecord));
    if (callRecord == null || !hasActiveIncomingInvite()) finish();
  }

  @NonNull
  private FrameLayout content(@Nullable CallRecordDatabase.CallRecord callRecord) {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.rgb(25, 48, 44));

    GlowView glow = new GlowView(this);
    root.addView(glow, new FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.MATCH_PARENT));

    LinearLayout callerBlock = new LinearLayout(this);
    callerBlock.setOrientation(LinearLayout.VERTICAL);
    callerBlock.setGravity(Gravity.CENTER);

    TextView caller = new TextView(this);
    caller.setText(caller(callRecord));
    caller.setGravity(Gravity.CENTER);
    caller.setTextColor(Color.WHITE);
    caller.setTextSize(40);
    caller.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    caller.setSingleLine(false);
    caller.setIncludeFontPadding(false);
    caller.setLineSpacing(0, 1.0f);
    callerBlock.addView(caller, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT));

    LinearLayout viaClara = new LinearLayout(this);
    viaClara.setOrientation(LinearLayout.HORIZONTAL);
    viaClara.setGravity(Gravity.CENTER);

    FrameLayout badge = new FrameLayout(this);
    badge.setBackground(circle(Color.WHITE));
    ImageView badgeIcon = new ImageView(this);
    badgeIcon.setImageResource(R.drawable.fork_ic_clara_10);
    badgeIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    badge.addView(badgeIcon, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
    LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(20), dp(20));
    viaClara.addView(badge, badgeParams);

    TextView viaLabel = new TextView(this);
    viaLabel.setText("Call via Clara");
    viaLabel.setGravity(Gravity.CENTER);
    viaLabel.setTextColor(Color.argb(128, 255, 255, 255));
    viaLabel.setTextSize(20);
    viaLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    viaLabel.setIncludeFontPadding(false);
    LinearLayout.LayoutParams viaLabelParams = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT);
    viaLabelParams.setMargins(dp(8), 0, 0, 0);
    viaClara.addView(viaLabel, viaLabelParams);

    LinearLayout.LayoutParams viaParams = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT);
    viaParams.setMargins(0, dp(12), 0, 0);
    callerBlock.addView(viaClara, viaParams);

    FrameLayout.LayoutParams callerBlockParams = new FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.WRAP_CONTENT,
      Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    callerBlockParams.setMargins(dp(24), dp(148), dp(24), 0);
    root.addView(callerBlock, callerBlockParams);

    LinearLayout actionRow = new LinearLayout(this);
    actionRow.setOrientation(LinearLayout.HORIZONTAL);
    actionRow.setGravity(Gravity.CENTER);

    actionRow.addView(actionButton(false, "Decline", Color.rgb(255, 56, 60), () -> {
      sendAction(Constants.ACTION_REJECT_CALL, callRecord);
      finish();
    }), new LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT));

    View spacer = new View(this);
    actionRow.addView(spacer, new LinearLayout.LayoutParams(dp(80), 1));

    actionRow.addView(actionButton(true, "Answer", Color.rgb(52, 199, 89), () -> {
      sendAction(Constants.ACTION_ACCEPT_CALL, callRecord);
      finish();
    }), new LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT));

    FrameLayout.LayoutParams actionRowParams = new FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.WRAP_CONTENT,
      Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    actionRowParams.setMargins(0, 0, 0, dp(72));
    root.addView(actionRow, actionRowParams);

    root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      int height = bottom - top;
      int callerTop = Math.max(dp(104), Math.round(height * 0.161f));
      int actionBottom = Math.max(dp(48), Math.round(height * 0.078f));
      callerBlockParams.setMargins(dp(24), callerTop, dp(24), 0);
      actionRowParams.setMargins(0, 0, 0, actionBottom);
      callerBlock.setLayoutParams(callerBlockParams);
      actionRow.setLayoutParams(actionRowParams);
    });

    return root;
  }

  @NonNull
  private LinearLayout actionButton(boolean answer,
                                    @NonNull String label,
                                    int color,
                                    @NonNull Runnable action) {
    LinearLayout wrapper = new LinearLayout(this);
    wrapper.setOrientation(LinearLayout.VERTICAL);
    wrapper.setGravity(Gravity.CENTER);

    FrameLayout circle = new FrameLayout(this);
    circle.setBackground(circle(color));
    circle.setContentDescription(label);
    circle.setClickable(true);
    circle.setFocusable(true);
    circle.setOnClickListener(view -> action.run());

    ImageView icon = new ImageView(this);
    icon.setImageResource(answer ? R.drawable.fork_ic_call_40 : R.drawable.fork_ic_call_end_40);
    icon.setColorFilter(Color.WHITE);
    icon.setScaleType(ImageView.ScaleType.CENTER);
    FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
    circle.addView(icon, iconParams);

    wrapper.addView(circle, new LinearLayout.LayoutParams(dp(80), dp(80)));

    TextView caption = new TextView(this);
    caption.setText(label);
    caption.setGravity(Gravity.CENTER);
    caption.setTextColor(Color.WHITE);
    caption.setTextSize(16);
    caption.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    caption.setIncludeFontPadding(false);
    LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT);
    captionParams.setMargins(0, dp(12), 0, 0);
    wrapper.addView(caption, captionParams);

    return wrapper;
  }

  @NonNull
  private GradientDrawable circle(int color) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setShape(GradientDrawable.OVAL);
    drawable.setColor(color);
    return drawable;
  }

  private void sendAction(String action, @Nullable CallRecordDatabase.CallRecord callRecord) {
    if (callRecord == null) {
      logger.warning("sendAction: no call record");
      return;
    }
    ForkTelecomManager.sendVoiceServiceAction(this, action, callRecord);
  }

  private String caller(@Nullable CallRecordDatabase.CallRecord callRecord) {
    if (callRecord == null || callRecord.getCallInvite() == null) return "Unknown Caller";
    return ForkContactLookup.resolveForIncoming(this, callRecord).displayName;
  }

  private boolean hasActiveIncomingInvite() {
    CallRecordDatabase.CallRecord callRecord = callRecord();
    return callRecord != null
      && callRecord.getCallInvite() != null
      && callRecord.getCallInviteState() == CallRecordDatabase.CallRecord.CallInviteState.ACTIVE;
  }

  private void finishFromCallStateChange() {
    if (isFinishing() || isDestroyed()) return;
    logger.debug("finishing incoming-call activity for settled invite");
    finish();
  }

  private void removeActivityReferences() {
    for (WeakReference<ForkIncomingCallActivity> reference : activeActivities) {
      ForkIncomingCallActivity activity = reference.get();
      if (activity == null || activity == this) {
        activeActivities.remove(reference);
      }
    }
  }

  @Nullable
  private CallRecordDatabase.CallRecord callRecord() {
    if (callUuid == null) return null;
    return VoiceApplicationProxy.getCallRecordDatabase()
      .get(new CallRecordDatabase.CallRecord(callUuid));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static final class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GlowView(@NonNull Context context) {
      super(context);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
      super.onDraw(canvas);
      float radius = Math.max(getWidth() * 1.35f, getHeight() * 0.95f);
      paint.setStyle(Paint.Style.FILL);
      paint.setShader(new RadialGradient(
        getWidth() * 0.5f,
        getHeight() * 0.98f,
        radius,
        new int[] {
          Color.rgb(69, 129, 79),
          Color.argb(210, 69, 129, 79),
          Color.argb(80, 69, 129, 79),
          Color.TRANSPARENT
        },
        new float[] { 0f, 0.36f, 0.70f, 1f },
        Shader.TileMode.CLAMP));
      canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
      paint.setShader(null);
    }
  }


}
