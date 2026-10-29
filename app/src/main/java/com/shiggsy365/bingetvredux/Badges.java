package com.shiggsy365.bingetvredux;

import android.view.View;
import android.widget.ProgressBar;

import com.shiggsy365.bingetvredux.stremio.MetaItem;

/**
 * The watched tick and progress bar on a poster card: a film watched or part-watched, or a show
 * with an episode part-watched.
 */
public final class Badges {
    private Badges() {}

    public static void bind(View card, MetaItem item) {
        View tick = card.findViewById(R.id.card_watched);
        ProgressBar progress = card.findViewById(R.id.card_progress);
        if (tick == null || progress == null) return;
        WatchStore store = WatchStore.get(card.getContext());
        WatchStore.Row row = store.row(item.id);
        boolean watched = row != null && row.watched;
        WatchStore.Row resume = row != null && row.positionMs > 0 && !row.watched ? row
                : store.resumeFor(item.id);
        tick.setVisibility(watched ? View.VISIBLE : View.GONE);
        progress.setVisibility(resume != null ? View.VISIBLE : View.GONE);
        if (resume != null) progress.setProgress((int) (resume.progress() * 1000));
    }

    public static void clear(View card) {
        View tick = card.findViewById(R.id.card_watched);
        View progress = card.findViewById(R.id.card_progress);
        if (tick != null) tick.setVisibility(View.GONE);
        if (progress != null) progress.setVisibility(View.GONE);
    }
}
