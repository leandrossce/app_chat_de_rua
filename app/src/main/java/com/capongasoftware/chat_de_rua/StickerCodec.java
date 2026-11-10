package com.capongasoftware.chat_de_rua;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Converts source media into small, metadata-free frames, usable on every supported Android. */
final class StickerCodec {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_DURATION_MS = 6000;
    static final int MAX_FRAMES = 48;
    static final int MAX_DIMENSION = 512;
    private static final int ANIMATED_DIMENSION = 384;
    private static final int FRAME_MS = 125;
    private static final int MAX_SOURCE_BYTES = 50 * 1024 * 1024;
    private static final long MAX_SOURCE_PIXELS = 64_000_000L;
    private static final int MAGIC = 0x53544b31; // STK1; versioned independently of encryption.

    interface Progress { void update(int percent) throws IOException; }

    static final class InvalidMediaException extends IOException { }
    static final class TooLargeException extends IOException { }
    static final class TooLongException extends IOException { }

    private StickerCodec() { }

    // This application uses platform APIs without AndroidX dependencies.
    @android.annotation.SuppressLint("ExifInterface")
    static Sticker fromImage(Context context, Uri uri, Progress progress) throws IOException {
        byte[] source;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            source = readBounded(input, MAX_SOURCE_BYTES);
        }
        progress.update(5);
        if (source.length >= 6 && tag(source, 0, "GIF8")) {
            return fromGif(source, progress);
        }
        if (source.length >= 30 && tag(source, 0, "RIFF") && tag(source, 8, "WEBP") &&
                tag(source, 12, "VP8X") && (source[20] & 2) != 0) {
            return fromAnimatedWebp(source, progress);
        }
        BitmapFactory.Options bounds = bounds(source);
        checkSourceDimensions(bounds.outWidth, bounds.outHeight, MAX_SOURCE_PIXELS);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (bitmap == null) throw new InvalidMediaException();
        try {
            // ExifInterface(InputStream) is available since API 24. The app also supports API 23.
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    ExifInterface exif = new ExifInterface(new ByteArrayInputStream(source));
                    int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    switch (orientation) {
                        case 2: matrix.setScale(-1, 1); break;
                        case 3: matrix.setRotate(180); break;
                        case 4: matrix.setScale(1, -1); break;
                        case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                        case 6: matrix.setRotate(90); break;
                        case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                        case 8: matrix.setRotate(-90); break;
                        default: break;
                    }
                    if (!matrix.isIdentity()) {
                        Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0,
                                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        if (oriented != bitmap) { bitmap.recycle(); bitmap = oriented; }
                    }
                } catch (IOException ignored) { /* Some formats do not contain EXIF. */ }
            }
            Builder builder = new Builder();
            builder.add(bitmap, 0, false);
            progress.update(100);
            return builder.finish();
        } finally {
            bitmap.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static Sticker fromGif(byte[] source, Progress progress) throws IOException {
        BitmapFactory.Options bounds = bounds(source);
        checkSourceDimensions(bounds.outWidth, bounds.outHeight, 4_194_304L);
        Movie movie = Movie.decodeByteArray(source, 0, source.length);
        if (movie == null) throw new InvalidMediaException();
        int duration = movie.duration();
        if (duration > MAX_DURATION_MS) throw new TooLongException();
        int[] size = fit(movie.width(), movie.height(), ANIMATED_DIMENSION);
        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(size[0] / (float) movie.width(), size[1] / (float) movie.height());
        Builder builder = new Builder();
        try {
            int count = Math.max(1, (duration + FRAME_MS - 1) / FRAME_MS);
            for (int i = 0; i < count; i++) {
                progress.update(5 + i * 90 / count);
                bitmap.eraseColor(Color.TRANSPARENT);
                movie.setTime(i * FRAME_MS);
                movie.draw(canvas, 0, 0);
                builder.add(bitmap, count == 1 ? 0 : Math.min(FRAME_MS,
                        duration - i * FRAME_MS), count > 1);
            }
            progress.update(100);
            return builder.finish();
        } finally { bitmap.recycle(); }
    }

    static Sticker fromVideo(Context context, Uri uri, Progress progress) throws IOException {
        // A bounded private copy handles providers without file descriptors or a declared size.
        File temporary = File.createTempFile("sticker_source_", ".video", context.getCacheDir());
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new InvalidMediaException();
                byte[] buffer = new byte[32 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    progress.update(3);
                    if (read > MAX_SOURCE_BYTES - total) throw new TooLargeException();
                    output.write(buffer, 0, read);
                    total += read;
                }
            }
            retriever.setDataSource(temporary.getAbsolutePath());
            int duration = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);
            int width = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int height = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (duration <= 0) throw new InvalidMediaException();
            if (duration > MAX_DURATION_MS) throw new TooLongException();
            checkSourceDimensions(width, height, 9_000_000L);
            int[] size = fit(width, height, ANIMATED_DIMENSION);
            int count = Math.max(2, (duration + FRAME_MS - 1) / FRAME_MS);
            Builder builder = new Builder();
            for (int i = 0; i < count; i++) {
                progress.update(5 + i * 90 / count);
                long timeUs = (long) i * duration * 1000 / count;
                // The retriever applies the video's rotation metadata to the returned frame.
                Bitmap frame = Build.VERSION.SDK_INT >= 27
                        ? retriever.getScaledFrameAtTime(timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST, size[0], size[1])
                        : retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) throw new InvalidMediaException();
                try {
                    builder.add(frame, Math.max(1, (i + 1) * duration / count -
                            i * duration / count), true);
                } finally { frame.recycle(); }
            }
            progress.update(100);
            return builder.finish();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new InvalidMediaException();
        } finally {
            try { retriever.release(); } finally { temporary.delete(); }
        }
    }

    /** Composites ANMF rectangles before re-encoding, including alpha, blending and disposal.
     * https://developers.google.com/speed/webp/docs/riff_container#animation
     */
    private static Sticker fromAnimatedWebp(byte[] source, Progress progress) throws IOException {
        int width = le24(source, 24) + 1;
        int height = le24(source, 27) + 1;
        checkSourceDimensions(width, height, 4_194_304L);
        if (le32(source, 4) != source.length - 8) throw new InvalidMediaException();
        List<Integer> offsets = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        int duration = 0;
        int background = Color.TRANSPARENT;
        for (int offset = 12; offset < source.length;) {
            if (source.length - offset < 8) throw new InvalidMediaException();
            int length = le32(source, offset + 4);
            if (length < 0 || length > source.length - offset - 8) throw new InvalidMediaException();
            if (tag(source, offset, "ANIM") && length >= 6) background = le32(source, offset + 8);
            if (tag(source, offset, "ANMF")) {
                if (length < 16 || offsets.size() >= 300) throw new InvalidMediaException();
                duration += Math.max(20, le24(source, offset + 20));
                if (duration > MAX_DURATION_MS) throw new TooLongException();
                offsets.add(offset + 8);
                lengths.add(length);
            }
            long next = (long) offset + 8 + length + (length & 1);
            if (next > source.length) throw new InvalidMediaException();
            offset = (int) next;
        }
        if (offsets.isEmpty()) throw new InvalidMediaException();
        Bitmap composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        composite.eraseColor(background);
        Canvas canvas = new Canvas(composite);
        Paint replace = new Paint();
        replace.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        Builder builder = new Builder();
        int nextSample = 0;
        int elapsed = 0;
        try {
            for (int i = 0; i < offsets.size(); i++) {
                progress.update(5 + i * 90 / offsets.size());
                int offset = offsets.get(i);
                int x = le24(source, offset) * 2;
                int y = le24(source, offset + 3) * 2;
                int frameWidth = le24(source, offset + 6) + 1;
                int frameHeight = le24(source, offset + 9) + 1;
                if (x > width - frameWidth || y > height - frameHeight) throw new InvalidMediaException();
                int flags = source[offset + 15] & 0xff;
                byte[] frameSource = webpFrame(source, offset + 16,
                        lengths.get(i) - 16, frameWidth, frameHeight);
                BitmapFactory.Options frameBounds = bounds(frameSource);
                if (frameBounds.outWidth != frameWidth || frameBounds.outHeight != frameHeight) {
                    throw new InvalidMediaException();
                }
                Bitmap frame = BitmapFactory.decodeByteArray(frameSource, 0, frameSource.length);
                if (frame == null) throw new InvalidMediaException();
                replace.setAlpha(255);
                try { canvas.drawBitmap(frame, x, y, (flags & 2) != 0 ? replace : null); }
                finally { frame.recycle(); }
                elapsed += Math.max(20, le24(source, offset + 12));
                while (nextSample < elapsed) {
                    builder.add(composite, Math.min(FRAME_MS, duration - nextSample), true);
                    nextSample += FRAME_MS;
                }
                if ((flags & 1) != 0) {
                    replace.setColor(background);
                    canvas.drawRect(x, y, x + frameWidth, y + frameHeight, replace);
                }
            }
            progress.update(100);
            return builder.finish();
        } finally { composite.recycle(); }
    }

    private static byte[] webpFrame(byte[] source, int offset, int length, int width,
                                    int height) throws IOException {
        // The extended header is needed for an ALPH + VP8 frame. Unknown chunks are stripped.
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        boolean alpha = false;
        boolean image = false;
        int end = offset + length;
        while (offset < end) {
            if (end - offset < 8) throw new InvalidMediaException();
            int size = le32(source, offset + 4);
            if (size < 0 || size > end - offset - 8) throw new InvalidMediaException();
            int padded = size + (size & 1);
            if (padded > end - offset - 8) throw new InvalidMediaException();
            if (tag(source, offset, "ALPH")) {
                alpha = true;
                chunks.write(source, offset, 8 + padded);
            } else if (tag(source, offset, "VP8 ") || tag(source, offset, "VP8L")) {
                if (image) throw new InvalidMediaException();
                image = true;
                chunks.write(source, offset, 8 + padded);
            }
            offset += 8 + padded;
        }
        if (!image) throw new InvalidMediaException();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{'R', 'I', 'F', 'F'});
        writeLe32(output, 4 + (alpha ? 18 : 0) + chunks.size());
        output.write(new byte[]{'W', 'E', 'B', 'P'});
        if (alpha) {
            output.write(new byte[]{'V', 'P', '8', 'X'});
            writeLe32(output, 10);
            output.write(new byte[]{16, 0, 0, 0});
            writeLe24(output, width - 1);
            writeLe24(output, height - 1);
        }
        chunks.writeTo(output);
        return output.toByteArray();
    }

    static Sticker decode(byte[] bytes) throws IOException {
        if (bytes.length < 14 || bytes.length > MAX_BYTES) throw new InvalidMediaException();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != MAGIC) throw new InvalidMediaException();
        int count = input.readUnsignedShort();
        int width = input.readUnsignedShort();
        int height = input.readUnsignedShort();
        if (count < 1 || count > MAX_FRAMES || width < 1 || height < 1 ||
                width > MAX_DIMENSION || height > MAX_DIMENSION) throw new InvalidMediaException();
        byte[][] frames = new byte[count][];
        int[] durations = new int[count];
        int totalDuration = 0;
        for (int i = 0; i < count; i++) {
            int duration = input.readUnsignedShort();
            if ((count == 1 && duration != 0) || (count > 1 && duration < 1)) {
                throw new InvalidMediaException();
            }
            totalDuration += duration;
            if (totalDuration > MAX_DURATION_MS) throw new TooLongException();
            int length = input.readInt();
            if (length <= 0 || length > input.available()) throw new InvalidMediaException();
            byte[] frame = new byte[length];
            input.readFully(frame);
            BitmapFactory.Options bounds = bounds(frame);
            if (bounds.outWidth != width || bounds.outHeight != height ||
                    !("image/png".equals(bounds.outMimeType) || "image/webp".equals(bounds.outMimeType))) {
                throw new InvalidMediaException();
            }
            // Reject nested animation and malformed bitstreams before UI playback.
            if (frame.length >= 21 && tag(frame, 12, "VP8X") && (frame[20] & 2) != 0) {
                throw new InvalidMediaException();
            }
            Bitmap decoded = BitmapFactory.decodeByteArray(frame, 0, frame.length);
            if (decoded == null) throw new InvalidMediaException();
            decoded.recycle();
            frames[i] = frame;
            durations[i] = duration;
        }
        if (input.available() != 0) throw new InvalidMediaException();
        return new Sticker(bytes, width, height, frames, durations);
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (input == null) throw new InvalidMediaException();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelado");
            if (read > limit - output.size()) throw new TooLargeException();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static final class Builder {
        final List<byte[]> frames = new ArrayList<>();
        final List<Integer> durations = new ArrayList<>();
        int bytes = 10;
        int width;
        int height;

        @SuppressWarnings("deprecation")
        void add(Bitmap source, int duration, boolean animated) throws IOException {
            if (frames.size() >= MAX_FRAMES) throw new TooLargeException();
            int[] size = fit(source.getWidth(), source.getHeight(),
                    animated ? ANIMATED_DIMENSION : MAX_DIMENSION);
            if (!frames.isEmpty() && (width != size[0] || height != size[1])) throw new InvalidMediaException();
            width = size[0]; height = size[1];
            Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                if (!scaled.compress(animated ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.PNG,
                        animated ? 82 : 100, output)) throw new InvalidMediaException();
            } finally { if (scaled != source) scaled.recycle(); }
            byte[] encoded = output.toByteArray();
            if (encoded.length > MAX_BYTES - bytes - 6) throw new TooLargeException();
            bytes += encoded.length + 6;
            frames.add(encoded);
            durations.add(duration);
        }

        Sticker finish() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes);
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeInt(MAGIC);
            output.writeShort(frames.size()); output.writeShort(width); output.writeShort(height);
            for (int i = 0; i < frames.size(); i++) {
                output.writeShort(frames.size() == 1 ? 0 : durations.get(i));
                output.writeInt(frames.get(i).length); output.write(frames.get(i));
            }
            output.flush();
            byte[] encoded = buffer.toByteArray();
            int[] times = new int[frames.size()];
            for (int i = 0; i < times.length; i++) times[i] = times.length == 1 ? 0 : durations.get(i);
            return new Sticker(encoded, width, height, frames.toArray(new byte[0][]), times);
        }
    }

    private static BitmapFactory.Options bounds(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return options;
    }

    private static void checkSourceDimensions(int width, int height, long pixels) throws IOException {
        if (width < 1 || height < 1 || (long) width * height > pixels) throw new InvalidMediaException();
    }

    private static int sampleSize(int width, int height, int limit) {
        int sample = 1;
        while (Math.max(width, height) / sample > limit * 2) sample *= 2;
        return sample;
    }

    private static int[] fit(int width, int height, int limit) {
        float scale = Math.min(1f, limit / (float) Math.max(width, height));
        return new int[]{Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }

    private static int metadataInt(MediaMetadataRetriever retriever, int key) throws IOException {
        try { return Integer.parseInt(retriever.extractMetadata(key)); }
        catch (NumberFormatException e) { throw new InvalidMediaException(); }
    }

    private static boolean tag(byte[] data, int offset, String tag) {
        if (offset < 0 || offset > data.length - tag.length()) return false;
        for (int i = 0; i < tag.length(); i++) if (data[offset + i] != tag.charAt(i)) return false;
        return true;
    }

    private static int le24(byte[] data, int offset) throws IOException {
        if (offset < 0 || offset > data.length - 3) throw new InvalidMediaException();
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8) | ((data[offset + 2] & 255) << 16);
    }

    private static int le32(byte[] data, int offset) throws IOException {
        if (offset < 0 || offset > data.length - 4) throw new InvalidMediaException();
        return le24(data, offset) | ((data[offset + 3] & 255) << 24);
    }

    private static void writeLe24(ByteArrayOutputStream output, int value) {
        output.write(value); output.write(value >>> 8); output.write(value >>> 16);
    }

    private static void writeLe32(ByteArrayOutputStream output, int value) {
        writeLe24(output, value); output.write(value >>> 24);
    }
}
