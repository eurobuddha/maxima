package com.eurobuddha.maxima.core.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordered photo snapshot of ONE conversation, supplied by its existing message store. */
public final class ChatImages {
    public static final class Photo {
        public final String id, body;
        public final long time;
        public Photo(String id, String body, long time) {
            this.id = id; this.body = body; this.time = time;
        }
    }
    private final List<Photo> photos = new ArrayList<>();
    private int index;

    public ChatImages(List<Photo> messages, String selectedId) {
        Set<String> seen = new HashSet<>();
        for (Photo p : messages) {
            if (p.id != null && !p.id.isEmpty() && ChatMedia.mime(p.body).startsWith("image/")
                    && !ChatMedia.ref(p.body).isEmpty() && seen.add(p.id)) photos.add(p);
        }
        photos.sort(Comparator.comparingLong(p -> p.time));
        index = -1;
        for (int i = 0; i < photos.size(); i++) if (photos.get(i).id.equals(selectedId)) index = i;
    }
    public ChatImages withOlder(List<Photo> older) {
        List<Photo> combined = new ArrayList<>(photos);
        combined.addAll(older);
        return new ChatImages(combined, current() == null ? null : current().id);
    }
    public int size() { return photos.size(); }
    public int index() { return index; }
    public Photo current() { return index < 0 ? null : photos.get(index); }
    public boolean move(int delta) {
        int next = index + delta;
        if (index < 0 || next < 0 || next >= photos.size() || next == index) return false;
        index = next;
        return true;
    }
}
