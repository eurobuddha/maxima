package com.eurobuddha.maxima.app.chat;

/** Salon's horizontal distance gate, also accepting a slow swipe; zoom owns panning. */
final class PhotoSwipe {
    private PhotoSwipe() {}
    static int direction(float dx, float dy, float density, boolean atFit) {
        if (!atFit || Math.abs(dx) <= 56 * density || Math.abs(dx) <= Math.abs(dy) * 1.5f) return 0;
        return dx < 0 ? 1 : -1;
    }
}
