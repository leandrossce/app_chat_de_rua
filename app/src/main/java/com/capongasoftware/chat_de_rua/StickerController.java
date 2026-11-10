package com.capongasoftware.chat_de_rua;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** Library and creation UI. Source selection uses the system picker, with no storage permission. */
final class StickerController {
    static final int PICK_IMAGE = 110;
    static final int PICK_VIDEO = 111;
    private static final int PAGE_SIZE = 9;

    interface Listener {
        boolean send(Sticker sticker);
        void stateChanged();
    }

    private final Activity activity;
    private final ExecutorService queue;
    private final Listener listener;
    private final StickerStore store;
    private Dialog current;
    private volatile int generation;
    private volatile boolean closed;
    private boolean busy;
    private boolean picking;

    StickerController(Activity activity, ExecutorService queue, Listener listener) {
        this.activity = activity; this.queue = queue; this.listener = listener;
        store = new StickerStore(activity);
    }

    boolean isBusy() { return busy; }
    boolean isOpen() { return current != null || picking || busy; }

    void openLibrary() { if (!closed && !busy) showLibrary(0); }

    private void showLibrary(int requestedPage) {
        if (closed || busy) return;
        LinearLayout body = column();
        TextView note = text(R.string.sticker_library_note);
        body.addView(note);
        Button create = button(R.string.create_sticker);
        body.addView(create);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout grid = column();
        grid.setPadding(0, 0, 0, 0);
        scroll.addView(grid);
        int screenHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels /
                activity.getResources().getDisplayMetrics().density);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(Math.max(140, Math.min(315, screenHeight - 400)))));
        LinearLayout navigation = new LinearLayout(activity);
        navigation.setGravity(Gravity.CENTER);
        body.addView(navigation);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.stickers)
                .setView(body).setNegativeButton(R.string.close_stickers, null).create();
        show(dialog);
        create.setOnClickListener(v -> showCreateOptions());
        grid.addView(text(R.string.loading_stickers));
        queue.execute(() -> {
            List<StickerStore.Entry> entries = store.list();
            activity.runOnUiThread(() -> {
                if (!isCurrent(dialog)) return;
                grid.removeAllViews();
                if (entries.isEmpty()) { grid.addView(text(R.string.empty_sticker_library)); return; }
                int page = Math.min(requestedPage, (entries.size() - 1) / PAGE_SIZE);
                int end = Math.min(entries.size(), (page + 1) * PAGE_SIZE);
                LinearLayout row = null;
                for (int i = page * PAGE_SIZE; i < end; i++) {
                    if ((i % 3) == 0) {
                        row = new LinearLayout(activity);
                        row.setBaselineAligned(false);
                        grid.addView(row, new LinearLayout.LayoutParams(-1, dp(102)));
                    }
                    StickerStore.Entry entry = entries.get(i);
                    LinearLayout cell = new LinearLayout(activity);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER);
                    cell.setBackgroundResource(android.R.drawable.list_selector_background);
                    cell.setContentDescription(entry.name);
                    ImageView image = new ImageView(activity);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    cell.addView(image, new LinearLayout.LayoutParams(-1, dp(74)));
                    TextView label = new TextView(activity);
                    label.setText(entry.animated ? activity.getString(R.string.animated_sticker_label, entry.name) : entry.name);
                    label.setTextSize(12);
                    label.setMaxLines(1);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    label.setGravity(Gravity.CENTER);
                    cell.addView(label);
                    row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
                    cell.setOnClickListener(v -> loadEntry(entry));
                    queue.execute(() -> {
                        Bitmap thumbnail = BitmapFactory.decodeFile(entry.thumbnail().getAbsolutePath());
                        activity.runOnUiThread(() -> {
                            if (isCurrent(dialog)) image.setImageBitmap(thumbnail);
                            else if (thumbnail != null) thumbnail.recycle();
                        });
                    });
                }
                int remainder = (end - page * PAGE_SIZE) % 3;
                if (remainder != 0) for (int i = remainder; i < 3; i++) {
                    row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
                }
                Button previous = button(R.string.previous_stickers);
                Button next = button(R.string.next_stickers);
                previous.setEnabled(page > 0);
                next.setEnabled(end < entries.size());
                previous.setOnClickListener(v -> showLibrary(page - 1));
                next.setOnClickListener(v -> showLibrary(page + 1));
                navigation.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
                TextView position = new TextView(activity);
                position.setText(activity.getString(R.string.sticker_page, page + 1,
                        (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE));
                navigation.addView(position);
                navigation.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
            });
        });
    }

    private void showCreateOptions() {
        if (closed || busy) return;
        show(new AlertDialog.Builder(activity).setTitle(R.string.create_sticker)
                .setItems(new CharSequence[]{activity.getString(R.string.sticker_from_image),
                        activity.getString(R.string.sticker_from_video)},
                        (dialog, which) -> pickSource(which == 1))
                .setNegativeButton(R.string.close_stickers, null).create());
    }

    private void pickSource(boolean video) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(video ? "video/*" : "image/*");
        try {
            picking = true;
            activity.startActivityForResult(intent, video ? PICK_VIDEO : PICK_IMAGE);
        } catch (ActivityNotFoundException e) {
            picking = false;
            toast(R.string.sticker_picker_unavailable);
        }
        listener.stateChanged();
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_IMAGE && request != PICK_VIDEO) return false;
        picking = false;
        if (!closed && result == Activity.RESULT_OK && data != null && data.getData() != null) {
            create(data.getData(), request == PICK_VIDEO);
        } else if (!closed) openLibrary();
        listener.stateChanged();
        return true;
    }

    private void create(Uri uri, boolean video) {
        if (busy || closed) return;
        busy = true;
        final int operation = ++generation;
        LinearLayout body = column();
        TextView stage = text(R.string.creating_sticker);
        body.addView(stage);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(20)));
        body.addView(text(R.string.sticker_creation_limits));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.create_sticker)
                .setView(body).setCancelable(false)
                .setNegativeButton(R.string.cancel_sticker, (d, which) -> {
                    generation++;
                    busy = false;
                    listener.stateChanged();
                    showLibrary(0);
                }).create();
        show(dialog);
        queue.execute(() -> {
            try {
                StickerCodec.Progress callback = percent -> {
                    if (closed || generation != operation || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Cancelado");
                    }
                    activity.runOnUiThread(() -> {
                        if (isCurrent(dialog)) progress.setProgress(percent);
                    });
                };
                Sticker sticker = video ? StickerCodec.fromVideo(activity, uri, callback)
                        : StickerCodec.fromImage(activity, uri, callback);
                activity.runOnUiThread(() -> {
                    if (closed || generation != operation) return;
                    busy = false;
                    showPreview(sticker, null);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                int error = e instanceof StickerCodec.TooLongException ? R.string.sticker_too_long
                        : e instanceof StickerCodec.TooLargeException ? R.string.sticker_too_large
                        : R.string.invalid_sticker_source;
                activity.runOnUiThread(() -> {
                    if (closed || generation != operation) return;
                    busy = false;
                    toast(error);
                    showLibrary(0);
                });
            }
        });
    }

    private void loadEntry(StickerStore.Entry entry) {
        if (busy || closed) return;
        busy = true;
        listener.stateChanged();
        queue.execute(() -> {
            try {
                Sticker sticker = store.load(entry);
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    busy = false;
                    showPreview(sticker, entry);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    busy = false;
                    toast(R.string.sticker_load_failed);
                    showLibrary(0);
                });
            }
        });
    }

    void showPreview(Sticker sticker, StickerStore.Entry entry) {
        if (closed || busy) return;
        LinearLayout body = column();
        body.addView(new StickerView(activity, sticker), new LinearLayout.LayoutParams(-1, dp(220)));
        body.addView(text(sticker.isAnimated() ? R.string.animated_sticker : R.string.static_sticker));
        EditText name = new EditText(activity);
        if (entry == null) {
            name.setSingleLine(true);
            name.setHint(R.string.sticker_name);
            name.setText(R.string.default_sticker_name);
            name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
            body.addView(name);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(entry == null ? R.string.sticker_preview : R.string.sticker_saved_preview)
                .setView(body).setNegativeButton(R.string.close_stickers, null);
        if (entry == null) builder.setPositiveButton(R.string.save_sticker, null);
        else builder.setPositiveButton(R.string.send_sticker, null)
                .setNeutralButton(R.string.delete_sticker, null);
        AlertDialog dialog = builder.create();
        show(dialog);
        if (entry != null) {
            dialog.setTitle(entry.name);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (listener.send(sticker)) dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                show(new AlertDialog.Builder(activity).setTitle(R.string.delete_sticker)
                        .setMessage(R.string.delete_sticker_confirmation)
                        .setNegativeButton(R.string.cancel_sticker, (d, w) -> showPreview(sticker, entry))
                        .setPositiveButton(R.string.delete_sticker, (d, w) -> {
                            queue.execute(() -> {
                                store.delete(entry);
                                activity.runOnUiThread(() -> { if (!closed) showLibrary(0); });
                            });
                        }).create());
            });
        } else {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String label = name.getText().toString().trim();
                if (label.isEmpty()) { name.setError(activity.getString(R.string.sticker_name)); return; }
                busy = true;
                listener.stateChanged();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                queue.execute(() -> {
                    try {
                        store.save(label, sticker);
                        activity.runOnUiThread(() -> {
                            if (closed) return;
                            busy = false;
                            toast(R.string.sticker_saved);
                            showLibrary(0);
                        });
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        activity.runOnUiThread(() -> {
                            if (closed) return;
                            busy = false;
                            toast(R.string.sticker_save_failed);
                            if (isCurrent(dialog)) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            listener.stateChanged();
                        });
                    }
                });
            });
        }
    }

    private void show(AlertDialog dialog) {
        if (current != null) current.dismiss();
        current = dialog;
        dialog.setOnDismissListener(d -> {
            if (current == dialog) current = null;
            if (!closed) listener.stateChanged();
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        listener.stateChanged();
    }

    private boolean isCurrent(Dialog dialog) { return !closed && current == dialog && dialog.isShowing(); }

    void close() {
        closed = true;
        generation++;
        if (current != null) current.dismiss();
        current = null;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(8), dp(16), dp(8));
        return column;
    }

    private TextView text(int resource) {
        TextView text = new TextView(activity);
        text.setText(resource);
        text.setTextColor(activity.getColor(R.color.text_secondary));
        text.setPadding(0, dp(4), 0, dp(8));
        return text;
    }

    private Button button(int resource) {
        Button button = new Button(activity);
        button.setText(resource);
        button.setTextSize(14);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void toast(int resource) { Toast.makeText(activity, resource, Toast.LENGTH_LONG).show(); }
}
