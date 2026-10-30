package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.StreamInfo;
import com.shiggsy365.bingetvredux.stremio.Streams;
import com.shiggsy365.bingetvredux.stremio.Subtitles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds the streams for the film or episode in Playback, from every stream addon, with
 * subtitles alongside. Played straight away, the best stream starts (a next episode prefers
 * the source the last one came from), and the next ones are tried if it fails; chosen, they're
 * listed with their quality tags.
 */
public class StreamsActivity extends Activity {
    static final String EXTRA_CHOOSE = "choose";

    private TextView mStatus;
    private View mProgress;
    private VerticalGridView mList;
    private HorizontalGridView mFilters;
    private TextView mFilename;
    // Tab label and the streams (positions in the list) it shows: All, each addon, each
    // resolution and Cached, where they narrow things down.
    private final List<String> mFilterLabels = new ArrayList<>();
    private final List<List<Integer>> mFilterMembers = new ArrayList<>();
    private int mFilter;
    private StreamCards.Adapter mCards;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(2);
    private Playback mPlayback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.streams_activity);
        mPlayback = Playback.current();
        if (mPlayback == null) {
            finish();
            return;
        }
        mStatus = findViewById(R.id.status);
        mProgress = findViewById(R.id.progress);
        mList = findViewById(R.id.list);
        mFilters = findViewById(R.id.filters);
        mFilename = findViewById(R.id.filename);
        MetaItem meta = mPlayback.meta;
        boolean hasLogo = !meta.logo.isEmpty();
        for (int[] ids : new int[][]{{R.id.searching_logo, R.id.searching_title}, {R.id.logo, R.id.title}}) {
            ImageView logo = findViewById(ids[0]);
            TextView title = findViewById(ids[1]);
            title.setText(mPlayback.title());
            title.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
            logo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
            Images.fade(logo, meta.logo);
        }
        Images.fade(findViewById(R.id.backdrop), meta.background.isEmpty() ? meta.poster : meta.background);
        mStatus.setText("Searching for streams…");
        find();
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void find() {
        String type = mPlayback.meta.type;
        String id = mPlayback.videoId();
        Context app = getApplicationContext();
        List<String> languages = Settings.subtitleLanguages(app);
        Streams.Prefs prefs = Settings.streamPrefs(app);
        Future<List<Subtitles.Track>> subtitles = mExecutor.submit(() -> Subtitles.fetch(
                Stremio.client(app), Stremio.addons(app), type, id, null, languages));
        mExecutor.execute(() -> {
            Streams.Found found = Streams.fetch(Stremio.client(app), Stremio.addons(app), type, id);
            List<Stream> streams = sameSourceFirst(Streams.prepare(found.streams, prefs),
                    mPlayback.bingeGroup);
            List<Subtitles.Track> subs;
            try {
                subs = subtitles.get();
            } catch (Exception e) {
                subs = new ArrayList<>();
            }
            List<Subtitles.Track> finalSubs = subs;
            runOnUiThread(() -> show(streams, finalSubs, found));
        });
    }

    /** Streams from the given source group first (for the next episode of a show). */
    private static List<Stream> sameSourceFirst(List<Stream> streams, String bingeGroup) {
        if (bingeGroup.isEmpty()) return streams;
        List<Stream> out = new ArrayList<>();
        for (Stream s : streams) if (bingeGroup.equals(s.bingeGroup)) out.add(s);
        for (Stream s : streams) if (!bingeGroup.equals(s.bingeGroup)) out.add(s);
        return out;
    }

    private void show(List<Stream> streams, List<Subtitles.Track> subtitles, Streams.Found found) {
        if (isDestroyed()) return;
        mPlayback.streams = streams;
        mPlayback.subtitles = subtitles;
        mProgress.setVisibility(View.GONE);
        if (streams.isEmpty()) {
            mStatus.setText(found.streams.isEmpty()
                    ? "No streams found" + (found.errors.isEmpty() ? ""
                    : " (" + String.join("; ", found.errors) + ")")
                    : "No streams left after your filters (" + found.streams.size() + " hidden)");
            return;
        }
        if (!getIntent().getBooleanExtra(EXTRA_CHOOSE, false)) {
            play(0);
            finish();
            return;
        }
        showList(streams);
    }

    /** The cards in two columns, under tabs that filter them. */
    private void showList(List<Stream> streams) {
        findViewById(R.id.searching).setVisibility(View.GONE);
        findViewById(R.id.page).setVisibility(View.VISIBLE);
        ((View) findViewById(R.id.shade)).setBackgroundColor(0xCC0E0F14);
        String count = streams.size() + (streams.size() == 1 ? " stream" : " streams");
        String subtitle = mPlayback.subtitle();
        ((TextView) findViewById(R.id.subtitle)).setText(subtitle == null ? count : subtitle + "  ·  " + count);
        List<StreamInfo> infos = new ArrayList<>();
        for (Stream s : streams) infos.add(StreamInfo.of(s));
        buildFilters(streams, infos);
        mCards = new StreamCards.Adapter(streams, infos, -1, this::play,
                info -> mFilename.setText(info.filename.isEmpty() ? info.details.replace('\n', ' ') : info.filename));
        mList.setNumColumns(2);
        mList.setHorizontalSpacing(Math.round(8 * getResources().getDisplayMetrics().density));
        mList.setVerticalSpacing(Math.round(10 * getResources().getDisplayMetrics().density));
        mList.setAdapter(mCards);
        // Up from the top row goes to the tabs (the grid keeps Up to itself otherwise).
        mList.setOnKeyInterceptListener(event -> {
            if (event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP || mList.getSelectedPosition() >= 2
                    || mFilterLabels.size() < 2) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) mFilters.requestFocus();
            return true;
        });
        mFilters.setAdapter(new FilterAdapter());
        showFilter(0);
        mList.requestFocus();
    }

    private void buildFilters(List<Stream> streams, List<StreamInfo> infos) {
        List<Integer> every = new ArrayList<>();
        for (int i = 0; i < streams.size(); i++) every.add(i);
        mFilterLabels.clear();
        mFilterMembers.clear();
        mFilterLabels.add("All");
        mFilterMembers.add(every);
        java.util.LinkedHashSet<String> addons = new java.util.LinkedHashSet<>();
        for (Stream s : streams) addons.add(s.addon);
        for (String addon : addons) {
            List<Integer> members = new ArrayList<>();
            for (int i : every) if (streams.get(i).addon.equals(addon)) members.add(i);
            addFilter(addon, members, every.size());
        }
        for (String res : StreamInfo.RESOLUTION_ORDER) {
            List<Integer> members = new ArrayList<>();
            for (int i : every) if (infos.get(i).resolution.equals(res)) members.add(i);
            addFilter(StreamInfo.LABELS.get(res), members, every.size());
        }
        List<Integer> cached = new ArrayList<>();
        for (int i : every) if (infos.get(i).cached) cached.add(i);
        addFilter("Cached", cached, every.size());
    }

    private void addFilter(String label, List<Integer> members, int all) {
        if (members.isEmpty() || members.size() == all) return;
        mFilterLabels.add(label);
        mFilterMembers.add(members);
    }

    private void showFilter(int index) {
        mFilter = index;
        mCards.show(mFilterMembers.get(index));
        RecyclerView.Adapter<?> tabs = mFilters.getAdapter();
        if (tabs != null) tabs.notifyDataSetChanged();
    }

    private final class FilterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.season_tab, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TextView tab = (TextView) holder.itemView;
            tab.setText(mFilterLabels.get(position) + "  " + mFilterMembers.get(position).size());
            boolean current = position == mFilter;
            tab.setSelected(current);
            tab.setTextColor(current ? 0xFFFFFFFF : 0xFFA0A0A0);
            tab.setOnClickListener(v -> {
                int at = holder.getBindingAdapterPosition();
                if (at != mFilter) {
                    // After the click, not during it: the tabs are redrawn.
                    v.post(() -> showFilter(at));
                }
                mList.requestFocus();
            });
        }

        @Override
        public int getItemCount() {
            return mFilterLabels.size();
        }
    }

    private void play(int index) {
        mPlayback.start = index;
        startActivity(new Intent(this, VideoPlayerActivity.class));
    }
}
