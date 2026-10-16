package com.eurobuddha.maxima.app.ui;

import android.view.View;

/** One tab. Built once, rendered whenever it is the visible one. */
public interface Page {

    View view();

    CharSequence title();

    void render();

    /** Update locale-dependent display data without rebuilding forms. */
    default void languageChanged() {}
}
