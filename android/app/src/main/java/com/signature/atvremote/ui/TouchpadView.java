package com.signature.atvremote.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.signature.atvremote.protocol.CompanionClient;

/**
 * Apple TV System Remote Clickpad (iOS 18 / tvOS 18 Control Center aesthetic).
 * Brushed dark-titanium squircle surface with precision inner ring, cardinal micro-dots,
 * and soft silver touch highlight.
 */
public final class TouchpadView extends View {

    public interface TouchpadListener {
        void onHidButtonState(int buttonCode, boolean down);
        void onHidButtonClick(int buttonCode);
        void onTouchMove(int phase, float normX, float normY);
    }

    private final TouchpadListener listener;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeZonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private boolean dpadHoldMode = false;
    private float touchX = -1f;
    private float touchY = -1f;
    private float downX = 0f;
    private float downY = 0f;
    private float accumDx = 0f;
    private float accumDy = 0f;
    private long downTimeMs = 0L;
    private boolean isDragging = false;
    private int heldButton = -1;

    public TouchpadView(Context context, TouchpadListener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.0f));
        borderPaint.setColor(0xFF2E2E32);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(0.9f));
        ringPaint.setColor(0xFF2A2A2E);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFF636366);

        labelPaint.setColor(0xFF636366);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setLetterSpacing(0.08f);

        activeZonePaint.setStyle(Paint.Style.FILL);
        activeZonePaint.setColor(0x1FFFFFFF);
    }

    public void setDpadHoldMode(boolean dpadHoldMode) {
        if (this.dpadHoldMode != dpadHoldMode) {
            releaseHeldButton();
            this.dpadHoldMode = dpadHoldMode;
            invalidate();
        }
    }

    public boolean isDpadHoldMode() {
        return dpadHoldMode;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float pad = dp(2f);
        bounds.set(pad, pad, w - pad, h - pad);
        bgPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{0xFF1F1F23, 0xFF18181B, 0xFF131316},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float radius = Math.min(w, h) * 0.42f;
        float corner = dp(38f);

        // Matte dark-titanium squircle surface
        canvas.drawRoundRect(bounds, corner, corner, bgPaint);
        canvas.drawRoundRect(bounds, corner, corner, borderPaint);

        // Subtle Siri Remote clickpad rings
        canvas.drawCircle(cx, cy, radius, ringPaint);
        canvas.drawCircle(cx, cy, radius * 0.38f, ringPaint);

        // Highlight active held direction
        if (heldButton != -1) {
            float hx = cx;
            float hy = cy;
            float hr = radius * 0.26f;
            if (heldButton == CompanionClient.HID_UP) hy = cy - radius * 0.70f;
            else if (heldButton == CompanionClient.HID_DOWN) hy = cy + radius * 0.70f;
            else if (heldButton == CompanionClient.HID_LEFT) hx = cx - radius * 0.70f;
            else if (heldButton == CompanionClient.HID_RIGHT) hx = cx + radius * 0.70f;
            else if (heldButton == CompanionClient.HID_SELECT) hr = radius * 0.38f;
            canvas.drawCircle(hx, hy, hr, activeZonePaint);
        }

        // Four minimalist cardinal dots (like physical Siri Remote clickpad ring)
        float dotDist = radius * 0.76f;
        float dotR = dp(2.4f);
        dotPaint.setColor(heldButton == CompanionClient.HID_UP ? 0xFFFFFFFF : 0xFF636366);
        canvas.drawCircle(cx, cy - dotDist, dotR, dotPaint);
        dotPaint.setColor(heldButton == CompanionClient.HID_DOWN ? 0xFFFFFFFF : 0xFF636366);
        canvas.drawCircle(cx, cy + dotDist, dotR, dotPaint);
        dotPaint.setColor(heldButton == CompanionClient.HID_LEFT ? 0xFFFFFFFF : 0xFF636366);
        canvas.drawCircle(cx - dotDist, cy, dotR, dotPaint);
        dotPaint.setColor(heldButton == CompanionClient.HID_RIGHT ? 0xFFFFFFFF : 0xFF636366);
        canvas.drawCircle(cx + dotDist, cy, dotR, dotPaint);

        // Subtle center caption
        labelPaint.setTextSize(dp(10f));
        labelPaint.setColor(0xFF525258);
        String centerLabel = dpadHoldMode ? "D-PAD CONTINUO" : "DESLIZAR O PULSAR";
        canvas.drawText(centerLabel, cx, cy + dp(3.5f), labelPaint);

        if (touchX >= 0 && touchY >= 0) {
            float r = dp(52f);
            ripplePaint.setShader(new RadialGradient(
                    touchX, touchY, r,
                    new int[]{0x38FFFFFF, 0x12FFFFFF, Color.TRANSPARENT},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(touchX, touchY, r, ripplePaint);
        }
    }

    private int resolveZoneButton(float x, float y) {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float dx = x - cx;
        float dy = y - cy;
        float dist = (float) Math.hypot(dx, dy);
        float radius = Math.min(getWidth(), getHeight()) * 0.42f;

        if (dist < radius * 0.42f) {
            return CompanionClient.HID_SELECT;
        }
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx < 0 ? CompanionClient.HID_LEFT : CompanionClient.HID_RIGHT;
        } else {
            return dy < 0 ? CompanionClient.HID_UP : CompanionClient.HID_DOWN;
        }
    }

    private void setHeldButton(int newButton) {
        if (heldButton == newButton) return;
        if (heldButton != -1 && listener != null) {
            listener.onHidButtonState(heldButton, false);
        }
        heldButton = newButton;
        if (heldButton != -1 && listener != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            listener.onHidButtonState(heldButton, true);
        }
        invalidate();
    }

    private void releaseHeldButton() {
        if (heldButton != -1) {
            if (listener != null) {
                listener.onHidButtonState(heldButton, false);
            }
            heldButton = -1;
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());
        float normX = Math.max(0f, Math.min(1f, x / w));
        float normY = Math.max(0f, Math.min(1f, y / h));

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                touchX = x;
                touchY = y;
                downX = x;
                downY = y;
                accumDx = 0f;
                accumDy = 0f;
                downTimeMs = System.currentTimeMillis();
                isDragging = false;

                if (dpadHoldMode) {
                    setHeldButton(resolveZoneButton(x, y));
                } else {
                    float cx = w * 0.5f;
                    float cy = h * 0.5f;
                    float dist = (float) Math.hypot(x - cx, y - cy);
                    float radius = Math.min(w, h) * 0.42f;
                    if (dist >= radius * 0.58f) {
                        setHeldButton(resolveZoneButton(x, y));
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (listener != null) {
                            listener.onTouchMove(1, normX, normY);
                        }
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                touchX = x;
                touchY = y;

                if (dpadHoldMode) {
                    setHeldButton(resolveZoneButton(x, y));
                    invalidate();
                    return true;
                }

                float totalMove = (float) Math.hypot(x - downX, y - downY);
                if (totalMove > dp(14f)) {
                    if (heldButton != -1) {
                        releaseHeldButton();
                    }
                    isDragging = true;
                }

                if (isDragging && listener != null) {
                    listener.onTouchMove(2, normX, normY);
                    float stepThreshold = dp(42f);
                    float dx = x - (downX + accumDx);
                    float dy = y - (downY + accumDy);
                    if (Math.abs(dx) >= stepThreshold) {
                        int steps = (int) (dx / stepThreshold);
                        accumDx += steps * stepThreshold;
                        int btn = steps > 0 ? CompanionClient.HID_RIGHT : CompanionClient.HID_LEFT;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        listener.onHidButtonClick(btn);
                    }
                    if (Math.abs(dy) >= stepThreshold) {
                        int steps = (int) (dy / stepThreshold);
                        accumDy += steps * stepThreshold;
                        int btn = steps > 0 ? CompanionClient.HID_DOWN : CompanionClient.HID_UP;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        listener.onHidButtonClick(btn);
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                touchX = -1f;
                touchY = -1f;
                if (heldButton != -1) {
                    releaseHeldButton();
                } else if (!isDragging && (System.currentTimeMillis() - downTimeMs) < 550L) {
                    int zoneBtn = resolveZoneButton(x, y);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) {
                        listener.onHidButtonClick(zoneBtn);
                    }
                } else if (listener != null) {
                    listener.onTouchMove(4, normX, normY);
                }
                isDragging = false;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }
}
