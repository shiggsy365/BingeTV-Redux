package com.shiggsy365.bingetvredux;

import android.util.Log;

import com.shiggsy365.bingetvredux.SubtitleAlign.Span;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When a Matroska file's own subtitles show, read from the index mkvmerge writes rather than from
 * the film: the file's start for where the index is, then the index, a megabyte or two however
 * big the file. Files without subtitle lines in their index give nothing, and are matched as
 * they play instead.
 */
final class SubtitleIndex {
    private static final String TAG = "Subtitles";
    private static final int HEAD_BYTES = 256 * 1024;
    private static final long MAX_INDEX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CUE_POINT_BYTES = 64 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private static final long EBML = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long SEEK_HEAD = 0x114D9B74L;
    private static final long SEEK = 0x4DBBL;
    private static final long SEEK_ID = 0x53ABL;
    private static final long SEEK_POSITION = 0x53ACL;
    private static final long INFO = 0x1549A966L;
    private static final long TIMESTAMP_SCALE = 0x2AD7B1L;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long CUES = 0x1C53BB6BL;
    private static final long CUE_POINT = 0xBBL;
    private static final long CUE_TIME = 0xB3L;
    private static final long CUE_TRACK_POSITIONS = 0xB7L;
    private static final long CUE_TRACK = 0xF7L;
    private static final long CUE_DURATION = 0xB2L;
    private static final byte[] CUES_ID = {0x1C, 0x53, (byte) 0xBB, 0x6B};

    private SubtitleIndex() {
    }

    /** Where a file's index is, and its unit of time. */
    static final class Layout {
        final long cuesPosition;
        final long timestampScale;

        Layout(long cuesPosition, long timestampScale) {
            this.cuesPosition = cuesPosition;
            this.timestampScale = timestampScale;
        }
    }

    /** The lines in the index of the file at {@code url}, by track number; empty when there are none. */
    static Map<Long, List<Span>> read(String url, Map<String, String> headers) {
        try {
            byte[] head;
            HttpURLConnection connection = open(url, headers, "bytes=0-");
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                head = readUpTo(in, HEAD_BYTES);
            } finally {
                connection.disconnect();
            }
            Layout layout = layout(head);
            if (layout == null) {
                Log.i(TAG, "No index position in the file's first " + head.length + " bytes");
                return new HashMap<>();
            }
            connection = open(url, headers, "bytes=" + layout.cuesPosition + "-");
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 64 * 1024)) {
                return lines(in, layout.timestampScale);
            } finally {
                connection.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "No index to read: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /** A ranged request, following redirects even between http and https. */
    private static HttpURLConnection open(String url, Map<String, String> headers, String range) throws IOException {
        String next = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(next).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            // As the player asks: some servers turn away anything else.
            c.setRequestProperty("Range", range);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            }
            int status = c.getResponseCode();
            if (status == HttpURLConnection.HTTP_PARTIAL) return c;
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (status / 100 != 3 || location == null) throw new IOException("HTTP " + status + " for " + range);
            next = new URL(new URL(next), location).toString();
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] readUpTo(InputStream in, int limit) throws IOException {
        byte[] data = new byte[limit];
        int n = 0;
        int read;
        while (n < limit && (read = in.read(data, n, limit - n)) != -1) n += read;
        return n == limit ? data : Arrays.copyOf(data, n);
    }

    /** Elements in a block of bytes: an id, a size, then that many bytes. */
    private static final class Elements {
        final byte[] data;
        final int end;
        int at;
        long id;
        // -1: unknown.
        long size;

        Elements(byte[] data, int from, int end) {
            this.data = data;
            this.at = from;
            this.end = end;
        }

        /** Moves to the next element's content; false at the end of the block or a damaged one. */
        boolean next() {
            if (at >= end) return false;
            int idLength = Integer.numberOfLeadingZeros(data[at] & 0xFF) - 23;
            if (idLength > 4 || at + idLength >= end) return false;
            id = 0;
            for (int i = 0; i < idLength; i++) id = id << 8 | (data[at + i] & 0xFF);
            at += idLength;
            int sizeLength = Integer.numberOfLeadingZeros(data[at] & 0xFF) - 23;
            if (sizeLength > 8 || at + sizeLength > end) return false;
            size = (data[at] & 0xFF) & (0xFF >> sizeLength);
            boolean unknown = size == (0xFF >> sizeLength);
            for (int i = 1; i < sizeLength; i++) {
                size = size << 8 | (data[at + i] & 0xFF);
                unknown &= (data[at + i] & 0xFF) == 0xFF;
            }
            if (unknown) size = -1;
            at += sizeLength;
            return true;
        }

        /** Whether all of this element is in the block. */
        boolean whole() {
            return size >= 0 && size <= end - at;
        }

        void skip() {
            at += (int) size;
        }

        long number() {
            long value = 0;
            for (int i = 0; i < size; i++) value = value << 8 | (data[at + i] & 0xFF);
            return value;
        }
    }

    /** Where the index is, from the file's first bytes; null when they don't say. */
    static Layout layout(byte[] head) {
        Elements top = new Elements(head, 0, head.length);
        if (!top.next() || top.id != EBML || !top.whole()) return null;
        top.skip();
        if (!top.next() || top.id != SEGMENT) return null;
        long segment = top.at;
        long cues = -1;
        long scale = 1_000_000;
        boolean scaleKnown = false;
        Elements children = new Elements(head, top.at, head.length);
        while ((cues < 0 || !scaleKnown) && children.next() && children.id != CLUSTER && children.whole()) {
            if (children.id == SEEK_HEAD) {
                long position = cuesPosition(head, children.at, children.at + (int) children.size);
                if (position >= 0) cues = position;
            } else if (children.id == INFO) {
                Elements info = new Elements(head, children.at, children.at + (int) children.size);
                while (info.next() && info.whole()) {
                    if (info.id == TIMESTAMP_SCALE) scale = info.number();
                    info.skip();
                }
                scaleKnown = true;
            }
            children.skip();
        }
        return cues < 0 ? null : new Layout(segment + cues, scale);
    }

    private static long cuesPosition(byte[] data, int from, int to) {
        Elements seeks = new Elements(data, from, to);
        while (seeks.next() && seeks.whole()) {
            if (seeks.id == SEEK) {
                Elements seek = new Elements(data, seeks.at, seeks.at + (int) seeks.size);
                boolean isCues = false;
                long position = -1;
                while (seek.next() && seek.whole()) {
                    if (seek.id == SEEK_ID) {
                        isCues = Arrays.equals(Arrays.copyOfRange(data, seek.at, seek.at + (int) seek.size), CUES_ID);
                    } else if (seek.id == SEEK_POSITION) {
                        position = seek.number();
                    }
                    seek.skip();
                }
                if (isCues && position >= 0) return position;
            }
            seeks.skip();
        }
        return -1;
    }

    /** Reads an index in order, counting how far it has got. */
    private static final class Input {
        private final InputStream mIn;
        long consumed;

        Input(InputStream in) {
            mIn = in;
        }

        int read() throws IOException {
            int b = mIn.read();
            if (b < 0) throw new EOFException();
            consumed++;
            return b;
        }

        void read(byte[] into, int length) throws IOException {
            int n = 0;
            while (n < length) {
                int read = mIn.read(into, n, length - n);
                if (read < 0) throw new EOFException();
                n += read;
            }
            consumed += length;
        }

        long id() throws IOException {
            int first = read();
            int length = Integer.numberOfLeadingZeros(first) - 23;
            if (length > 4) throw new IOException("Damaged index");
            long id = first;
            for (int i = 1; i < length; i++) id = id << 8 | read();
            return id;
        }

        /** An element's size; -1 when it's unknown. */
        long size() throws IOException {
            int first = read();
            int length = Integer.numberOfLeadingZeros(first) - 23;
            if (length > 8) throw new IOException("Damaged index");
            long size = first & (0xFF >> length);
            boolean unknown = size == (0xFF >> length);
            for (int i = 1; i < length; i++) {
                int b = read();
                size = size << 8 | b;
                unknown &= b == 0xFF;
            }
            return unknown ? -1 : size;
        }
    }

    /** The subtitle lines (the entries with a duration) in the index {@code stream} starts at. */
    static Map<Long, List<Span>> lines(InputStream stream, long timestampScale) throws IOException {
        Input in = new Input(stream);
        if (in.id() != CUES) throw new IOException("Not an index");
        long size = in.size();
        if (size < 0 || size > MAX_INDEX_BYTES) throw new IOException("Index size " + size);
        long end = in.consumed + size;
        Map<Long, List<Span>> tracks = new HashMap<>();
        byte[] buffer = new byte[4096];
        double msPerTick = timestampScale / 1_000_000d;
        while (in.consumed < end) {
            long id = in.id();
            long length = in.size();
            if (length < 0 || length > MAX_CUE_POINT_BYTES) throw new IOException("Damaged index");
            if (length > buffer.length) buffer = new byte[(int) length];
            in.read(buffer, (int) length);
            if (id == CUE_POINT) cuePoint(buffer, (int) length, msPerTick, tracks);
        }
        return tracks;
    }

    private static void cuePoint(byte[] data, int length, double msPerTick, Map<Long, List<Span>> tracks) {
        long time = -1;
        Elements point = new Elements(data, 0, length);
        while (point.next() && point.whole()) {
            if (point.id == CUE_TIME) time = point.number();
            point.skip();
        }
        if (time < 0) return;
        point = new Elements(data, 0, length);
        while (point.next() && point.whole()) {
            if (point.id == CUE_TRACK_POSITIONS) {
                addLine(data, point.at, point.at + (int) point.size, time, msPerTick, tracks);
            }
            point.skip();
        }
    }

    private static void addLine(byte[] data, int from, int to, long time, double msPerTick,
                                Map<Long, List<Span>> tracks) {
        Elements position = new Elements(data, from, to);
        long track = -1;
        long duration = 0;
        while (position.next() && position.whole()) {
            if (position.id == CUE_TRACK) track = position.number();
            else if (position.id == CUE_DURATION) duration = position.number();
            position.skip();
        }
        if (track < 0 || duration <= 0) return;
        List<Span> lines = tracks.get(track);
        if (lines == null) {
            lines = new ArrayList<>();
            tracks.put(track, lines);
        }
        double start = time * msPerTick;
        lines.add(new Span(start, start + duration * msPerTick));
    }
}
