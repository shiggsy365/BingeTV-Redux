package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * As EdgeFadeImageView, for what's inside it: its left, bottom and top edges fade to
 * transparent, so Home's trailer blends into the artwork behind it. Black bars in the picture
 * can be left out ({@link #setCrop}), the edges fading from the picture's own.
 */
public class EdgeFadeFrame extends FrameLayout {
    private static final float LEFT_FADE = 0.12f;
    private static final float BOTTOM_FADE = 0.125f;
    private static final float TOP_FADE = 0.1f;

    private final Paint mLeft = new Paint();
    private final Paint mBottom = new Paint();
    private final Paint mTop = new Paint();
    // The bars left out, as shares of the height.
    private float mCropTop, mCropBottom;

    public EdgeFadeFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        PorterDuffXfermode keep = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
        mLeft.setXfermode(keep);
        mBottom.setXfermode(keep);
        mTop.setXfermode(keep);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mLeft.setShader(new LinearGradient(0, 0, w * LEFT_FADE, 0, 0x00000000, 0xFF000000,
                Shader.TileMode.CLAMP));
        mBottom.setShader(new LinearGradient(0, h * (1 - BOTTOM_FADE), 0, h, 0xFF000000,
                0x00000000, Shader.TileMode.CLAMP));
        topFade(h);
        if (getChildCount() > 0) getChildAt(0).setTranslationY(h * mCropBottom);
    }

    private void topFade(int h) {
        float top = pictureTop(h);
        mTop.setShader(new LinearGradient(0, top, 0, top + h * TOP_FADE, 0x00000000, 0xFF000000,
                Shader.TileMode.CLAMP));
    }

    /** Where the picture starts, with the bars left out: its foot stays at the bottom. */
    private float pictureTop(int h) {
        return h * (mCropTop + mCropBottom);
    }

    /**
     * Leaves out bands at the top and bottom of what's inside (shares of its height): the
     * bottom one by moving it down, the top one, and the space that leaves, by not drawing it.
     * False when they're as they were.
     */
    boolean setCrop(float top, float bottom) {
        if (top == mCropTop && bottom == mCropBottom) return false;
        mCropTop = top;
        mCropBottom = bottom;
        int h = getHeight();
        if (getChildCount() > 0) getChildAt(0).setTranslationY(h * bottom);
        topFade(h);
        invalidate();
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.save();
        canvas.clipRect(0, pictureTop(h), w, h);
        int saved = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0, 0, w, h, mLeft);
        canvas.drawRect(0, 0, w, h, mBottom);
        canvas.drawRect(0, 0, w, h, mTop);
        canvas.restoreToCount(saved);
        canvas.restore();
    }
}
