package com.capongasoftware.chat_de_rua;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps only one decoded frame per visible sticker, and decodes away from the UI thread. */
@android.annotation.SuppressLint("ViewConstructor") // Created with validated frames, never inflated from XML.
final class StickerView extends View {
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect visibleArea = new Rect();
    private final RectF destination = new RectF();
    private final Sticker sticker;
    private Bitmap bitmap;
    private int frame;
    private int generation;
    private boolean decoding;
    private final Runnable tick = this::advance;

    StickerView(Context context, Sticker sticker) {
        super(context);
        this.sticker = sticker;
        setContentDescription(LocalizedText.text(context, sticker.isAnimated()
                ? R.string.animated_sticker : R.string.static_sticker));
        setMinimumHeight((int) (96 * getResources().getDisplayMetrics().density));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        advance();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) advance(); else stop();
    }

    private void stop() {
        generation++;
        decoding = false;
        handler.removeCallbacks(tick);
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
    }

    private void advance() {
        handler.removeCallbacks(tick);
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || decoding) return;
        if (!isShown() || !getGlobalVisibleRect(visibleArea)) {
            handler.postDelayed(tick, 300);
            return;
        }
        if (bitmap != null && !sticker.isAnimated()) return;
        decoding = true;
        final int expected = generation;
        final int index = frame;
        DECODER.execute(() -> {
            Bitmap decoded;
            try { decoded = sticker.decodeFrame(index); }
            catch (RuntimeException | OutOfMemoryError e) { decoded = null; }
            final Bitmap ready = decoded;
            handler.post(() -> {
                if (expected != generation || !isAttachedToWindow()) {
                    if (ready != null) ready.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) return;
                if (bitmap != null) bitmap.recycle();
                bitmap = ready;
                invalidate();
                frame = (index + 1) % sticker.frameCount();
                if (sticker.isAnimated()) handler.postDelayed(tick, sticker.duration(index));
            });
        });
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        destination.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                (getWidth() + width) / 2, (getHeight() + height) / 2);
        canvas.drawBitmap(bitmap, null, destination, paint);
    }
}
