package com.eurobuddha.maxima.app.chat;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoSwipeTest {
    @Test public void horizontalSwipesFollowChronologicalDirection() {
        assertEquals(1, PhotoSwipe.direction(-180, 20, 2, true));
        assertEquals(-1, PhotoSwipe.direction(180, -20, 2, true));
    }
    @Test public void pinchPanTapAndVerticalMovementDoNotPage() {
        assertEquals(0, PhotoSwipe.direction(-400, 0, 2, false));
        assertEquals(0, PhotoSwipe.direction(-50, 0, 2, true));
        assertEquals(0, PhotoSwipe.direction(-180, 200, 2, true));
        assertEquals(0, PhotoSwipe.direction(-180, 140, 2, true));
        assertEquals(0, PhotoSwipe.direction(-112, 0, 2, true));
    }
}
