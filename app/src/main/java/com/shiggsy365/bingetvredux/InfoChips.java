package com.shiggsy365.bingetvredux;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The outlined chips of Home's hero and the info page: a title's facts, and its genres. */
final class InfoChips {
    private InfoChips() {}

    /** Year, seasons (a show) or length (a film), age rating and rating. */
    static List<String> facts(MetaItem item) {
        List<String> facts = new ArrayList<>();
        String year = item.releaseInfo.length() >= 4 ? item.releaseInfo.substring(0, 4) : item.releaseInfo;
        if (!year.isEmpty()) facts.add(year);
        if (item.hasSeasons()) {
            // Known only from a full meta (rows' items carry no episodes).
            int count = 0;
            for (int s : item.seasons()) if (s != 0) count++;
            if (count > 0) facts.add(count == 1 ? "1 season" : count + " seasons");
        } else {
            int minutes = item.runtimeSeconds() / 60;
            if (minutes > 0) facts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
        }
        if (!item.certification.isEmpty()) facts.add(item.certification);
        if (item.imdbRating > 0) facts.add(String.format(Locale.ROOT, "★ %.1f", item.imdbRating));
        return facts;
    }

    /** Up to three genres. */
    static List<String> genres(MetaItem item) {
        return item.genres.subList(0, Math.min(3, item.genres.size()));
    }

    /** Fills a row with chips; hides it when there are none. */
    static void fill(LinearLayout into, List<String> labels) {
        into.removeAllViews();
        for (String label : labels) {
            TextView chip = (TextView) LayoutInflater.from(into.getContext())
                    .inflate(R.layout.info_chip, into, false);
            chip.setText(label);
            into.addView(chip);
        }
        into.setVisibility(labels.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
