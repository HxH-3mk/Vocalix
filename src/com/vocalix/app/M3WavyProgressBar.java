package com.vocalix.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Authentic Material 3 Expressive Wavy Progress Indicator (Android 15 M3E).
 * Draws an undulating sinusoidal wave that flows smoothly when loading.
 */
public class M3WavyProgressBar extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final Path trackPath = new Path();

    private float phase = 0f;
    private ValueAnimator animator;
    private float density;

    private static final float STROKE_WIDTH_DP = 4.0f;
    private static final float WAVELENGTH_DP = 32.0f;
    private static final float AMPLITUDE_DP = 3.8f;

    public M3WavyProgressBar(Context context) {
        super(context);
        init();
    }

    public M3WavyProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public M3WavyProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeJoin(Paint.Join.ROUND);
        trackPaint.setStrokeWidth(STROKE_WIDTH_DP * density);

        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setStrokeWidth(STROKE_WIDTH_DP * density);
    }

    private int getPrimaryColor() {
        boolean amoled = AmoledThemeHelper.isAmoled(getContext());
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = nightMode == Configuration.UI_MODE_NIGHT_YES;

        if (amoled || isDark) {
            return Color.parseColor("#A8C7FA");
        } else {
            return Color.parseColor("#0B57D0");
        }
    }

    private void startAnimation() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1200);
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        if (!animator.isRunning()) {
            animator.start();
        }
    }

    private void stopAnimation() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            startAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredH = (int) (18 * density);
        int h = resolveSize(desiredH, heightMeasureSpec);
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float centerY = height / 2.0f;
        float wavelength = WAVELENGTH_DP * density;
        float amplitude = AMPLITUDE_DP * density;

        int primaryColor = getPrimaryColor();

        // 1. Background Track (Subtle straight line with low alpha)
        trackPaint.setColor(primaryColor);
        trackPaint.setAlpha(40);
        float pad = STROKE_WIDTH_DP * density;
        canvas.drawLine(pad, centerY, width - pad, centerY, trackPaint);

        // 2. Animated Sinusoidal Wave
        wavePaint.setColor(primaryColor);
        wavePaint.setAlpha(255);

        wavePath.reset();
        boolean isFirst = true;

        float step = 2.0f * density;
        for (float x = pad; x <= width - pad; x += step) {
            float normalizedX = (x / wavelength) - phase;
            float y = (float) (centerY + amplitude * Math.sin(normalizedX * 2.0 * Math.PI));

            if (isFirst) {
                wavePath.moveTo(x, y);
                isFirst = false;
            } else {
                wavePath.lineTo(x, y);
            }
        }

        canvas.drawPath(wavePath, wavePaint);
    }
}

