package com.signature.atvremote.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.signature.atvremote.protocol.CompanionClient;

import java.util.HashSet;
import java.util.Set;

/**
 * Apple Arcade / tvOS Dark Glass Multi-Touch Wii Controller.
 * Adapts automatically to Portrait (20:9) and Landscape orientations on the Motorola Signature.
 */
public final class WiiPadView extends View {

    public interface GamepadListener {
        void onButtonStateChanged(int hidButton, boolean pressed);
    }

    private final GamepadListener listener;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stickBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stickKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnAPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnABPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnSysPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cardRect = new RectF();
    private final RectF pauseRect = new RectF();
    private final RectF abMacroRect = new RectF();

    private float stickCx, stickCy, stickRadius;
    private float btnACx, btnACy, btnARadius;
    private float btnBCx, btnBCy, btnBRadius;

    private final Set<Integer> activeButtons = new HashSet<>();
    private final Set<Integer> touchButtons = new HashSet<>();
    private final Set<Integer> tiltButtons = new HashSet<>();

    private int stickPointerId = MotionEvent.INVALID_POINTER_ID;
    private float stickOffsetX = 0f;
    private float stickOffsetY = 0f;

    private boolean turboEnabled = false;
    private boolean turboPhase = false;
    private final Handler turboHandler = new Handler(Looper.getMainLooper());
    private final Runnable turboRunnable = new Runnable() {
        @Override
        public void run() {
            if (!turboEnabled) return;
            turboPhase = !turboPhase;
            if (touchButtons.contains(CompanionClient.HID_SELECT)) {
                listener.onButtonStateChanged(CompanionClient.HID_SELECT, turboPhase);
            }
            if (touchButtons.contains(CompanionClient.HID_PLAY_PAUSE)) {
                listener.onButtonStateChanged(CompanionClient.HID_PLAY_PAUSE, turboPhase);
            }
            invalidate();
            turboHandler.postDelayed(this, 55L);
        }
    };

    public WiiPadView(Context context, GamepadListener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);

        Typeface mediumTf = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.0f));
        strokePaint.setColor(0xFF2E2E32);

        stickBasePaint.setStyle(Paint.Style.FILL);
        stickBasePaint.setColor(0xFF141417);

        stickKnobPaint.setStyle(Paint.Style.FILL);
        stickKnobPaint.setColor(0xFF2C2C2E);

        btnAPaint.setStyle(Paint.Style.FILL);
        btnBPaint.setStyle(Paint.Style.FILL);
        btnABPaint.setStyle(Paint.Style.FILL);
        btnSysPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(mediumTf);

        subTextPaint.setColor(0xFF8E8E93);
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setTypeface(mediumTf);
        subTextPaint.setLetterSpacing(0.06f);

        dotPaint.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setTurboEnabled(boolean enabled) {
        if (this.turboEnabled == enabled) return;
        this.turboEnabled = enabled;
        turboHandler.removeCallbacks(turboRunnable);
        if (enabled) {
            turboHandler.post(turboRunnable);
        } else {
            syncActiveButtons();
        }
        invalidate();
    }

    public boolean isTurboEnabled() {
        return turboEnabled;
    }

    public void setTiltDirections(boolean up, boolean down, boolean left, boolean right) {
        tiltButtons.clear();
        if (up) tiltButtons.add(CompanionClient.HID_UP);
        if (down) tiltButtons.add(CompanionClient.HID_DOWN);
        if (left) tiltButtons.add(CompanionClient.HID_LEFT);
        if (right) tiltButtons.add(CompanionClient.HID_RIGHT);
        syncActiveButtons();
        invalidate();
    }

    public void releaseAll() {
        touchButtons.clear();
        tiltButtons.clear();
        stickPointerId = MotionEvent.INVALID_POINTER_ID;
        stickOffsetX = 0f;
        stickOffsetY = 0f;
        syncActiveButtons();
        invalidate();
    }

    private void computeLayout(float w, float h) {
        boolean portrait = h > w * 1.05f;
        if (portrait) {
            stickCx = w * 0.50f;
            stickCy = h * 0.31f;
            stickRadius = Math.min(w * 0.32f, h * 0.22f);

            btnBCx = w * 0.30f;
            btnBCy = h * 0.70f;
            btnBRadius = Math.min(w * 0.16f, h * 0.115f);

            btnACx = w * 0.72f;
            btnACy = h * 0.65f;
            btnARadius = Math.min(w * 0.18f, h * 0.125f);

            float pillW = w * 0.39f;
            float pillH = dp(42f);
            pauseRect.set(w * 0.27f - pillW * 0.5f, h * 0.89f - pillH * 0.5f,
                    w * 0.27f + pillW * 0.5f, h * 0.89f + pillH * 0.5f);
            abMacroRect.set(w * 0.73f - pillW * 0.5f, h * 0.89f - pillH * 0.5f,
                    w * 0.73f + pillW * 0.5f, h * 0.89f + pillH * 0.5f);
        } else {
            stickCx = w * 0.25f;
            stickCy = h * 0.52f;
            stickRadius = Math.min(w * 0.20f, h * 0.36f);

            btnBCx = w * 0.64f;
            btnBCy = h * 0.62f;
            btnBRadius = Math.min(w * 0.105f, h * 0.20f);

            btnACx = w * 0.80f;
            btnACy = h * 0.38f;
            btnARadius = Math.min(w * 0.115f, h * 0.22f);

            float centerW = dp(108f);
            float centerH = dp(40f);
            pauseRect.set(w * 0.48f - centerW * 0.5f, h * 0.82f - centerH * 0.5f,
                    w * 0.48f + centerW * 0.5f, h * 0.82f + centerH * 0.5f);
            abMacroRect.set(w * 0.78f - centerW * 0.5f, h * 0.82f - centerH * 0.5f,
                    w * 0.78f + centerW * 0.5f, h * 0.82f + centerH * 0.5f);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float pad = dp(2f);
        cardRect.set(pad, pad, w - pad, h - pad);
        bgPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{0xFF1F1F23, 0xFF17171A, 0xFF121215},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        ));
        computeLayout(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        computeLayout(w, h);
        float corner = dp(34f);

        canvas.drawRoundRect(cardRect, corner, corner, bgPaint);
        strokePaint.setColor(0xFF2E2E32);
        canvas.drawRoundRect(cardRect, corner, corner, strokePaint);

        subTextPaint.setTextSize(dp(9.5f));
        subTextPaint.setColor(0xFF636366);
        canvas.drawText("NUNCHUK STICK  •  WIIMOTE MULTITOUCH", w * 0.5f, dp(22f), subTextPaint);

        // Nunchuk / 8-Way D-Pad Stick
        canvas.drawCircle(stickCx, stickCy, stickRadius, stickBasePaint);
        strokePaint.setColor(0xFF2C2C30);
        canvas.drawCircle(stickCx, stickCy, stickRadius, strokePaint);
        canvas.drawCircle(stickCx, stickCy, stickRadius * 0.46f, strokePaint);

        // Minimalist cardinal indicator dots on the stick ring
        float dotDist = stickRadius * 0.76f;
        float dotR = dp(2.8f);
        dotPaint.setColor(activeButtons.contains(CompanionClient.HID_UP) ? 0xFFFFFFFF : 0xFF525258);
        canvas.drawCircle(stickCx, stickCy - dotDist, dotR, dotPaint);
        dotPaint.setColor(activeButtons.contains(CompanionClient.HID_DOWN) ? 0xFFFFFFFF : 0xFF525258);
        canvas.drawCircle(stickCx, stickCy + dotDist, dotR, dotPaint);
        dotPaint.setColor(activeButtons.contains(CompanionClient.HID_LEFT) ? 0xFFFFFFFF : 0xFF525258);
        canvas.drawCircle(stickCx - dotDist, stickCy, dotR, dotPaint);
        dotPaint.setColor(activeButtons.contains(CompanionClient.HID_RIGHT) ? 0xFFFFFFFF : 0xFF525258);
        canvas.drawCircle(stickCx + dotDist, stickCy, dotR, dotPaint);

        float knobX = stickCx + stickOffsetX;
        float knobY = stickCy + stickOffsetY;
        boolean stickActive = stickPointerId != MotionEvent.INVALID_POINTER_ID;
        stickKnobPaint.setColor(stickActive ? 0xFF3A3A3E : 0xFF242428);
        canvas.drawCircle(knobX, knobY, stickRadius * 0.34f, stickKnobPaint);
        strokePaint.setColor(stickActive ? 0xFF8E8E93 : 0xFF38383C);
        canvas.drawCircle(knobX, knobY, stickRadius * 0.34f, strokePaint);

        // Pause (-) Pill
        boolean pausePressed = activeButtons.contains(CompanionClient.HID_MENU);
        btnSysPaint.setColor(pausePressed ? 0xFF3A3A3C : 0xFF242426);
        canvas.drawRoundRect(pauseRect, dp(21f), dp(21f), btnSysPaint);
        strokePaint.setColor(0xFF38383A);
        canvas.drawRoundRect(pauseRect, dp(21f), dp(21f), strokePaint);
        textPaint.setTextSize(dp(11.5f));
        textPaint.setColor(0xFFE5E5EA);
        canvas.drawText("PAUSA (−)", pauseRect.centerX(), pauseRect.centerY() + dp(4f), textPaint);

        // Action Buttons A & B
        boolean aPressed = activeButtons.contains(CompanionClient.HID_SELECT);
        boolean bPressed = activeButtons.contains(CompanionClient.HID_PLAY_PAUSE);

        btnAPaint.setColor(aPressed ? 0xFF0A84FF : 0xFF222630);
        canvas.drawCircle(btnACx, btnACy, btnARadius, btnAPaint);
        strokePaint.setColor(aPressed ? 0xFF64D2FF : 0xFF0A84FF);
        canvas.drawCircle(btnACx, btnACy, btnARadius, strokePaint);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(dp(26f));
        canvas.drawText("A", btnACx, btnACy + dp(4f), textPaint);
        subTextPaint.setTextSize(dp(9f));
        subTextPaint.setColor(0xFF8E8E93);
        canvas.drawText("ACELERAR", btnACx, btnACy + dp(20f), subTextPaint);

        btnBPaint.setColor(bPressed ? 0xFF30D158 : 0xFF1E2822);
        canvas.drawCircle(btnBCx, btnBCy, btnBRadius, btnBPaint);
        strokePaint.setColor(bPressed ? 0xFF63E682 : 0xFF30D158);
        canvas.drawCircle(btnBCx, btnBCy, btnBRadius, strokePaint);
        textPaint.setTextSize(dp(24f));
        canvas.drawText("B", btnBCx, btnBCy + dp(4f), textPaint);
        subTextPaint.setTextSize(dp(9f));
        subTextPaint.setColor(0xFF8E8E93);
        canvas.drawText("GATILLO", btnBCx, btnBCy + dp(19f), subTextPaint);

        // Combo A+B Pill
        boolean abBoth = aPressed && bPressed;
        btnABPaint.setColor(abBoth ? 0xFF5E5CE6 : 0xFF242426);
        canvas.drawRoundRect(abMacroRect, dp(21f), dp(21f), btnABPaint);
        strokePaint.setColor(abBoth ? 0xFF7D7AFF : 0xFF38383A);
        canvas.drawRoundRect(abMacroRect, dp(21f), dp(21f), strokePaint);
        textPaint.setTextSize(dp(11.5f));
        canvas.drawText("A + B SIMULTÁNEO", abMacroRect.centerX(), abMacroRect.centerY() + dp(4f), textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true);
        int action = event.getActionMasked();
        int actionIdx = event.getActionIndex();
        int pointerCount = event.getPointerCount();

        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());
        computeLayout(w, h);
        boolean portrait = h > w * 1.05f;

        Set<Integer> nextTouchButtons = new HashSet<>();

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }

        boolean stickFound = false;

        for (int i = 0; i < pointerCount; i++) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == actionIdx) {
                int releasedId = event.getPointerId(i);
                if (releasedId == stickPointerId) {
                    stickPointerId = MotionEvent.INVALID_POINTER_ID;
                    stickOffsetX = 0f;
                    stickOffsetY = 0f;
                }
                continue;
            }

            int pid = event.getPointerId(i);
            float px = event.getX(i);
            float py = event.getY(i);

            boolean inStickRegion = portrait
                    ? (py < h * 0.52f && Math.hypot(px - stickCx, py - stickCy) <= stickRadius * 1.35f)
                    : (px < w * 0.48f);

            if (pid == stickPointerId || (stickPointerId == MotionEvent.INVALID_POINTER_ID && inStickRegion)) {
                stickPointerId = pid;
                stickFound = true;
                float dx = px - stickCx;
                float dy = py - stickCy;
                float dist = (float) Math.hypot(dx, dy);
                float maxClamp = stickRadius * 0.68f;
                if (dist > maxClamp && dist > 0f) {
                    stickOffsetX = (dx / dist) * maxClamp;
                    stickOffsetY = (dy / dist) * maxClamp;
                } else {
                    stickOffsetX = dx;
                    stickOffsetY = dy;
                }

                float deadzone = stickRadius * 0.22f;
                if (dx < -deadzone) nextTouchButtons.add(CompanionClient.HID_LEFT);
                if (dx > deadzone) nextTouchButtons.add(CompanionClient.HID_RIGHT);
                if (dy < -deadzone) nextTouchButtons.add(CompanionClient.HID_UP);
                if (dy > deadzone) nextTouchButtons.add(CompanionClient.HID_DOWN);
                continue;
            }

            if (Math.hypot(px - btnACx, py - btnACy) <= btnARadius * 1.20f) {
                nextTouchButtons.add(CompanionClient.HID_SELECT);
            }
            if (Math.hypot(px - btnBCx, py - btnBCy) <= btnBRadius * 1.20f) {
                nextTouchButtons.add(CompanionClient.HID_PLAY_PAUSE);
            }
            if (abMacroRect.contains(px, py)) {
                nextTouchButtons.add(CompanionClient.HID_SELECT);
                nextTouchButtons.add(CompanionClient.HID_PLAY_PAUSE);
            }
            if (pauseRect.contains(px, py)) {
                nextTouchButtons.add(CompanionClient.HID_MENU);
            }
        }

        if (!stickFound) {
            stickPointerId = MotionEvent.INVALID_POINTER_ID;
            stickOffsetX = 0f;
            stickOffsetY = 0f;
        }

        if (!nextTouchButtons.equals(touchButtons)) {
            if (nextTouchButtons.size() > touchButtons.size()) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            touchButtons.clear();
            touchButtons.addAll(nextTouchButtons);
            syncActiveButtons();
        }
        invalidate();
        return true;
    }

    private void syncActiveButtons() {
        Set<Integer> combined = new HashSet<>(touchButtons);
        combined.addAll(tiltButtons);

        for (Integer oldBtn : new HashSet<>(activeButtons)) {
            if (!combined.contains(oldBtn)) {
                activeButtons.remove(oldBtn);
                if (listener != null) {
                    listener.onButtonStateChanged(oldBtn, false);
                }
            }
        }
        for (Integer newBtn : combined) {
            if (!activeButtons.contains(newBtn)) {
                activeButtons.add(newBtn);
                if (listener != null) {
                    listener.onButtonStateChanged(newBtn, true);
                }
            }
        }
    }
}
