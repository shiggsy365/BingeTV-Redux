package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.StreamInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The stream list's cards, as BingeTV's: a title line (debrid tag, addon, resolution), quality,
 * audio and source lines, and a strip of badges (drawn here: coloured labels, no logos).
 */
final class StreamCards {
    private static final int KEY = 0xFF8A949E;        // "Quality:" etc.
    private static final int CACHED = 0xFF2ECC71, UNCACHED = 0xFFF5A623;

    /** Called with the position (in the full list) of the stream chosen. */
    interface OnPick {
        void pick(int index);
    }

    /** Called when a card gains focus, with its stream's info. */
    interface OnFocus {
        void focused(StreamInfo info);
    }

    private StreamCards() {}

    // ---- Text

    static CharSequence title(Stream stream, StreamInfo info) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String code = "";
        for (Map.Entry<String, String> d : StreamInfo.DEBRID.entrySet()) {
            if (d.getValue().equals(info.debrid)) {
                code = d.getKey();
                break;
            }
        }
        if (code.isEmpty() && !info.debrid.isEmpty()) {
            code = info.debrid.substring(0, Math.min(2, info.debrid.length())).toUpperCase(Locale.ROOT);
        }
        if (!code.isEmpty()) {
            append(out, "[" + code + (info.cached ? "+" : "") + "] ", info.cached ? CACHED : UNCACHED, false);
        } else if (info.p2p) {
            append(out, "[P2P] ", UNCACHED, false);
        }
        append(out, stream.addon, 0, true);
        String resolution = StreamInfo.LABELS.containsKey(info.resolution) ? StreamInfo.LABELS.get(info.resolution) : "";
        if (!resolution.isEmpty()) out.append("  ").append(resolution);
        return out;
    }

    /** Up to three lines: Quality, Audio, then Size · Source · Lang (or the addon's own words). */
    static List<CharSequence> lines(Stream stream, StreamInfo info) {
        List<String> quality = new ArrayList<>();
        quality.add(label(info.release));
        quality.add(stream.quality.codec);
        for (String v : info.visual) quality.add(label(v));
        for (String e : info.extras) quality.add(label(e));
        LinkedHashSet<String> audio = new LinkedHashSet<>(StreamInfo.audioCodecs(String.join("\n",
                StreamInfo.normalise(stream.name), StreamInfo.normalise(stream.title),
                (stream.filename == null ? "" : stream.filename).replace(".", " "))));
        audio.add(info.channels);
        List<CharSequence> lines = new ArrayList<>();
        add(lines, line("Quality", quality, " | "));
        add(lines, line("Audio", new ArrayList<>(audio), " | "));
        SpannableStringBuilder third = new SpannableStringBuilder();
        for (CharSequence part : new CharSequence[]{
                line("Size", join(" · ", info.size, info.bitrate)),
                line("Source", join(" · ", info.provider, info.group)),
                line("Lang", info.languages.toUpperCase(Locale.ROOT))}) {
            if (part.length() == 0) continue;
            if (third.length() > 0) third.append("   ");
            third.append(part);
        }
        add(lines, third);
        if (lines.size() < 3) {
            // Little to go on (e.g. YouTube): show the addon's own words.
            for (String l : StreamInfo.normalise(stream.title).split("\n")) {
                if (lines.size() >= 3) break;
                if (!l.isEmpty()) lines.add(l);
            }
        }
        return lines;
    }

    private static String label(String key) {
        return StreamInfo.LABELS.containsKey(key) ? StreamInfo.LABELS.get(key) : key;
    }

    private static String join(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return String.join(sep, out);
    }

    private static CharSequence line(String key, String value) {
        List<String> values = new ArrayList<>();
        values.add(value);
        return line(key, values, "");
    }

    private static CharSequence line(String key, List<String> values, String sep) {
        List<String> kept = new ArrayList<>();
        for (String v : values) if (v != null && !v.isEmpty()) kept.add(v);
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (kept.isEmpty()) return out;
        append(out, key + ": ", KEY, false);
        out.append(String.join(sep, kept));
        return out;
    }

    private static void add(List<CharSequence> lines, CharSequence line) {
        if (line.length() > 0) lines.add(line);
    }

    private static void append(SpannableStringBuilder out, String text, int colour, boolean bold) {
        int start = out.length();
        out.append(text);
        if (colour != 0) {
            out.setSpan(new ForegroundColorSpan(colour), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---- Badges

    /** {label, fill, text colour, border (0: none)} for a badge key. */
    private static final Map<String, Object[]> STYLES = new HashMap<>();
    private static final int GOLD = 0xFFFFBE01, INK = 0xFF0E0E0E, WHITE = 0xFFFFFFFF;
    static {
        style("dl_cached", "⚡ Cached", 0xFF1E9E4A, WHITE, 0);
        style("dl_p2p", "P2P", UNCACHED, INK, 0);
        style("res_4k", "4K", GOLD, INK, 0);
        for (String r : new String[]{"1440p", "1080p", "720p", "sd"}) {
            style("res_" + r, StreamInfo.LABELS.get(r), 0xFFE6E6E6, INK, 0);
        }
        for (String r : new String[]{"remux", "bluray", "webdl", "webrip", "hdtv", "dvd"}) {
            style("rel_" + r, StreamInfo.LABELS.get(r), 0x2600E932, 0xFF3DDC64, 0xFF00E932);
        }
        style("rel_cam", "CAM", 0xFFD32F2F, WHITE, 0);
        style("vis_dv", "Dolby Vision", GOLD, INK, 0);
        style("vis_hdr10plus", "HDR10+", GOLD, INK, 0);
        style("vis_hdr10", "HDR10", GOLD, INK, 0);
        style("vis_hdr", "HDR", GOLD, INK, 0);
        style("vis_sdr", "SDR", 0x33FFFFFF, WHITE, 0xFF858283);
        style("extra_imax", "IMAX", 0xFF1565C0, WHITE, 0);
        style("extra_imax_enhanced", "IMAX Enhanced", 0xFF1565C0, WHITE, 0);
        style("extra_3d", "3D", 0x33FFFFFF, WHITE, 0xFF858283);
        style("extra_seadex", "SeaDex", 0xFF7B1FA2, WHITE, 0);
        for (String a : new String[]{"atmos", "truehd", "dtsx", "dtshdma", "dtshd", "dts", "ddp", "dd",
                "flac", "opus", "aac", "mp3", "pcm"}) {
            style("aud_" + a, a.equals("atmos") ? "Dolby Atmos" : StreamInfo.LABELS.get(a), WHITE, INK, 0);
        }
        for (String c : new String[]{"10", "20", "51", "61", "71"}) {
            style("ch_" + c, c.charAt(0) + "." + c.charAt(1), WHITE, INK, 0);
        }
        // Streaming services: their colours with a plain word, not their logos.
        style("net_netflix", "NETFLIX", 0xFFE50914, WHITE, 0);
        style("net_amazon", "PRIME", 0xFF00A8E1, WHITE, 0);
        style("net_appletv", "APPLE TV+", 0xFF000000, WHITE, 0xFFFFFFFF);
        style("net_disney", "DISNEY+", 0xFF113CCF, WHITE, 0);
        style("net_max", "max", 0xFF002BE7, WHITE, 0);
        style("net_hulu", "hulu", 0xFF1CE783, INK, 0);
        style("net_peacock", "PEACOCK", 0xFF000000, WHITE, 0xFFFFFFFF);
        style("net_paramount", "PARAMOUNT+", 0xFF0064FF, WHITE, 0);
        style("net_crunchyroll", "CRUNCHYROLL", 0xFFF47521, WHITE, 0);
    }

    private static void style(String key, String label, int fill, int text, int border) {
        STYLES.put(key, new Object[]{label, fill, text, border});
    }

    /** Fills a row with the stream's badges. */
    static void badges(LinearLayout into, StreamInfo info) {
        into.removeAllViews();
        Context context = into.getContext();
        float dp = context.getResources().getDisplayMetrics().density;
        for (String key : info.badges()) {
            Object[] s = STYLES.get(key);
            if (s == null) continue;
            TextView badge = new TextView(context);
            badge.setText((String) s[0]);
            badge.setTextColor((Integer) s[2]);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            badge.setGravity(Gravity.CENTER);
            badge.setSingleLine(true);
            badge.setPadding(Math.round(6 * dp), 0, Math.round(6 * dp), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(4 * dp);
            bg.setColor((Integer) s[1]);
            if ((Integer) s[3] != 0) bg.setStroke(Math.max(1, Math.round(dp)), (Integer) s[3]);
            badge.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(17 * dp));
            lp.setMarginEnd(Math.round(5 * dp));
            into.addView(badge, lp);
        }
    }

    // ---- The list

    /** Cards for the streams at {@code shown} (positions in {@code streams}). */
    static final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Stream> mStreams;
        private final List<StreamInfo> mInfos;
        private final List<Integer> mShown = new ArrayList<>();
        private final int mPlaying;
        private final OnPick mOnPick;
        private final OnFocus mOnFocus;

        Adapter(List<Stream> streams, List<StreamInfo> infos, int playing, OnPick onPick, OnFocus onFocus) {
            mStreams = streams;
            mInfos = infos;
            mPlaying = playing;
            mOnPick = onPick;
            mOnFocus = onFocus;
        }

        void show(List<Integer> shown) {
            mShown.clear();
            mShown.addAll(shown);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.stream_card, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int index = mShown.get(position);
            Stream stream = mStreams.get(index);
            StreamInfo info = mInfos.get(index);
            View view = holder.itemView;
            CharSequence title = title(stream, info);
            if (index == mPlaying) {
                SpannableStringBuilder playing = new SpannableStringBuilder();
                append(playing, "▶ Playing  ", CACHED, true);
                title = playing.append(title);
            }
            ((TextView) view.findViewById(R.id.card_title)).setText(title);
            List<CharSequence> lines = lines(stream, info);
            int[] ids = {R.id.card_line1, R.id.card_line2, R.id.card_line3};
            for (int i = 0; i < ids.length; i++) {
                TextView line = view.findViewById(ids[i]);
                line.setText(i < lines.size() ? lines.get(i) : "");
                line.setVisibility(i < lines.size() ? View.VISIBLE : View.INVISIBLE);
            }
            badges(view.findViewById(R.id.card_badges), info);
            view.setOnClickListener(v -> mOnPick.pick(index));
            view.setOnFocusChangeListener((v, focused) -> {
                if (focused) mOnFocus.focused(info);
            });
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }
    }
}
