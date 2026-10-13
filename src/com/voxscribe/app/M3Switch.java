package com.voxscribe.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Checkable;

/**
 * Authentic Material 3 Switch with animated pill track, expanding thumb,
 * and checkmark icon (âœ“) inside the thumb when active.
 */
public class M3Switch extends View implements Checkable {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(View buttonView, boolean isChecked);
    }

    private boolean isChecked = false;
    private float progress = 0f; // 0.0f = unchecked, 1.0f = checked
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path checkPath = new Path();
    private final RectF trackRect = new RectF();

    // Standard Material 3 switch dimensions in dp
    private static final float TRACK_WIDTH_DP = 52f;
    private static final float TRACK_HEIGHT_DP = 32f;
    private static final float THUMB_RADIUS_UNCHECKED_DP = 8f;   // 16dp diameter
    private static final float THUMB_RADIUS_CHECKED_DP = 12f;    // 24dp diameter

    private float density;

    public M3Switch(Context context) {
        super(context);
        init();
    }

    public M3Switch(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public M3Switch(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);

        trackPaint.setStyle(Paint.Style.FILL);

        trackStrokePaint.setStyle(Paint.Style.STROKE);
        trackStrokePaint.setStrokeWidth(dp(2f));

        thumbPaint.setStyle(Paint.Style.FILL);

        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setStrokeWidth(dp(2.2f));
    }

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = (int) (dp(TRACK_WIDTH_DP) + getPaddingLeft() + getPaddingRight());
        int desiredH = (int) (dp(TRACK_HEIGHT_DP) + getPaddingTop() + getPaddingBottom());

        int w = resolveSize(desiredW, widthMeasureSpec);
        int h = resolveSize(desiredH, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean performClick() {
        toggle();
        playSoundEffect(SoundEffectConstants.CLICK);
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public void setChecked(boolean checked) {
        setChecked(checked, true);
    }

    public void setChecked(boolean checked, boolean animate) {
        boolean changed = (this.isChecked != checked);
        this.isChecked = checked;
        animateTo(this.isChecked ? 1f : 0f, animate);
        if (changed && listener != null) {
            listener.onCheckedChanged(this, this.isChecked);
        }
    }

    public void setCheckedSilently(boolean checked) {
        this.isChecked = checked;
        animateTo(this.isChecked ? 1f : 0f, false);
    }

    @Override
    public boolean isChecked() {
        return isChecked;
    }

    @Override
    public void toggle() {
        setChecked(!isChecked, true);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    private void animateTo(float target, boolean animate) {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        if (!animate) {
            progress = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private boolean isNightMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private int blendColors(int from, int to, float ratio) {
        float inverse = 1f - ratio;
        float a = Color.alpha(from) * inverse + Color.alpha(to) * ratio;
        float r = Color.red(from) * inverse + Color.red(to) * ratio;
        float g = Color.green(from) * inverse + Color.green(to) * ratio;
        float b = Color.blue(from) * inverse + Color.blue(to) * ratio;
        return Color.argb((int) a, (int) r, (int) g, (int) b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        boolean night = isNightMode();
        boolean amoled = AmoledThemeHelper.isAmoled(getContext());

        float trackW = dp(TRACK_WIDTH_DP);
        float trackH = dp(TRACK_HEIGHT_DP);
        float trackR = trackH / 2f;

        float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - trackW) / 2f;
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - trackH) / 2f;
        float right = left + trackW;
        float bottom = top + trackH;

        trackRect.set(left, top, right, bottom);

        // 1. Color Palette Tokens for Material 3
        int trackColorOff;
        int trackStrokeColorOff;
        int trackColorOn;

        int thumbColorOff;
        int thumbColorOn;
        int checkIconColor;

        if (amoled) {
            trackColorOff = Color.parseColor("#121417");
            trackStrokeColorOff = Color.parseColor("#44474E");
            trackColorOn = Color.parseColor("#A8C7FA");

            thumbColorOff = Color.parseColor("#8E9192");
            thumbColorOn = Color.parseColor("#062E6F");
            checkIconColor = Color.parseColor("#A8C7FA");
        } else if (night) {
            trackColorOff = Color.parseColor("#22252A");
            trackStrokeColorOff = Color.parseColor("#44474E");
            trackColorOn = Color.parseColor("#A8C7FA");

            thumbColorOff = Color.parseColor("#938F99");
            thumbColorOn = Color.parseColor("#062E6F");
            checkIconColor = Color.parseColor("#A8C7FA");
        } else {
            trackColorOff = Color.parseColor("#E7E0EC");
            trackStrokeColorOff = Color.parseColor("#79747E");
            trackColorOn = Color.parseColor("#0B57D0");

            thumbColorOff = Color.parseColor("#79747E");
            thumbColorOn = Color.parseColor("#FFFFFF");
            checkIconColor = Color.parseColor("#0B57D0");
        }

        // 2. Draw Track
        int currentTrackColor = blendColors(trackColorOff, trackColorOn, progress);
        trackPaint.setColor(currentTrackColor);
        canvas.drawRoundRect(trackRect, trackR, trackR, trackPaint);

        // Stroke on track only visible when off (fades out as progress -> 1)
        if (progress < 0.99f) {
            int strokeAlpha = (int) ((1f - progress) * 255);
            trackStrokePaint.setColor(trackStrokeColorOff);
            trackStrokePaint.setAlpha(strokeAlpha);
            float inset = dp(1f);
            RectF strokeRect = new RectF(left + inset, top + inset, right - inset, bottom - inset);
            canvas.drawRoundRect(strokeRect, trackR - inset, trackR - inset, trackStrokePaint);
        }

        // 3. Compute Thumb Position & Radius
        boolean isRtl = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        }

        float minRadius = dp(THUMB_RADIUS_UNCHECKED_DP);
        float maxRadius = dp(THUMB_RADIUS_CHECKED_DP);
        float currentRadius = minRadius + (maxRadius - minRadius) * progress;

        float thumbCenterY = top + trackR;
        float startCenterX = left + trackR;
        float endCenterX = right - trackR;

        float thumbCenterX;
        if (isRtl) {
            // In RTL, unchecked starts at end (right), checked moves to start (left)
            thumbCenterX = endCenterX - (endCenterX - startCenterX) * progress;
        } else {
            thumbCenterX = startCenterX + (endCenterX - startCenterX) * progress;
        }

        // 4. Draw Thumb
        int currentThumbColor = blendColors(thumbColorOff, thumbColorOn, progress);
        thumbPaint.setColor(currentThumbColor);
        canvas.drawCircle(thumbCenterX, thumbCenterY, currentRadius, thumbPaint);

        // 5. Draw Material 3 Checkmark inside Checked Thumb
        if (progress > 0.05f) {
            float checkScale = 0.5f + (0.5f * progress);
            checkPaint.setColor(checkIconColor);
            checkPaint.setAlpha((int) (progress * 255));

            checkPath.reset();
            float p1x = thumbCenterX - dp(4.2f) * checkScale;
            float p1y = thumbCenterY - dp(0.3f) * checkScale;
            float p2x = thumbCenterX - dp(1.0f) * checkScale;
            float p2y = thumbCenterY + dp(3.0f) * checkScale;
            float p3x = thumbCenterX + dp(4.5f) * checkScale;
            float p3y = thumbCenterY - dp(3.0f) * checkScale;

            checkPath.moveTo(p1x, p1y);
            checkPath.lineTo(p2x, p2y);
            checkPath.lineTo(p3x, p3y);

            canvas.drawPath(checkPath, checkPaint);
        }
    }
}

