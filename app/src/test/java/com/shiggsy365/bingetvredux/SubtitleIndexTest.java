package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.shiggsy365.bingetvredux.SubtitleAlign.Span;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Matroska files say when their subtitles show in their index. */
public class SubtitleIndexTest {
    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] id(long id) {
        int length = id > 0xFFFFFFL ? 4 : id > 0xFFFFL ? 3 : id > 0xFFL ? 2 : 1;
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (id >> (8 * (length - 1 - i)));
        return bytes;
    }

    /** An element, its size in {@code sizeBytes} bytes as muxers may choose. */
    private static byte[] element(long elementId, int sizeBytes, byte[]... content) {
        byte[] body = join(content);
        byte[] size = new byte[sizeBytes];
        for (int i = 0; i < sizeBytes; i++) size[i] = (byte) (body.length >> (8 * (sizeBytes - 1 - i)));
        size[0] |= (byte) (0x80 >> (sizeBytes - 1));
        return join(id(elementId), size, body);
    }

    private static byte[] element(long elementId, byte[]... content) {
        return element(elementId, 1, content);
    }

    private static byte[] number(long value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] cuePoint(long time, long track, long duration) {
        byte[] position = duration > 0
                ? join(element(0xF7, new byte[]{(byte) track}), element(0xF1, number(1234)), element(0xB2, number(duration)))
                : join(element(0xF7, new byte[]{(byte) track}), element(0xF1, number(1234)));
        return element(0xBB, element(0xB3, number(time)), element(0xB7, position));
    }

    private static byte[] seek(byte[] target, long position) {
        return element(0x4DBB, element(0x53AB, target), element(0x53AC, number(position)));
    }

    private static final byte[] CUES_ID = {0x1C, 0x53, (byte) 0xBB, 0x6B};
    private static final byte[] INFO_ID = {0x15, 0x49, (byte) 0xA9, 0x66};

    private static byte[] head(byte[]... segmentChildren) {
        return join(element(0x1A45DFA3L, new byte[]{0x42, (byte) 0x86, (byte) 0x81, 1}),
                id(0x18538067L), new byte[]{0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                join(segmentChildren));
    }

    @Test
    public void findsTheIndexFromTheStartOfTheFile() {
        byte[] seekHead = element(0x114D9B74L, seek(INFO_ID, 40), seek(CUES_ID, 5_000_000));
        byte[] info = element(0x1549A966L, element(0x2AD7B1L, number(1_000_000)));
        byte[] file = head(seekHead, info, element(0x1F43B675L, new byte[]{0}));
        SubtitleIndex.Layout layout = SubtitleIndex.layout(file);
        assertNotNull(layout);
        // Positions count from the start of the segment's content.
        int segmentStart = element(0x1A45DFA3L, new byte[]{0x42, (byte) 0x86, (byte) 0x81, 1}).length + 12;
        assertEquals(segmentStart + 5_000_000L, layout.cuesPosition);
        assertEquals(1_000_000L, layout.timestampScale);
    }

    @Test
    public void readsTheTimestampScale() {
        byte[] seekHead = element(0x114D9B74L, seek(CUES_ID, 100));
        byte[] info = element(0x1549A966L, element(0x2AD7B1L, number(500_000)));
        assertEquals(500_000L, SubtitleIndex.layout(head(seekHead, info)).timestampScale);
    }

    @Test
    public void aFileThatDoesntSayHasNoLayout() {
        byte[] info = element(0x1549A966L, element(0x2AD7B1L, number(1_000_000)));
        assertNull(SubtitleIndex.layout(head(info, element(0x1F43B675L, new byte[]{0}))));
        assertNull(SubtitleIndex.layout(new byte[]{1, 2, 3, 4}));
    }

    @Test
    public void readsLinesWithADurationByTrack() throws IOException {
        byte[] index = element(0x1C53BB6BL, 4,
                cuePoint(1000, 1, 0),
                cuePoint(5000, 3, 2000),
                cuePoint(9000, 3, 1500),
                cuePoint(9000, 4, 1600));
        Map<Long, List<Span>> tracks = SubtitleIndex.lines(new ByteArrayInputStream(index), 1_000_000);
        // Video keyframes have no duration.
        assertNull(tracks.get(1L));
        assertEquals(2, tracks.get(3L).size());
        assertEquals(5000, tracks.get(3L).get(0).startMs, 0);
        assertEquals(7000, tracks.get(3L).get(0).endMs, 0);
        assertEquals(10_600, tracks.get(4L).get(0).endMs, 0);
    }

    @Test
    public void appliesTheScaleToBothEnds() throws IOException {
        byte[] index = element(0x1C53BB6BL, 4, cuePoint(4000, 2, 1000));
        Span line = SubtitleIndex.lines(new ByteArrayInputStream(index), 500_000).get(2L).get(0);
        assertEquals(2000, line.startMs, 0);
        assertEquals(2500, line.endMs, 0);
    }

    @Test(expected = IOException.class)
    public void aCutOffIndexIsAnError() throws IOException {
        byte[] index = element(0x1C53BB6BL, 4, cuePoint(5000, 3, 2000), cuePoint(9000, 3, 1500));
        SubtitleIndex.lines(new ByteArrayInputStream(index, 0, index.length - 4), 1_000_000);
    }
}
