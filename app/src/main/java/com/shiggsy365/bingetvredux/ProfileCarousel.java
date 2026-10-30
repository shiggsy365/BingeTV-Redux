package com.shiggsy365.bingetvredux;

import android.view.View;

import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;

/**
 * The profile pickers as a carousel: the focused profile in the middle of the screen, full size,
 * the others either side of it smaller and dimmer.
 */
final class ProfileCarousel {
    private static final float AWAY_SCALE = 0.82f, AWAY_ALPHA = 0.55f;

    private ProfileCarousel() {}

    /** Keeps the focused tile centred as the focus moves. */
    static void setUp(HorizontalGridView grid) {
        grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE);
        grid.setWindowAlignmentOffset(0);
        grid.setWindowAlignmentOffsetPercent(50f);
        grid.setItemAlignmentOffset(0);
        grid.setItemAlignmentOffsetPercent(50f);
    }

    /** A tile (the whole tile: picture and name), focused or not. */
    static void style(View tile, boolean focused, boolean animate) {
        float scale = focused ? 1.1f : AWAY_SCALE, alpha = focused ? 1f : AWAY_ALPHA;
        if (animate) {
            tile.animate().scaleX(scale).scaleY(scale).alpha(alpha).setDuration(160).start();
        } else {
            tile.setScaleX(scale);
            tile.setScaleY(scale);
            tile.setAlpha(alpha);
        }
    }
}
