package com.capongasoftware.chat_de_rua;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Private files survive chat cleanup, disconnection and process restarts. */
final class StickerStore {
    static final class Entry {
        final File file;
        final String name;
        final boolean animated;

        Entry(File file, String name, boolean animated) {
            this.file = file; this.name = name; this.animated = animated;
        }

        File thumbnail() { return new File(file.getParentFile(), file.getName() + ".png"); }
    }

    private final File directory;

    StickerStore(Context context) { directory = new File(context.getFilesDir(), "stickers"); }

    synchronized Entry save(String name, Sticker sticker) throws IOException {
        name = name.trim();
        if (name.isEmpty() || name.length() > 60) throw new IOException("Nome inválido");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Armazenamento indisponível");
        Entry entry = new Entry(new File(directory, UUID.randomUUID() + ".sticker"), name, sticker.isAnimated());
        AtomicFile atomic = new AtomicFile(entry.file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            DataOutputStream output = new DataOutputStream(stream);
            output.writeUTF(name);
            output.writeBoolean(sticker.isAnimated());
            output.writeInt(sticker.bytes.length);
            output.write(sticker.bytes);
            output.flush();
            Bitmap frame = sticker.decodeFrame(0);
            if (frame == null) throw new IOException("Figurinha inválida");
            Bitmap thumbnail = null;
            try {
                float scale = Math.min(1f, 128f / Math.max(frame.getWidth(), frame.getHeight()));
                thumbnail = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)),
                        Math.max(1, Math.round(frame.getHeight() * scale)), true);
                try (FileOutputStream image = new FileOutputStream(entry.thumbnail())) {
                    if (!thumbnail.compress(Bitmap.CompressFormat.PNG, 100, image)) throw new IOException("Miniatura inválida");
                }
            } finally {
                if (thumbnail != null && thumbnail != frame) thumbnail.recycle();
                frame.recycle();
            }
            atomic.finishWrite(stream);
            stream = null;
            return entry;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            atomic.failWrite(stream);
            entry.thumbnail().delete();
            throw e;
        }
    }

    synchronized List<Entry> list() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".sticker"));
        List<Entry> entries = new ArrayList<>();
        if (files == null) return entries;
        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        for (File file : files) {
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                String name = input.readUTF();
                boolean animated = input.readBoolean();
                if (!name.isEmpty() && name.length() <= 60) entries.add(new Entry(file, name, animated));
            } catch (IOException ignored) { /* An interrupted/corrupt file must not hide the library. */ }
        }
        return entries;
    }

    synchronized Sticker load(Entry entry) throws IOException {
        try (DataInputStream input = new DataInputStream(new AtomicFile(entry.file).openRead())) {
            input.readUTF(); input.readBoolean();
            int size = input.readInt();
            if (size < 1 || size > StickerCodec.MAX_BYTES) throw new IOException("Figurinha inválida");
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            if (input.read() != -1) throw new IOException("Figurinha inválida");
            return StickerCodec.decode(bytes);
        }
    }

    synchronized void delete(Entry entry) {
        new AtomicFile(entry.file).delete();
        entry.thumbnail().delete();
    }
}
