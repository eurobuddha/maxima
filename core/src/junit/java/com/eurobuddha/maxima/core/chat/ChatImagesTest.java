package com.eurobuddha.maxima.core.chat;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChatImagesTest {
    private ChatImages.Photo photo(String id, long time) {
        return new ChatImages.Photo(id, ChatMedia.wrap("image/jpeg", "mx1:" + id, "caption"), time);
    }
    @Test public void selectsTappedImageAndTraversesOnlyPhotosInTimeOrder() {
        ChatImages gallery = new ChatImages(Arrays.asList(photo("last", 30),
                new ChatImages.Photo("text", "hello", 12), photo("first", 10),
                new ChatImages.Photo("audio", ChatMedia.wrap("audio/ogg", "mx1:audio", ""), 15),
                photo("middle", 20), photo("middle", 20),
                new ChatImages.Photo("empty", ChatMedia.wrap("image/png", "", ""), 40)), "middle");
        assertEquals(3, gallery.size()); assertEquals(1, gallery.index());
        assertTrue(gallery.move(1)); assertEquals("last", gallery.current().id);
        assertFalse(gallery.move(1)); assertEquals("last", gallery.current().id);
        assertTrue(gallery.move(-1)); assertTrue(gallery.move(-1));
        assertEquals("first", gallery.current().id); assertFalse(gallery.move(-1));
    }
    @Test public void missingOrDeletedSelectionNeverOpensAnotherPhoto() {
        ChatImages gallery = new ChatImages(Collections.singletonList(photo("other", 10)), "deleted");
        assertNull(gallery.current()); assertFalse(gallery.move(1));
        assertNull(new ChatImages(Collections.emptyList(), "missing").current());
    }
    @Test public void tiedTimesPreserveOrderAndSnapshotSurvivesRefresh() {
        List<ChatImages.Photo> source = new ArrayList<>(Arrays.asList(photo("a", 1), photo("b", 1)));
        ChatImages gallery = new ChatImages(source, "a"); source.clear();
        assertTrue(gallery.move(1)); assertEquals("b", gallery.current().id);
    }
    @Test public void addingEarlierHistoryKeepsTheSelectedPhotoAndSkipsTextOnlyPages() {
        ChatImages gallery = new ChatImages(Collections.singletonList(photo("current", 20)), "current");
        gallery = gallery.withOlder(Arrays.asList(new ChatImages.Photo("text", "hello", 1), photo("old", 10)));
        assertEquals("current", gallery.current().id); assertEquals(1, gallery.index());
        assertTrue(gallery.move(-1)); assertEquals("old", gallery.current().id);
    }
}
