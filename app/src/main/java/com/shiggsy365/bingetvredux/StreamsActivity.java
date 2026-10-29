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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds the streams for a film or episode from every stream addon, with subtitles alongside.
 * Played straight away, the best stream starts (and the next ones are tried if it fails);
 * chosen, they're listed with their quality tags.
 */
public class StreamsActivity extends Activity {
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SUBTITLE = "subtitle";
    private static final String EXTRA_CHOOSE = "choose";
    private static final String EXTRA_BACKDROP = "backdrop";

    private TextView mStatus;
    private View mProgress;
    private VerticalGridView mList;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(2);
    private List<Stream> mStreams = new ArrayList<>();
    private List<Subtitles.Track> mSubtitles = new ArrayList<>();

    public static Intent intent(Context context, String type, String videoId, String title,
            String subtitle, boolean choose, String backdrop) {
        return new Intent(context, StreamsActivity.class)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_ID, videoId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_CHOOSE, choose)
                .putExtra(EXTRA_BACKDROP, backdrop);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.streams_activity);
        mStatus = findViewById(R.id.status);
        mProgress = findViewById(R.id.progress);
        mList = findViewById(R.id.list);
        Intent in = getIntent();
        ((TextView) findViewById(R.id.title)).setText(in.getStringExtra(EXTRA_TITLE));
        TextView subtitle = findViewById(R.id.subtitle);
        subtitle.setText(in.getStringExtra(EXTRA_SUBTITLE));
        subtitle.setVisibility(in.getStringExtra(EXTRA_SUBTITLE) == null ? View.GONE : View.VISIBLE);
        Images.fade(findViewById(R.id.backdrop), in.getStringExtra(EXTRA_BACKDROP));
        mStatus.setText("Finding streams…");
        find();
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void find() {
        String type = getIntent().getStringExtra(EXTRA_TYPE);
        String id = getIntent().getStringExtra(EXTRA_ID);
        Context app = getApplicationContext();
        Future<List<Subtitles.Track>> subtitles = mExecutor.submit(() -> Subtitles.fetch(
                Stremio.client(app), Stremio.addons(app), type, id, null,
                Collections.singletonList(Locale.getDefault().getISO3Language())));
        mExecutor.execute(() -> {
            Streams.Found found = Streams.fetch(Stremio.client(app), Stremio.addons(app), type, id);
            List<Stream> streams = Streams.prepare(found.streams, new Streams.Prefs());
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

    private void show(List<Stream> streams, List<Subtitles.Track> subtitles, Streams.Found found) {
        if (isDestroyed()) return;
        mStreams = streams;
        mSubtitles = subtitles;
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
        mList.setAdapter(new StreamAdapter());
        mList.requestFocus();
    }

    private void play(int index) {
        Intent in = getIntent();
        Playback.set(new Playback(in.getStringExtra(EXTRA_TITLE), in.getStringExtra(EXTRA_SUBTITLE),
                in.getStringExtra(EXTRA_TYPE), in.getStringExtra(EXTRA_ID), mStreams, index,
                mSubtitles));
        startActivity(new Intent(this, VideoPlayerActivity.class));
    }

    private final class StreamAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
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
            labels.add(s.addon);
            labels.addAll(Streams.tags(s));
            for (String label : labels) {
                TextView chip = (TextView) LayoutInflater.from(view.getContext())
                        .inflate(R.layout.tag_chip, tags, false);
                chip.setText(label);
                if (label.equals("Cached")) chip.setBackgroundResource(R.drawable.chip_catchup);
                tags.addView(chip);
            }
            ((TextView) view.findViewById(R.id.name)).setText(s.name.replace('\n', ' '));
            ((TextView) view.findViewById(R.id.description)).setText(s.title.replace('\n', ' '));
            view.setOnClickListener(v -> play(position));
        }

        @Override
        public int getItemCount() {
            return mStreams.size();
        }
    }
}
