/*
 * Ported from AIOStreams (https://github.com/Viren070/AIOStreams),
 * packages/web/src/lib/subtitles/auto-sync.ts, tracks.ts and delay.ts. AIOStreams, and so this file, is
 * licensed under the GNU Affero General Public License v3.0: see LICENSE-AGPL-3.0.
 */
package com.shiggsy365.bingetvredux;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

import com.shiggsy365.bingetvredux.SubtitleAlign.Alignment;
import com.shiggsy365.bingetvredux.SubtitleAlign.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lines an addon's subtitles up with the lines of the video's own as they come, settling once
 * two tries agree or one has plenty to go on, then following the lines around the playhead for
 * as long as it runs. Not thread-safe: {@link #add} belongs on one background thread.
 */
@OptIn(markerClass = UnstableApi.class)
final class SubtitleSync {
    /** A delay to show the subtitles with, a ratio to stretch their times by, or why neither. */
    static final class Result {
        static final int DELAY = 0;
        static final int RATIO = 1;
        static final int FAILED = 2;

        final int kind;
        final long delayMs;
        final double ratio;

        private Result(int kind, long delayMs, double ratio) {
            this.kind = kind;
            this.delayMs = delayMs;
            this.ratio = ratio;
        }
    }

    // AIOStreams allows ±10 minutes; releases of one film differ by far less, and the curves
    // it needs are kept in memory for as long as the film plays.
    static final int LIMIT_MS = 120_000;

    private static final int MIN_LINES = 30;
    private static final double MIN_SPAN_MS = 60_000;
    // The lines around the playhead each try matches with, so a scene a cut moved soon wins.
    private static final int WINDOW_LINES = 100;
    // Heard without a match, the subtitles belong to something else.
    private static final int GIVE_UP_LINES = 150;
    private static final int EVERY_LINES = 10;
    private static final int WHOLE_FILM_TRIES = 8;
    // Enough lines for one answer to stand alone.
    private static final int SURE_LINES = 60;
    private static final long AGREE_MS = 300;
    // Smaller corrections aren't worth a jump.
    private static final long NUDGE_MS = 150;
    /** The fewest lines in the file's index for a track to be matched against. */
    static final int MIN_INDEX_LINES = 30;

    private final Map<String, Span> mHeard = new LinkedHashMap<>();
    private final SubtitleAlign.Curve[] mCurves = SubtitleAlign.curves(LIMIT_MS);
    private List<Span> mTarget;
    private int mReceived;
    private int mTriedAt;
    private Alignment mLast;
    private Long mApplied;
    private boolean mRetimed;
    // All the lines came at once: it's only for addWhole to give up.
    private boolean mWhole;
    private boolean mDone;

    /** {@code ratio}: the stretch already applied to the subtitles, 1 for none. */
    SubtitleSync(List<Span> target, double ratio) {
        mRetimed = ratio != 1;
        mTarget = mRetimed ? retimed(target, ratio) : target;
    }

    private static List<Span> retimed(List<Span> lines, double ratio) {
        List<Span> out = new ArrayList<>(lines.size());
        for (Span s : lines) out.add(new Span(s.startMs * ratio, s.endMs * ratio));
        return out;
    }

    /** Tries again around the playhead with the lines it has, for lines that all came at once. */
    Result follow(long positionMs) {
        if (mDone) return null;
        if (mWhole && mApplied == null) return acrossTheFilm(positionMs);
        mTriedAt = mReceived - EVERY_LINES;
        return add(new ArrayList<>(), positionMs);
    }

    /** Takes lines of the video's own subtitles as they're read; a result when they settle or move it. */
    Result add(List<Span> lines, long positionMs) {
        if (mDone) return null;
        for (Span line : lines) {
            // Players time a line to the centisecond or the millisecond.
            mHeard.put((long) (line.startMs / 10) + ":" + (long) (line.endMs / 10), line);
        }
        mReceived += lines.size();
        int count = mHeard.size();
        if (count < MIN_LINES || mReceived - mTriedAt < EVERY_LINES) return null;
        List<Span> reference = around(positionMs);
        if (reference.get(reference.size() - 1).endMs - reference.get(0).startMs < MIN_SPAN_MS) return null;
        mTriedAt = mReceived;
        return attempt(reference, count);
    }

    /**
     * Takes all of the video's lines at once, from the file's index: one quiet or cut stretch
     * says nothing about the rest, so it tries around the playhead and then across the film.
     */
    Result addWhole(List<Span> lines, long positionMs) {
        mWhole = true;
        for (Span line : lines) mHeard.put((long) (line.startMs / 10) + ":" + (long) (line.endMs / 10), line);
        mReceived += lines.size();
        mTriedAt = mReceived;
        Result result = acrossTheFilm(positionMs);
        if (result != null) return result;
        mDone = true;
        return new Result(Result.FAILED, 0, 1);
    }

    private Result acrossTheFilm(long positionMs) {
        double first = Double.MAX_VALUE;
        double last = 0;
        for (Span line : mHeard.values()) {
            first = Math.min(first, line.startMs);
            last = Math.max(last, line.endMs);
        }
        Result result = attempt(around(positionMs), mHeard.size());
        for (int i = 1; result == null && i < WHOLE_FILM_TRIES; i++) {
            result = attempt(around((long) (first + (last - first) * i / WHOLE_FILM_TRIES)), mHeard.size());
        }
        return result;
    }

    private Result attempt(List<Span> reference, int count) {
        Alignment found = SubtitleAlign.align(reference, mTarget, mCurves);
        Alignment last = mLast;
        mLast = found;
        if (found == null || !found.confident) {
            if (mWhole || mApplied != null || count < GIVE_UP_LINES) return null;
            mDone = true;
            return new Result(Result.FAILED, 0, 1);
        }
        boolean agrees = last != null && last.confident && last.ratio == found.ratio
                && Math.abs(last.delayMs - found.delayMs) <= AGREE_MS;
        if (!agrees && (mApplied != null || reference.size() < SURE_LINES)) return null;
        if (found.ratio != 1) {
            if (mApplied != null || mRetimed) return null;
            // Stretched from here on, and the delay comes from the next tries.
            mTarget = retimed(mTarget, found.ratio);
            mRetimed = true;
            mLast = null;
            mTriedAt = mReceived - EVERY_LINES;
            return new Result(Result.RATIO, 0, found.ratio);
        }
        if (mApplied != null && Math.abs(found.delayMs - mApplied) < NUDGE_MS) return null;
        mApplied = found.delayMs;
        return new Result(Result.DELAY, found.delayMs, 1);
    }

    private List<Span> around(long atMs) {
        List<Span> lines = new ArrayList<>(mHeard.values());
        Collections.sort(lines, (a, b) -> Double.compare(a.startMs, b.startMs));
        int next = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startMs >= atMs) {
                next = i;
                break;
            }
        }
        int from = Math.max(0, Math.min(next - WINDOW_LINES / 2, lines.size() - WINDOW_LINES));
        return new ArrayList<>(lines.subList(from, Math.min(lines.size(), from + WINDOW_LINES)));
    }

    /**
     * How well subtitles fit the video's own lines, from three places in the film: the best
     * clear match, 0 for none. {@code curves} are reused from one subtitle to the next.
     */
    static double fit(List<Span> subtitles, List<Span> video, SubtitleAlign.Curve[] curves) {
        List<Span> sorted = new ArrayList<>(video);
        Collections.sort(sorted, (a, b) -> Double.compare(a.startMs, b.startMs));
        double best = 0;
        for (int quarter = 1; quarter <= 3; quarter++) {
            int from = Math.max(0, Math.min(sorted.size() * quarter / 4 - WINDOW_LINES / 2, sorted.size() - WINDOW_LINES));
            Alignment found = SubtitleAlign.align(sorted.subList(from, Math.min(sorted.size(), from + WINDOW_LINES)),
                    subtitles, curves);
            if (found != null && found.confident) best = Math.max(best, found.match);
        }
        return best;
    }

    // ---- Which of the video's own subtitles to match against

    private static final Pattern COMMENTARY = Pattern.compile("\\bcommentary\\b");
    private static final Pattern SIGNS = Pattern.compile("\\b(signs?|songs?)\\b");
    private static final Pattern FORCED = Pattern.compile("\\bforced\\b");
    private static final Pattern SDH = Pattern.compile("\\b(sdh|cc|hearing[ -]impaired)\\b");

    /**
     * How good a track of the video's is to match against, lower first; -1 for one that won't
     * do, being in a format whose lines can't be had or only having some of the lines. Text
     * comes before pictures and then the subtitles' own language, or the other way round with
     * the file's index, which times every track alike; not for the hard of hearing comes last.
     */
    static int rank(String mimeType, String codecs, int selectionFlags, int roleFlags, String label,
                    boolean sameLanguage, boolean indexed) {
        // Subtitles parsed while the file is read keep their own format here.
        String format = MimeTypes.APPLICATION_MEDIA3_CUES.equals(mimeType) ? codecs : mimeType;
        boolean picture = MimeTypes.APPLICATION_PGS.equals(format);
        boolean text = MimeTypes.APPLICATION_SUBRIP.equals(format) || MimeTypes.TEXT_SSA.equals(format)
                || MimeTypes.TEXT_VTT.equals(format) || MimeTypes.APPLICATION_TX3G.equals(format);
        if (!picture && !text) return -1;
        String title = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (COMMENTARY.matcher(title).find() || SIGNS.matcher(title).find()) return -1;
        if ((selectionFlags & C.SELECTION_FLAG_FORCED) != 0 || FORCED.matcher(title).find()) return -1;
        boolean hearingImpaired = (roleFlags & (C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0
                || SDH.matcher(title).find();
        return (indexed ? (sameLanguage ? 0 : 4) + (picture ? 2 : 0) : (picture ? 4 : 0) + (sameLanguage ? 0 : 2))
                + (hearingImpaired ? 1 : 0);
    }

    // ---- Timing by ear

    private static final long MAX_TAP_GAP_MS = 30_000;
    private static final long MAX_DELAY_MS = 600_000;

    /**
     * The delay from when a line was heard and when its subtitle showed; null when too far apart
     * to be one line. Reaction time is in both presses and cancels out.
     */
    static Long delayForTaps(long delayMs, long heardAtMs, long sawAtMs) {
        long gap = heardAtMs - sawAtMs;
        if (Math.abs(gap) > MAX_TAP_GAP_MS) return null;
        return Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs + gap));
    }
}
