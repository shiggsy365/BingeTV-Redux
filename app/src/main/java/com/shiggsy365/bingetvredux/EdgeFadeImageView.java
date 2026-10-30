package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * An image whose left and bottom edges fade to transparent, so it blends into whatever is behind
 * it (Home's windowed backdrop over the same artwork full screen).
 */
public class EdgeFadeImageView extends ImageView {
    // How much of the width and height the fades cover.
    private static final float LEFT_FADE = 0.2f;
    private static final float BOTTOM_FADE = 0.2f;

    private final Paint mLeft = new Paint();
    private final Paint mBottom = new Paint();

    public EdgeFadeImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        PorterDuffXfermode keep = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
        mLeft.setXfermode(keep);
        mBottom.setXfermode(keep);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mLeft.setShader(new LinearGradient(0, 0, w * LEFT_FADE, 0, 0x00000000, 0xFF000000,
                Shader.TileMode.CLAMP));
        mBottom.setShader(new LinearGradient(0, h * (1 - BOTTOM_FADE), 0, h, 0xFF000000,
                0x00000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int saved = canvas.saveLayer(0, 0, w, h, null);
        super.onDraw(canvas);
        canvas.drawRect(0, 0, w, h, mLeft);
        canvas.drawRect(0, 0, w, h, mBottom);
        canvas.restoreToCount(saved);
    }
}
