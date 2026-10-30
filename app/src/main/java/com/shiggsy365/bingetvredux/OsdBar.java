package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The OSD's progress line: buffered (grey) under played (red). Focused (for seeking) it grows
 * thicker and shows a white nub where playback is.
 */
public class OsdBar extends View {
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBuffered = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlayed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private float mPlayedFraction;
    private float mBufferedFraction;

    public OsdBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTrack.setColor(0x40FFFFFF);
        mBuffered.setColor(0x80FFFFFF);
        mPlayed.setColor(context.getColor(R.color.guide_accent));
        mNub.setColor(0xFFFFFFFF);
        setFocusable(true);
    }

    /** Fractions of the whole, 0–1. */
    public void set(float played, float buffered) {
        played = Math.max(0f, Math.min(1f, played));
        buffered = Math.max(played, Math.min(1f, buffered));
        if (played == mPlayedFraction && buffered == mBufferedFraction) return;
        mPlayedFraction = played;
        mBufferedFraction = buffered;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float dp = getResources().getDisplayMetrics().density;
        float thickness = (isFocused() ? 6 : 3) * dp;
        float nub = isFocused() ? 8 * dp : 0;
        float left = nub, right = getWidth() - nub, cy = getHeight() / 2f;
        float width = right - left;
        float radius = thickness / 2;
        mRect.set(left, cy - radius, right, cy + radius);
        canvas.drawRoundRect(mRect, radius, radius, mTrack);
        mRect.set(left, cy - radius, left + width * mBufferedFraction, cy + radius);
        canvas.drawRoundRect(mRect, radius, radius, mBuffered);
        mRect.set(left, cy - radius, left + width * mPlayedFraction, cy + radius);
        canvas.drawRoundRect(mRect, radius, radius, mPlayed);
        if (nub > 0) canvas.drawCircle(left + width * mPlayedFraction, cy, nub, mNub);
    }
}
