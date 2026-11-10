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
 * transparent, so Home's trailer (letterbox bars and all) blends into the artwork behind it.
 */
public class EdgeFadeFrame extends FrameLayout {
    private static final float LEFT_FADE = 0.12f;
    private static final float BOTTOM_FADE = 0.25f;
    private static final float TOP_FADE = 0.2f;

    private final Paint mLeft = new Paint();
    private final Paint mBottom = new Paint();
    private final Paint mTop = new Paint();

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
        mTop.setShader(new LinearGradient(0, 0, 0, h * TOP_FADE, 0x00000000, 0xFF000000,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int saved = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0, 0, w, h, mLeft);
        canvas.drawRect(0, 0, w, h, mBottom);
        canvas.drawRect(0, 0, w, h, mTop);
        canvas.restoreToCount(saved);
    }
}
