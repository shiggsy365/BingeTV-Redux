package com.shiggsy365.bingetvredux;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * A description: justified (Android 7, on the Fire TV Stick, can't justify text itself), and
 * when it's longer than its box ({@code maxLines} lines), scrolling slowly up to its end after a
 * pause, then starting again from the top, as Kodi's text boxes do.
 */
public class ScrollingText extends TextView {
    private static final long START_AFTER_MS = 5_000, HOLD_END_MS = 4_000, MS_PER_LINE = 1_400;

    private final int mVisibleLines;
    private float mScroll;
    private ValueAnimator mAnimator;
    private final Runnable mStart = this::startScrolling;

    public ScrollingText(Context context, AttributeSet attrs) {
        super(context, attrs);
        int lines = getMaxLines();
        mVisibleLines = lines > 0 && lines < Integer.MAX_VALUE ? lines : 4;
        setMaxLines(Integer.MAX_VALUE);
        setEllipsize(null);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        restart();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Layout layout = getLayout();
        if (layout == null) return;
        int lines = Math.min(layout.getLineCount(), mVisibleLines);
        int height = lines == 0 ? 0 : layout.getLineTop(lines) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void stop() {
        removeCallbacks(mStart);
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    private void restart() {
        stop();
        mScroll = 0;
        invalidate();
        if (overflow() > 0) postDelayed(mStart, START_AFTER_MS);
    }

    /** How far the text goes past its box, in pixels. */
    private int overflow() {
        Layout layout = getLayout();
        if (layout == null || layout.getLineCount() <= mVisibleLines) return 0;
        return layout.getLineTop(layout.getLineCount()) - layout.getLineTop(mVisibleLines);
    }

    private void startScrolling() {
        int distance = overflow();
        if (distance <= 0) return;
        int lines = getLayout().getLineCount() - mVisibleLines;
        mAnimator = ValueAnimator.ofFloat(0, distance);
        mAnimator.setDuration(lines * MS_PER_LINE);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(a -> {
            mScroll = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mAnimator != animation) return;
                // Hold at the end, then back to the top and round again.
                postDelayed(() -> {
                    if (mAnimator == animation) restart();
                }, HOLD_END_MS);
            }
        });
        mAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) return;
        CharSequence text = getText();
        TextPaint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        paint.drawableState = getDrawableState();
        int left = getPaddingLeft(), top = getPaddingTop();
        int width = getWidth() - left - getPaddingRight();
        canvas.save();
        canvas.clipRect(left, top, left + width, getHeight() - getPaddingBottom());
        canvas.translate(left, top - mScroll);
        for (int i = 0; i < layout.getLineCount(); i++) {
            if (layout.getLineBottom(i) < mScroll) continue;
            if (layout.getLineTop(i) > mScroll + getHeight()) break;
            int start = layout.getLineStart(i), end = layout.getLineEnd(i);
            String line = text.subSequence(start, end).toString();
            boolean paragraphEnd = end >= text.length() || text.charAt(end - 1) == '\n';
            line = line.replace("\n", "");
            float baseline = layout.getLineBaseline(i);
            String trimmed = line.trim();
            String[] words = trimmed.split(" +");
            if (paragraphEnd || words.length < 2) {
                canvas.drawText(line, 0, baseline, paint);
                continue;
            }
            float wordsWidth = 0;
            for (String w : words) wordsWidth += paint.measureText(w);
            float gap = (width - wordsWidth) / (words.length - 1);
            // A line far from full (e.g. before a long word) isn't stretched to look gappy.
            if (gap > paint.measureText(" ") * 4) {
                canvas.drawText(line, 0, baseline, paint);
                continue;
            }
            float x = 0;
            for (String w : words) {
                canvas.drawText(w, x, baseline, paint);
                x += paint.measureText(w) + gap;
            }
        }
        canvas.restore();
    }
}
