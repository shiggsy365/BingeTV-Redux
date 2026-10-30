package com.shiggsy365.bingetvredux;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;

/**
 * The ribbon across a poster's top-right corner, as on BingeTV: green "Paused" while there's a
 * resume point (a film, or any episode of a show), else red "Watched" once a film is watched or
 * a show has every aired episode watched. A part-watched film or episode also gets a bar
 * along the bottom showing how far in it is.
 */
public final class Badges {
    // The ribbon's side, as a share of the card's shorter side.
    private static final float RIBBON_SHARE = 0.7f;

    private Badges() {}

    public static void bind(View card, MetaItem item) {
        ImageView ribbon = card.findViewById(R.id.card_ribbon);
        if (ribbon == null) return;
        WatchStore store = WatchStore.get(card.getContext());
        WatchStore.Row row = store.row(item.id);
        WatchStore.Row resume = row != null && row.positionMs > 0 && !row.watched ? row
                : store.resumeFor(item.id);
        boolean paused = resume != null;
        progress(card.findViewById(R.id.card_progress), resume);
        boolean watched = row != null && row.watched;
        if (!paused && !watched && !"movie".equals(item.type)) {
            watched = ShowProgress.fullyWatched(card.getContext(), item);
        }
        ribbon(ribbon, card.getLayoutParams(), paused, watched);
    }

    /** Shows the paused or watched ribbon (paused wins), sized for a card of {@code size}. */
    static void ribbon(ImageView ribbon, ViewGroup.LayoutParams size, boolean paused, boolean watched) {
        if (!paused && !watched) {
            ribbon.setVisibility(View.GONE);
            return;
        }
        ribbon.setImageResource(paused ? R.drawable.ribbon_paused : R.drawable.ribbon_watched);
        if (size != null && size.width > 0 && size.height > 0) {
            int side = Math.round(Math.min(size.width, size.height) * RIBBON_SHARE);
            ViewGroup.LayoutParams lp = ribbon.getLayoutParams();
            if (lp.width != side) {
                lp.width = side;
                lp.height = side;
                ribbon.setLayoutParams(lp);
            }
        }
        ribbon.setVisibility(View.VISIBLE);
    }

    public static void clear(View card) {
        View ribbon = card.findViewById(R.id.card_ribbon);
        if (ribbon != null) ribbon.setVisibility(View.GONE);
        progress(card.findViewById(R.id.card_progress), null);
    }

    private static void progress(android.widget.ProgressBar bar, WatchStore.Row resume) {
        if (bar == null) return;
        boolean show = resume != null && resume.durationMs > 0;
        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) bar.setProgress((int) Math.min(1000, resume.positionMs * 1000 / resume.durationMs));
    }
}
