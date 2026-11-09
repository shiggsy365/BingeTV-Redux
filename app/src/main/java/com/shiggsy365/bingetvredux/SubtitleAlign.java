/*
 * Ported from AIOStreams (https://github.com/Viren070/AIOStreams),
 * packages/web/src/lib/subtitles/align.ts. AIOStreams, and so this file, is licensed under the
 * GNU Affero General Public License v3.0: see LICENSE-AGPL-3.0.
 */
package com.shiggsy365.bingetvredux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds the delay that lines one subtitle up with another, matching on timing alone, so the two
 * can be in different languages.
 */
final class SubtitleAlign {
    /** One line's time on screen, which is when someone speaks. */
    static final class Span {
        final double startMs;
        final double endMs;

        Span(double startMs, double endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    static final class Alignment {
        /** Shows the target's lines in time with the reference's (later +, earlier −). */
        final long delayMs;
        /** The target runs at this rate of the reference, as one timed for another frame rate does. */
        final double ratio;
        /** Clear of every other delay. */
        final boolean confident;

        Alignment(long delayMs, double ratio, boolean confident) {
            this.delayMs = delayMs;
            this.ratio = ratio;
            this.confident = confident;
        }
    }

    // Longer lines are signs or songs, not speech.
    private static final double LONGEST_MS = 12_000;
    // A longer gap between the reference's lines is a stretch it wasn't read over.
    private static final double UNREAD_GAP_MS = 60_000;
    private static final int STEP_MS = 10;
    // Delays this close to the best match are its own edges.
    private static final int NEAR_MS = 3000;
    private static final double MIN_MATCH = 0.5;
    private static final double MIN_LEAD = 1.6;
    // Lines starting or ending this close together start or end together.
    private static final double EDGE_MS = 150;
    private static final double MIN_EDGE_LEAD = 2;
    private static final double AGREE_MS = 300;

    private static final double FILM = 24000d / 1001;
    // Film played at the PAL rate and back.
    private static final double[] RATIOS = {1, 25 / FILM, FILM / 25};

    private SubtitleAlign() {
    }

    /** The speech in {@code lines}, in order, with overlapping lines joined. */
    private static List<Span> speech(List<Span> lines, double ratio) {
        List<Span> sorted = new ArrayList<>(lines.size());
        for (Span l : lines) {
            if (l.endMs > l.startMs && l.endMs - l.startMs <= LONGEST_MS) {
                sorted.add(new Span(l.startMs * ratio, l.endMs * ratio));
            }
        }
        Collections.sort(sorted, (a, b) -> Double.compare(a.startMs, b.startMs));
        return join(sorted, 0);
    }

    /** Sorted spans with those less than {@code gapMs} apart joined. */
    private static List<Span> join(List<Span> spans, double gapMs) {
        List<Span> joined = new ArrayList<>(spans.size());
        for (Span span : spans) {
            Span last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (last != null && span.startMs - last.endMs <= gapMs) {
                joined.set(joined.size() - 1, new Span(last.startMs, Math.max(last.endMs, span.endMs)));
            } else {
                joined.add(span);
            }
        }
        return joined;
    }

    /** The times the lines start and end at, sorted: { starts, ends }. */
    private static double[][] edges(List<Span> lines, double ratio) {
        double[] starts = new double[lines.size()];
        double[] ends = new double[lines.size()];
        int n = 0;
        for (Span l : lines) {
            if (l.endMs > l.startMs && l.endMs - l.startMs <= LONGEST_MS) {
                starts[n] = l.startMs * ratio;
                ends[n++] = l.endMs * ratio;
            }
        }
        starts = Arrays.copyOf(starts, n);
        ends = Arrays.copyOf(ends, n);
        Arrays.sort(starts);
        Arrays.sort(ends);
        return new double[][]{starts, ends};
    }

    /**
     * A curve over the delays from -limit to +limit, summed from its changes of slope. Changes
     * before it only set where it starts, so it keeps no more than its own delays. Cleared and
     * reused, so a sync running for a whole film isn't forever allocating them.
     */
    static final class Curve {
        final double[] values;
        final int limitMs;
        private double mSlope;
        private double mValue;

        Curve(int limitMs) {
            this.limitMs = limitMs;
            values = new double[2 * limitMs / STEP_MS + 1];
        }

        void clear() {
            Arrays.fill(values, 0);
            mSlope = 0;
            mValue = 0;
        }

        void add(double delayMs, double change) {
            long i = Math.round((delayMs + limitMs) / STEP_MS);
            if (i >= values.length) return;
            if (i >= 0) {
                values[(int) i] += change;
            } else {
                mSlope += change;
                mValue -= change * i * STEP_MS;
            }
        }

        double[] sum() {
            double slope = mSlope;
            double value = mValue;
            for (int i = 0; i < values.length; i++) {
                double change = values[i];
                values[i] = value;
                slope += change;
                value += slope * STEP_MS;
            }
            return values;
        }
    }

    /**
     * How long {@code target}, moved by each delay from -limit to +limit, overlaps {@code fixed}.
     * A pair of spans overlaps over a trapezoid of delays, added through its four changes of
     * slope, so the whole curve costs one pass.
     */
    private static double[] overlaps(List<Span> fixed, List<Span> target, Curve curve) {
        curve.clear();
        int limitMs = curve.limitMs;
        int lo = 0;
        int hi = 0;
        for (Span t : target) {
            while (lo < fixed.size() && fixed.get(lo).endMs - t.startMs <= -limitMs) lo++;
            while (hi < fixed.size() && fixed.get(hi).startMs - t.endMs < limitMs) hi++;
            for (int i = lo; i < hi; i++) {
                Span f = fixed.get(i);
                double first = f.startMs - t.endMs;
                double last = f.endMs - t.startMs;
                double full = Math.min(f.endMs - f.startMs, t.endMs - t.startMs);
                curve.add(first, 1);
                curve.add(first + full, -1);
                curve.add(last - full, -1);
                curve.add(last, 1);
            }
        }
        return curve.sum();
    }

    /** How many of {@code target}'s times each delay brings near one of {@code fixed}'s, nearer counting more. */
    private static double[] meetings(double[] fixed, double[] target, Curve curve) {
        curve.clear();
        int limitMs = curve.limitMs;
        int lo = 0;
        int hi = 0;
        for (double t : target) {
            while (lo < fixed.length && fixed[lo] - t < -limitMs - EDGE_MS) lo++;
            while (hi < fixed.length && fixed[hi] - t <= limitMs + EDGE_MS) hi++;
            for (int i = lo; i < hi; i++) {
                double delay = fixed[i] - t;
                curve.add(delay - EDGE_MS, 1 / EDGE_MS);
                curve.add(delay, -2 / EDGE_MS);
                curve.add(delay + EDGE_MS, 1 / EDGE_MS);
            }
        }
        return curve.sum();
    }

    /** A curve's best delay and its lead over the best delay clear of it. */
    private static final class Peak {
        final double delayMs;
        final double value;
        final double lead;

        Peak(double delayMs, double value, double lead) {
            this.delayMs = delayMs;
            this.value = value;
            this.lead = lead;
        }
    }

    private static Peak peak(double[] curve, int limitMs) {
        int best = 0;
        double sum = 0;
        for (int i = 0; i < curve.length; i++) {
            sum += curve[i];
            if (curve[i] > curve[best]) best = i;
        }
        double top = curve[best];
        // The middle of the delays that match about as well as the best.
        int left = best;
        int right = best;
        while (left > 0 && curve[left - 1] >= top * 0.99) left--;
        while (right < curve.length - 1 && curve[right + 1] >= top * 0.99) right++;
        double next = 0;
        for (int i = 0; i < curve.length; i++) {
            if (Math.abs(i - best) * STEP_MS > NEAR_MS) next = Math.max(next, curve[i]);
        }
        double chance = sum / curve.length;
        return new Peak((left + right) / 2d * STEP_MS - limitMs, top,
                next > chance ? (top - chance) / (next - chance) : Double.POSITIVE_INFINITY);
    }

    private static final class Match {
        final double delayMs;
        final double ratio;
        // The share of the speech both have, out of the speech either has.
        final double match;
        // How far the best delay rises above chance, against the next best clear of it.
        final double lead;

        Match(double delayMs, double ratio, double match, double lead) {
            this.delayMs = delayMs;
            this.ratio = ratio;
            this.match = match;
            this.lead = lead;
        }
    }

    private static Match match(List<Span> heard, List<Span> read, List<Span> target, double ratio, Curve[] curves) {
        double[] both = overlaps(heard, target, curves[0]);
        double[] shown = overlaps(read, target, curves[1]);
        double spoken = 0;
        for (Span s : heard) spoken += s.endMs - s.startMs;
        for (int i = 0; i < both.length; i++) {
            double either = spoken + shown[i] - both[i];
            both[i] /= either != 0 ? either : 1;
        }
        Peak best = peak(both, curves[0].limitMs);
        return new Match(best.delayMs, ratio, best.value, best.lead);
    }

    private static double[] inside(double[] times, double fromMs, double toMs) {
        double[] kept = new double[times.length];
        int n = 0;
        for (double t : times) {
            if (t > fromMs && t < toMs) kept[n++] = t;
        }
        return Arrays.copyOf(kept, n);
    }

    /**
     * Dense dialogue fills so much of the time that delays seconds apart overlap almost as well;
     * the moments lines start and end at still tell them apart.
     */
    private static boolean edgesAgree(List<Span> reference, List<Span> target, Match found,
                                      double fromMs, double toMs, Curve[] curves) {
        double[][] fixed = edges(reference, 1);
        double[][] moved = edges(target, found.ratio);
        double[] starts = meetings(fixed[0], inside(moved[0], fromMs, toMs), curves[0]);
        double[] ends = meetings(fixed[1], inside(moved[1], fromMs, toMs), curves[1]);
        for (int i = 0; i < starts.length; i++) starts[i] += ends[i];
        Peak best = peak(starts, curves[0].limitMs);
        return best.lead >= MIN_EDGE_LEAD && Math.abs(best.delayMs - found.delayMs) <= AGREE_MS;
    }

    /** Two curves over ±{@code limitMs}, for {@link #align}. */
    static Curve[] curves(int limitMs) {
        return new Curve[]{new Curve(limitMs), new Curve(limitMs)};
    }

    /**
     * The delay within the curves' limit that lines {@code target} up with {@code reference},
     * which may cover only parts of the video; null when there's nothing to compare.
     */
    static Alignment align(List<Span> reference, List<Span> target, Curve[] curves) {
        List<Span> heard = speech(reference, 1);
        if (heard.isEmpty()) return null;
        List<Span> read = join(heard, UNREAD_GAP_MS);
        // Only the target's lines that could land among the reference's.
        int limitMs = curves[0].limitMs;
        double fromMs = heard.get(0).startMs - limitMs;
        double toMs = heard.get(heard.size() - 1).endMs + limitMs;
        Match found = null;
        for (double ratio : RATIOS) {
            List<Span> lines = new ArrayList<>();
            for (Span s : speech(target, ratio)) {
                if (s.endMs > fromMs && s.startMs < toMs) lines.add(s);
            }
            if (lines.isEmpty()) continue;
            Match m = match(heard, read, lines, ratio, curves);
            if (found == null || m.match > found.match) found = m;
        }
        if (found == null) return null;
        boolean confident = found.match >= MIN_MATCH
                && (found.lead >= MIN_LEAD || edgesAgree(reference, target, found, fromMs, toMs, curves));
        return new Alignment(Math.round(found.delayMs), found.ratio, confident);
    }
}
