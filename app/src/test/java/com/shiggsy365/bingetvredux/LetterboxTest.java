package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Finding the black bars in a trailer's frame. */
public class LetterboxTest {
    private static final int W = 160, H = 90;

    /** A frame lit (grey) from row {@code from} to row {@code to}, black elsewhere. */
    private static int[] frame(int from, int to) {
        int[] pixels = new int[W * H];
        for (int y = from; y <= to; y++) {
            for (int x = 0; x < W; x++) pixels[y * W + x] = 0xFF808080;
        }
        return pixels;
    }

    @Test
    public void findsBarsAtTheTopAndBottom() {
        // 2.39:1 in 16:9: about an eighth of the height black above and below.
        assertArrayEquals(new float[] {11 / 90f, 11 / 90f}, Letterbox.bars(frame(11, 78), W, H), 1e-6f);
    }

    @Test
    public void noBarsInAFullPicture() {
        assertArrayEquals(new float[] {0, 0}, Letterbox.bars(frame(0, H - 1), W, H), 1e-6f);
    }

    @Test
    public void darkFramesAndTitleCardsSayNothing() {
        assertNull(Letterbox.bars(new int[W * H], W, H));
        assertNull(Letterbox.bars(frame(40, 50), W, H));
    }

    @Test
    public void noiseInTheBarsIsntPicture() {
        int[] pixels = frame(11, 78);
        pixels[3 * W + 7] = 0xFFFFFFFF;
        pixels[5 * W + 20] = 0xFF303030;
        assertArrayEquals(new float[] {11 / 90f, 11 / 90f}, Letterbox.bars(pixels, W, H), 1e-6f);
    }
}
