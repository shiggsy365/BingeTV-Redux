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

    /** A local picture that may be replaced (a profile's), reloaded whenever the file changes. */
    public static void loadFile(ImageView view, java.io.File file) {
        Glide.with(view).load(file)
                .signature(new com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                .into(view);
    }

    /** A small copy, fading in, for soft full-screen backgrounds (cheap to load and draw). */
    public static void fadeSoft(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
            return;
        }
        Glide.with(view).load(url).override(256, 144)
                .transition(DrawableTransitionOptions.withCrossFade(250)).into(view);
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
