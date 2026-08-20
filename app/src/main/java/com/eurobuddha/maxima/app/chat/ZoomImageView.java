package com.eurobuddha.maxima.app.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * The full-screen photo view: pinch to zoom (up to 5x), pan when zoomed,
 * double-tap toggles fit/2.5x, single tap reports up (the viewer toggles its
 * chrome). Pure Matrix math on a stock ImageView - no libraries, matching the
 * app's no-deps drawn-UI language.
 */
public final class ZoomImageView extends android.widget.ImageView {

    private final Matrix mM = new Matrix();
    private final float[] mV = new float[9];
    private float mFit = 1f;
    private ScaleGestureDetector mScale;
    private GestureDetector mGest;
    private Runnable mSingleTap;

    public ZoomImageView(Context zCtx) {
        super(zCtx);
        setScaleType(ScaleType.MATRIX);
        mScale = new ScaleGestureDetector(zCtx,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float cur = scale();
                float target = Math.max(mFit, Math.min(mFit * 5f,
                        cur * d.getScaleFactor()));
                float f = target / cur;
                mM.postScale(f, f, d.getFocusX(), d.getFocusY());
                clamp();
                setImageMatrix(mM);
                return true;
            }
        });
        mGest = new GestureDetector(zCtx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                mM.postTranslate(-dx, -dy);
                clamp();
                setImageMatrix(mM);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float cur = scale();
                if (cur > mFit * 1.01f) {
                    fit();
                } else {
                    float f = (mFit * 2.5f) / cur;
                    mM.postScale(f, f, e.getX(), e.getY());
                    clamp();
                    setImageMatrix(mM);
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mSingleTap != null) {
                    mSingleTap.run();
                }
                return true;
            }
        });
    }

    public void setOnSingleTap(Runnable zRun) {
        mSingleTap = zRun;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    @Override
    public void setImageBitmap(Bitmap zBmp) {
        super.setImageBitmap(zBmp);
        fit();
    }

    private float scale() {
        mM.getValues(mV);
        return mV[Matrix.MSCALE_X];
    }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            return;
        }
        mFit = Math.min(getWidth() / iw, getHeight() / ih);
        mM.reset();
        mM.postScale(mFit, mFit);
        mM.postTranslate((getWidth() - iw * mFit) / 2f,
                (getHeight() - ih * mFit) / 2f);
        setImageMatrix(mM);
    }

    /** Keep the image on-screen: centred when smaller than the view on an
     *  axis, edge-bounded when larger - so panning can never lose it. */
    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        mM.getValues(mV);
        float s = mV[Matrix.MSCALE_X];
        float w = d.getIntrinsicWidth() * s, h = d.getIntrinsicHeight() * s;
        float tx = mV[Matrix.MTRANS_X], ty = mV[Matrix.MTRANS_Y];
        float nx = w <= getWidth() ? (getWidth() - w) / 2f
                : Math.min(0, Math.max(getWidth() - w, tx));
        float ny = h <= getHeight() ? (getHeight() - h) / 2f
                : Math.min(0, Math.max(getHeight() - h, ty));
        mM.postTranslate(nx - tx, ny - ty);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        mScale.onTouchEvent(e);
        mGest.onTouchEvent(e);
        return true;
    }
}
