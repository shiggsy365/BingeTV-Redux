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
import com.shiggsy365.bingetvredux.stremio.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the streams for the film or episode in Playback, from every stream addon; subtitles
 * are found alongside and handed to the player when they come. Played straight away, the best
 * stream starts (a next episode prefers the source the last one came from), and the next ones
 * are tried if it fails; chosen, they're listed with their quality tags as addons answer.
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
    // What the list shows; the cards read these, so they're changed in place.
    private final List<Stream> mStreams = new ArrayList<>();
    private final List<StreamInfo> mInfos = new ArrayList<>();
    private final Background.Tasks mExecutor = new Background.Tasks(1);
    private Playback mPlayback;
    private boolean mChoose;
    // Every addon has answered (later news from a slow one is dropped).
    private boolean mDone;
    // The streams found, before the profile's filters, for "Show them anyway".
    private Streams.Found mFound;

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
        mChoose = getIntent().getBooleanExtra(EXTRA_CHOOSE, false);
        findViewById(R.id.show_hidden).setOnClickListener(v -> showHidden());
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
        boolean episode = mPlayback.video != null && mPlayback.video.season != Video.NONE;
        Streams.Prefs prefs = Settings.streamPrefs(app, episode);
        // Up Next may have found them already.
        StreamPrefetch.Found pre = StreamPrefetch.take(type, id);
        if (pre != null) mPlayback.subtitlesFrom(pre.subtitles);
        else mPlayback.findSubtitles(app);
        mExecutor.execute(() -> {
            Streams.Found found = null;
            if (pre != null) {
                try {
                    found = pre.streams.get();
                } catch (Exception e) {
                    // Ask again, then.
                }
            }
            if (found == null) {
                // Chosen: the list fills in as addons answer, rather than waiting for the slowest.
                Streams.Progress progress = !mChoose ? null : (soFar, answered, asked) -> {
                    List<Stream> streams = arrange(soFar, prefs);
                    runOnUiThread(() -> showSoFar(streams, answered, asked));
                };
                found = Streams.fetch(Stremio.client(app), Stremio.addons(app), type, id, progress);
            }
            Streams.Found all = found;
            List<Stream> streams = arrange(all, prefs);
            runOnUiThread(() -> show(streams, all));
        });
    }

    private List<Stream> arrange(Streams.Found found, Streams.Prefs prefs) {
        return sameSourceFirst(Streams.prepare(found.streams, prefs), mPlayback.bingeGroup);
    }

    /** Streams from the given source group first (for the next episode of a show). */
    private static List<Stream> sameSourceFirst(List<Stream> streams, String bingeGroup) {
        if (bingeGroup.isEmpty()) return streams;
        List<Stream> out = new ArrayList<>();
        for (Stream s : streams) if (bingeGroup.equals(s.bingeGroup)) out.add(s);
        for (Stream s : streams) if (!bingeGroup.equals(s.bingeGroup)) out.add(s);
        return out;
    }

    /** Some addons have answered: the list so far, while the rest are asked. */
    private void showSoFar(List<Stream> streams, int answered, int asked) {
        if (isDestroyed() || mDone) return;
        if (streams.isEmpty()) {
            mStatus.setText("Searching for streams… (" + answered + " of " + asked + " sources)");
            return;
        }
        showList(streams, asked - answered);
    }

    private void show(List<Stream> streams, Streams.Found found) {
        if (isDestroyed() || mDone) return;
        mDone = true;
        mFound = found;
        mPlayback.streams = streams;
        mProgress.setVisibility(View.GONE);
        if (streams.isEmpty()) {
            boolean hidden = !found.streams.isEmpty() && !Streams.prepare(found.streams,
                    new Streams.Prefs()).isEmpty();
            // The list may be showing what one addon offered; there's nothing in the end.
            findViewById(R.id.searching).setVisibility(View.VISIBLE);
            findViewById(R.id.page).setVisibility(View.GONE);
            mStatus.setText(found.streams.isEmpty()
                    ? "No streams found" + (found.errors.isEmpty() ? ""
                    : " (" + String.join("; ", found.errors) + ")")
                    : hidden ? "No streams left after your filters (" + found.streams.size() + " hidden)"
                    : "No playable streams (torrents need a torrent client)");
            View showHidden = findViewById(R.id.show_hidden);
            showHidden.setVisibility(hidden ? View.VISIBLE : View.GONE);
            if (hidden) showHidden.requestFocus();
            return;
        }
        if (!mChoose) {
            play(0);
            finish();
            return;
        }
        showList(streams, 0);
    }

    /** "Show them anyway": the streams the profile's filters hid, sorted as usual. */
    private void showHidden() {
        Streams.Prefs none = new Streams.Prefs();
        none.sort = Settings.integer(this, Settings.SORT);
        none.hideCam = false;
        List<Stream> streams = arrange(mFound, none);
        mPlayback.streams = streams;
        findViewById(R.id.show_hidden).setVisibility(View.GONE);
        if (streams.isEmpty()) return;
        showList(streams, 0);
    }

    /**
     * The cards in two columns, under tabs that filter them. Called again as more addons answer
     * ({@code waiting} still to come): the focused stream stays focused, on the same tab.
     */
    private void showList(List<Stream> streams, int waiting) {
        findViewById(R.id.searching).setVisibility(View.GONE);
        findViewById(R.id.page).setVisibility(View.VISIBLE);
        ((View) findViewById(R.id.shade)).setBackgroundColor(0xCC0E0F14);
        String count = streams.size() + (streams.size() == 1 ? " stream" : " streams")
                + (waiting > 0 ? "  ·  still asking " + waiting + (waiting == 1 ? " source" : " sources") : "");
        String subtitle = mPlayback.subtitle();
        ((TextView) findViewById(R.id.subtitle)).setText(subtitle == null ? count : subtitle + "  ·  " + count);
        // What has the focus now, to find again in the new list.
        String focusedKey = null, filterLabel = null;
        if (mCards != null) {
            filterLabel = mFilterLabels.get(mFilter);
            int at = mList.getSelectedPosition();
            List<Integer> members = mFilterMembers.get(mFilter);
            if (at >= 0 && at < members.size()) focusedKey = mStreams.get(members.get(at)).dedupeKey();
        }
        mStreams.clear();
        mStreams.addAll(streams);
        mInfos.clear();
        for (Stream s : streams) mInfos.add(StreamInfo.of(s));
        buildFilters(mStreams, mInfos);
        if (mCards != null) {
            int filter = Math.max(0, mFilterLabels.indexOf(filterLabel));
            mFilters.getAdapter().notifyDataSetChanged();
            showFilter(filter);
            List<Integer> members = mFilterMembers.get(filter);
            for (int i = 0; focusedKey != null && i < members.size(); i++) {
                if (mStreams.get(members.get(i)).dedupeKey().equals(focusedKey)) {
                    mList.setSelectedPosition(i);
                    break;
                }
            }
            return;
        }
        mCards = new StreamCards.Adapter(mStreams, mInfos, -1, this::play,
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
        // What's listed now: more addons answering later don't change what's playing.
        mPlayback.streams = new ArrayList<>(mStreams.isEmpty() ? mPlayback.streams : mStreams);
        mPlayback.start = index;
        startActivity(new Intent(this, VideoPlayerActivity.class));
    }
}
