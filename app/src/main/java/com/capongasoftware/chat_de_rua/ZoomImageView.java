package com.capongasoftware.chat_de_rua;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** ImageView com zoom por pinca e arraste, sem dependencias externas. */
public class ZoomImageView extends ImageView {

    private static final float MAX_RELATIVE_SCALE = 6f;

    private final Matrix zoomMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private float relativeScale = 1f;
    private float lastX;
    private float lastY;
    private boolean dragging;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float requested = detector.getScaleFactor();
                        float next = relativeScale * requested;
                        if (next < 1f) requested = 1f / relativeScale;
                        if (next > MAX_RELATIVE_SCALE) {
                            requested = MAX_RELATIVE_SCALE / relativeScale;
                        }
                        relativeScale *= requested;
                        zoomMatrix.postScale(requested, requested,
                                detector.getFocusX(), detector.getFocusY());
                        constrainTranslation();
                        setImageMatrix(zoomMatrix);
                        return true;
                    }
                });
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetImage();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        post(this::resetImage);
    }

    private void resetImage() {
        Drawable drawable = getDrawable();
        int viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0 ||
                drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            return;
        }
        float fitScale = Math.min(
                viewWidth / (float) drawable.getIntrinsicWidth(),
                viewHeight / (float) drawable.getIntrinsicHeight());
        float dx = getPaddingLeft() +
                (viewWidth - drawable.getIntrinsicWidth() * fitScale) / 2f;
        float dy = getPaddingTop() +
                (viewHeight - drawable.getIntrinsicHeight() * fitScale) / 2f;
        zoomMatrix.reset();
        zoomMatrix.postScale(fitScale, fitScale);
        zoomMatrix.postTranslate(dx, dy);
        relativeScale = 1f;
        setImageMatrix(zoomMatrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress() && relativeScale > 1f) {
                    float x = event.getX();
                    float y = event.getY();
                    zoomMatrix.postTranslate(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                    constrainTranslation();
                    setImageMatrix(zoomMatrix);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() - 1 == 1) {
                    int remainingIndex = event.getActionIndex() == 0 ? 1 : 0;
                    lastX = event.getX(remainingIndex);
                    lastY = event.getY(remainingIndex);
                    dragging = true;
                }
                break;
            case MotionEvent.ACTION_UP:
                performClick();
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void constrainTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        zoomMatrix.getValues(matrixValues);
        float scaleX = matrixValues[Matrix.MSCALE_X];
        float scaleY = matrixValues[Matrix.MSCALE_Y];
        float translatedX = matrixValues[Matrix.MTRANS_X];
        float translatedY = matrixValues[Matrix.MTRANS_Y];
        float imageWidth = drawable.getIntrinsicWidth() * Math.abs(scaleX);
        float imageHeight = drawable.getIntrinsicHeight() * Math.abs(scaleY);
        float viewWidth = getWidth();
        float viewHeight = getHeight();

        float correctionX;
        if (imageWidth <= viewWidth) {
            correctionX = (viewWidth - imageWidth) / 2f - translatedX;
        } else if (translatedX > 0f) {
            correctionX = -translatedX;
        } else if (translatedX + imageWidth < viewWidth) {
            correctionX = viewWidth - translatedX - imageWidth;
        } else {
            correctionX = 0f;
        }

        float correctionY;
        if (imageHeight <= viewHeight) {
            correctionY = (viewHeight - imageHeight) / 2f - translatedY;
        } else if (translatedY > 0f) {
            correctionY = -translatedY;
        } else if (translatedY + imageHeight < viewHeight) {
            correctionY = viewHeight - translatedY - imageHeight;
        } else {
            correctionY = 0f;
        }
        zoomMatrix.postTranslate(correctionX, correctionY);
    }
}
