package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.shiggsy365.bingetvredux.stremio.Stream;
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
        ((TextView) findViewById(R.id.title)).setText(mPlayback.title());
        TextView subtitle = findViewById(R.id.subtitle);
        subtitle.setText(mPlayback.subtitle());
        subtitle.setVisibility(mPlayback.subtitle() == null ? View.GONE : View.VISIBLE);
        Images.fade(findViewById(R.id.backdrop), mPlayback.meta.background.isEmpty()
                ? mPlayback.meta.poster : mPlayback.meta.background);
        mStatus.setText("Finding streams…");
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
        mStatus.setText(streams.size() + (streams.size() == 1 ? " stream" : " streams"));
        mList.setAdapter(new StreamAdapter(this, streams, -1, this::play));
        mList.requestFocus();
    }

    private void play(int index) {
        mPlayback.start = index;
        startActivity(new Intent(this, VideoPlayerActivity.class));
    }

    /** Called with the position of the stream chosen. */
    interface OnPick {
        void pick(int index);
    }

    /** Streams with their addon and quality tags; also the player's stream menu. */
    static final class StreamAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context mContext;
        private final List<Stream> mStreams;
        private final int mCurrent;
        private final OnPick mOnPick;

        StreamAdapter(Context context, List<Stream> streams, int current, OnPick onPick) {
            mContext = context;
            mStreams = streams;
            mCurrent = current;
            mOnPick = onPick;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.stream_choice, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Stream s = mStreams.get(position);
            View view = holder.itemView;
            LinearLayout tags = view.findViewById(R.id.tags);
            tags.removeAllViews();
            List<String> labels = new ArrayList<>();
            if (position == mCurrent) labels.add("▶ Playing");
            labels.add(s.addon);
            labels.addAll(Streams.tags(s));
            for (String label : labels) {
                TextView chip = (TextView) LayoutInflater.from(mContext)
                        .inflate(R.layout.tag_chip, tags, false);
                chip.setText(label);
                if (label.equals("Cached") || label.startsWith("▶")) {
                    chip.setBackgroundResource(R.drawable.chip_catchup);
                }
                tags.addView(chip);
            }
            ((TextView) view.findViewById(R.id.name)).setText(s.name.replace('\n', ' '));
            ((TextView) view.findViewById(R.id.description)).setText(s.title.replace('\n', ' '));
            view.setOnClickListener(v -> mOnPick.pick(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return mStreams.size();
        }
    }
}
