package com.shiggsy365.bingetvredux;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

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

    /** A title's logo, fading in; with no logo, or one that won't load, {@code name} shows instead. */
    public static void logo(ImageView logo, TextView name, String url) {
        logo(logo, name, url, true);
    }

    /** The same, without the fade when {@code fade} is false (it's there already, as it was). */
    public static void logo(ImageView logo, TextView name, String url, boolean fade) {
        boolean hasLogo = url != null && !url.isEmpty();
        name.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
        logo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
        if (!hasLogo) {
            Glide.with(logo).clear(logo);
            logo.setImageDrawable(null);
            return;
        }
        Glide.with(logo).load(url).transition(fade ? DrawableTransitionOptions.withCrossFade(250)
                : DrawableTransitionOptions.with(com.bumptech.glide.request.transition.NoTransition.getFactory()))
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target,
                            boolean isFirstResource) {
                        logo.setVisibility(View.GONE);
                        name.setVisibility(View.VISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                            DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                }).into(logo);
    }
}
