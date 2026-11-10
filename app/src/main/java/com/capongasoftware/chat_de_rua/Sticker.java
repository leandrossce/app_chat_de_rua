package com.capongasoftware.chat_de_rua;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Immutable, bounded, independently encoded frames; no source media or audio is retained. */
final class Sticker {
    final byte[] bytes;
    final int width;
    final int height;
    private final byte[][] frames;
    private final int[] durations;

    Sticker(byte[] bytes, int width, int height, byte[][] frames, int[] durations) {
        this.bytes = bytes;
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.durations = durations;
    }

    int frameCount() { return frames.length; }
    int duration(int index) { return durations[index]; }
    boolean isAnimated() { return frames.length > 1; }

    Bitmap decodeFrame(int index) {
        return BitmapFactory.decodeByteArray(frames[index], 0, frames[index].length);
    }
}
