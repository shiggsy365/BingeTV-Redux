package com.shiggsy365.bingetvredux;

import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

/** Artwork from addons, loaded and cached by Glide at the size it's shown. */
public final class Images {
    private Images() {}

    /** Loads {@code url} into the view, or clears it when there's no url. */
    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
            return;
        }
        Glide.with(view).load(url).into(view);
    }

    /** The same, fading in: for backdrops and logos that change as focus moves. */
    public static void fade(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
            return;
        }
        Glide.with(view).load(url).transition(DrawableTransitionOptions.withCrossFade(250))
                .into(view);
    }
}
