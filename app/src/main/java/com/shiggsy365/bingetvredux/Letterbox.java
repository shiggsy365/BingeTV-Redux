package com.shiggsy365.bingetvredux;

/**
 * Black bars burned into a video's picture (a widescreen film in a 16:9 trailer), found from a
 * small copy of one frame.
 */
final class Letterbox {
    // Brighter than this (0-255) is picture, not bar: compression leaves bars a little noisy.
    private static final int LIGHT = 40;
    // A row with this share of its pixels lit is picture.
    private static final float LIT_ROW = 0.03f;
    // Less picture than this: a frame from a dark scene or a title card, which says nothing.
    private static final float MIN_PICTURE = 0.6f;

    private Letterbox() {}

    /**
     * The bars at the top and bottom of a frame, as shares of its height; null when the frame
     * can't tell (too dark, or mostly black around a little picture).
     */
    static float[] bars(int[] pixels, int width, int height) {
        int first = -1, last = -1;
        for (int y = 0; y < height; y++) {
            int lit = 0;
            for (int x = 0; x < width; x++) {
                int p = pixels[y * width + x];
                int luma = (299 * ((p >> 16) & 0xFF) + 587 * ((p >> 8) & 0xFF) + 114 * (p & 0xFF)) / 1000;
                if (luma > LIGHT) lit++;
            }
            if (lit >= width * LIT_ROW) {
                if (first < 0) first = y;
                last = y;
            }
        }
        if (first < 0 || last - first + 1 < height * MIN_PICTURE) return null;
        return new float[] {first / (float) height, (height - 1 - last) / (float) height};
    }
}
