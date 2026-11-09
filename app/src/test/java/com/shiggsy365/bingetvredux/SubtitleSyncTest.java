package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;

import com.shiggsy365.bingetvredux.SubtitleAlign.Alignment;
import com.shiggsy365.bingetvredux.SubtitleAlign.Span;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Addon subtitles line up with the video's own by timing alone. */
public class SubtitleSyncTest {
    private static final double PAL = 25 / (24000d / 1001);

    /** A film's dialogue: lines of 1–4 s with gaps of 1–8 s. */
    private static List<Span> film(long seed, int count) {
        Random random = new Random(seed);
        List<Span> lines = new ArrayList<>();
        double at = 5000;
        for (int i = 0; i < count; i++) {
            double length = 1000 + random.nextInt(3000);
            lines.add(new Span(at, at + length));
            at += length + 1000 + random.nextInt(7000);
        }
        return lines;
    }

    private static List<Span> moved(List<Span> lines, double byMs, double rate) {
        List<Span> out = new ArrayList<>();
        for (Span l : lines) out.add(new Span(l.startMs * rate + byMs, l.endMs * rate + byMs));
        return out;
    }

    /** The same lines timed by a different hand: each edge up to 100 ms out. */
    private static List<Span> retyped(List<Span> lines, long seed) {
        Random random = new Random(seed);
        List<Span> out = new ArrayList<>();
        for (Span l : lines) {
            out.add(new Span(l.startMs + random.nextInt(201) - 100, l.endMs + random.nextInt(201) - 100));
        }
        return out;
    }

    private static Alignment align(List<Span> reference, List<Span> target) {
        return SubtitleAlign.align(reference, target, SubtitleAlign.curves(SubtitleSync.LIMIT_MS));
    }

    @Test
    public void findsTheDelayThatMovesTheTargetOntoTheReference() {
        List<Span> reference = film(1, 200);
        // Target shown 2.5 s early: it needs a positive delay, the same sense as the OSD's offset.
        Alignment early = align(reference, retyped(moved(reference, -2500, 1), 2));
        assertTrue(early.confident);
        assertEquals(1, early.ratio, 0);
        assertEquals(2500, early.delayMs, 150);
        Alignment late = align(reference, retyped(moved(reference, 40_000, 1), 3));
        assertTrue(late.confident);
        assertEquals(-40_000, late.delayMs, 150);
    }

    @Test
    public void findsAFrameRateMismatch() {
        List<Span> reference = film(4, 120);
        Alignment found = align(reference, moved(reference, 0, 1 / PAL));
        assertTrue(found.confident);
        assertEquals(PAL, found.ratio, 1e-9);
    }

    @Test
    public void unrelatedSubtitlesAreNotAMatch() {
        assertFalse(align(film(5, 200), film(6, 200)).confident);
    }

    @Test
    public void nothingToCompareIsNoAnswer() {
        assertEquals(null, align(Collections.emptyList(), film(7, 50)));
    }

    /** Feeds lines one at a time, as they play, up to the first result. */
    private static SubtitleSync.Result feed(SubtitleSync sync, List<Span> lines) {
        for (Span line : lines) {
            SubtitleSync.Result result = sync.add(Collections.singletonList(line), (long) line.endMs);
            if (result != null) return result;
        }
        return null;
    }

    @Test
    public void settlesOnADelayAsLinesArrive() {
        List<Span> video = film(8, 200);
        SubtitleSync sync = new SubtitleSync(retyped(moved(video, 3000, 1), 9), 1);
        SubtitleSync.Result result = feed(sync, video);
        assertNotNull(result);
        assertEquals(SubtitleSync.Result.DELAY, result.kind);
        assertEquals(-3000, result.delayMs, 150);
    }

    @Test
    public void givesNothingBeforeThereAreEnoughLines() {
        List<Span> video = film(10, 25);
        assertEquals(null, feed(new SubtitleSync(moved(video, 3000, 1), 1), video));
    }

    @Test
    public void retimesThenFindsTheDelay() {
        List<Span> video = film(11, 300);
        // Made for a 25 fps release, and starting 2 s out as well.
        SubtitleSync sync = new SubtitleSync(moved(moved(video, 2000, 1), 0, 1 / PAL), 1);
        List<Span> remaining = new ArrayList<>(video);
        SubtitleSync.Result first = null;
        while (first == null && !remaining.isEmpty()) {
            Span line = remaining.remove(0);
            first = sync.add(Collections.singletonList(line), (long) line.endMs);
        }
        assertNotNull(first);
        assertEquals(SubtitleSync.Result.RATIO, first.kind);
        assertEquals(PAL, first.ratio, 1e-9);
        SubtitleSync.Result second = feed(sync, remaining);
        assertNotNull(second);
        assertEquals(SubtitleSync.Result.DELAY, second.kind);
        assertEquals(-2000, second.delayMs, 150);
    }

    @Test
    public void givesUpOnSubtitlesForSomethingElse() {
        SubtitleSync sync = new SubtitleSync(film(12, 300), 1);
        SubtitleSync.Result result = feed(sync, film(13, 300));
        assertNotNull(result);
        assertEquals(SubtitleSync.Result.FAILED, result.kind);
    }

    private static int rank(String mime, int flags, String label, boolean sameLanguage) {
        return SubtitleSync.rank(mime, null, flags, 0, label, sameLanguage, false);
    }

    @Test
    public void ranksTheVideosOwnSubtitles() {
        int english = rank(MimeTypes.APPLICATION_SUBRIP, 0, "English", true);
        assertEquals(0, english);
        assertTrue(english < rank(MimeTypes.APPLICATION_SUBRIP, 0, "French", false));
        assertTrue(rank(MimeTypes.APPLICATION_SUBRIP, 0, "French", false) < rank(MimeTypes.APPLICATION_PGS, 0, "English", true));
        assertTrue(english < rank(MimeTypes.APPLICATION_SUBRIP, 0, "English SDH", true));
    }

    @Test
    public void skipsTracksWithOnlySomeOfTheLines() {
        assertEquals(-1, rank(MimeTypes.APPLICATION_SUBRIP, C.SELECTION_FLAG_FORCED, "English", true));
        assertEquals(-1, rank(MimeTypes.APPLICATION_SUBRIP, 0, "English (Forced)", true));
        assertEquals(-1, rank(MimeTypes.TEXT_SSA, 0, "Signs & Songs", true));
        assertEquals(-1, rank(MimeTypes.APPLICATION_SUBRIP, 0, "Director's Commentary", true));
        assertEquals(-1, rank(MimeTypes.APPLICATION_VOBSUB, 0, "English", true));
    }

    @Test
    public void withTheIndexTheLanguageCountsBeforeTheFormat() {
        int picture = SubtitleSync.rank(MimeTypes.APPLICATION_PGS, null, 0, 0, "", true, true);
        int otherText = SubtitleSync.rank(MimeTypes.APPLICATION_SUBRIP, null, 0, 0, "", false, true);
        assertTrue(picture < otherText);
    }

    @Test
    public void startsFromASavedRetiming() {
        List<Span> video = film(14, 200);
        SubtitleSync sync = new SubtitleSync(moved(moved(video, 1500, 1), 0, 1 / PAL), PAL);
        SubtitleSync.Result result = feed(sync, video);
        assertNotNull(result);
        assertEquals(SubtitleSync.Result.DELAY, result.kind);
        assertEquals(-1500, result.delayMs, 150);
    }

    @Test
    public void linesThatCameAtOnceAreFollowedAroundThePlayhead() {
        List<Span> video = film(15, 300);
        SubtitleSync sync = new SubtitleSync(moved(video, 4000, 1), 1);
        SubtitleSync.Result first = sync.add(video, 0);
        assertNotNull(first);
        assertEquals(-4000, first.delayMs, 150);
        // Nothing has moved: no correction.
        assertEquals(null, sync.follow((long) video.get(150).startMs));
    }

    @Test
    public void wholeFilmLooksPastAStretchThatDoesntMatch() {
        List<Span> video = film(16, 400);
        // Nothing lines up around the start: the subtitles only begin a long way in.
        SubtitleSync sync = new SubtitleSync(moved(video.subList(150, 400), 3000, 1), 1);
        SubtitleSync.Result result = sync.addWhole(video, 0);
        assertNotNull(result);
        assertEquals(SubtitleSync.Result.DELAY, result.kind);
        assertEquals(-3000, result.delayMs, 150);
    }

    @Test
    public void wholeFilmRetimesThenFindsTheDelayStraightAway() {
        List<Span> video = film(20, 400);
        SubtitleSync sync = new SubtitleSync(moved(moved(video, 2000, 1), 0, 1 / PAL), 1);
        assertEquals(SubtitleSync.Result.RATIO, sync.addWhole(video, 0).kind);
        SubtitleSync.Result second = sync.follow(0);
        assertNotNull(second);
        assertEquals(SubtitleSync.Result.DELAY, second.kind);
        assertEquals(-2000, second.delayMs, 150);
    }

    @Test
    public void wholeFilmGivesUpOnSubtitlesForSomethingElse() {
        SubtitleSync.Result result = new SubtitleSync(film(17, 400), 1).addWhole(film(18, 400), 0);
        assertNotNull(result);
        assertEquals(SubtitleSync.Result.FAILED, result.kind);
    }

    @Test
    public void wholeFilmIsNotGivenUpOnWhileFollowing() {
        List<Span> video = film(19, 400);
        SubtitleSync sync = new SubtitleSync(moved(video, 3000, 1), 1);
        assertEquals(SubtitleSync.Result.DELAY, sync.addWhole(video, 0).kind);
        assertEquals(null, sync.follow(0));
    }

    @Test
    public void timesASubtitleByEar() {
        // Heard at 10 s, shown at 12 s: 2 s too late, from an offset of +5 s.
        assertEquals(Long.valueOf(3000), SubtitleSync.delayForTaps(5000, 10_000, 12_000));
        assertEquals(Long.valueOf(1000), SubtitleSync.delayForTaps(0, 13_000, 12_000));
        assertEquals(null, SubtitleSync.delayForTaps(0, 10_000, 45_000));
    }

    @Test
    public void readsTheFormatOfSubtitlesParsedDuringExtraction() {
        assertEquals(0, SubtitleSync.rank(MimeTypes.APPLICATION_MEDIA3_CUES, MimeTypes.APPLICATION_SUBRIP, 0, 0, "", true, false));
        assertEquals(4, SubtitleSync.rank(MimeTypes.APPLICATION_MEDIA3_CUES, MimeTypes.APPLICATION_PGS, 0, 0, "", true, false));
    }
}
