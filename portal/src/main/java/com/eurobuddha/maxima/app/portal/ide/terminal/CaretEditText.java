package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatEditText;

/**
 * EditText that reports caret movement, so the completion dropdown tracks the
 * cursor even when the user moves it without typing (tap, long-press drag).
 */
public class CaretEditText extends AppCompatEditText {

    public interface OnCaretMoved {
        void onCaretMoved();
    }

    private OnCaretMoved mListener;

    public CaretEditText(Context context) {
        super(context);
    }

    public CaretEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CaretEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnCaretMoved(OnCaretMoved listener) {
        mListener = listener;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (mListener != null) mListener.onCaretMoved();
    }
}
