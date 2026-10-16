package com.eurobuddha.maxima.app.i18n;

/** Original-string offsets for highlighting, shared from CloudSearchActivity's existing matcher. */
public final class SearchText {
    private SearchText() {}
    public static int indexOf(String text, String query) {
        // Lowercasing can change length (İ → i + combining dot), invalidating substring offsets.
        for (int i = 0; i + query.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, query, 0, query.length())) return i;
        }
        return -1;
    }
}
