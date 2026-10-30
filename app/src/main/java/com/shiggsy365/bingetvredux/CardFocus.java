package com.shiggsy365.bingetvredux;

import android.view.View;

/** How a focused card stands out across the app: it grows a little and lifts. */
final class CardFocus {
    /** Posters in rows and grids. */
    static final float POSTER = 1.08f;
    /** Smaller cards beside others (an info page's "More like this"). */
    static final float SMALL = 1.06f;

    private CardFocus() {}

    static void apply(View card, boolean focused, float scale) {
        float to = focused ? scale : 1f;
        card.animate().scaleX(to).scaleY(to).setDuration(120).start();
        card.setElevation(focused ? 8f : 0f);
    }
}
