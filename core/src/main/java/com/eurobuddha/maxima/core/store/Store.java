package com.eurobuddha.maxima.core.store;

import java.util.List;
import java.util.Map;

/**
 * Durable storage, kept deliberately tiny.
 *
 * Everything above this was in-memory until now, which meant a relay lost every
 * held message when systemd restarted it and a phone forgot its contacts on
 * reboot. Neither is acceptable, and neither is worth a database dependency:
 * the entire working set is a few thousand small records.
 *
 * Two shapes cover all of it:
 *   - a KEYED map, for contacts, directory entries and settings
 *   - an APPEND log, for mailbox items and outbox entries
 *
 * The host supplies the implementation, so :core stays free of file APIs and
 * Android can put it wherever it likes.
 */
public interface Store {

    // ---- keyed records ----

    /** Overwrite (or create) a record. */
    void put(String zCollection, String zKey, String zValue);

    /** Null if absent. */
    String get(String zCollection, String zKey);

    void remove(String zCollection, String zKey);

    /** Every record in a collection, key -> value. Empty map if none. */
    Map<String, String> all(String zCollection);

    // ---- append log ----

    void append(String zLog, String zLine);

    List<String> read(String zLog);

    /** Replace the whole log - used after pruning. */
    void rewrite(String zLog, List<String> zLines);

    /** Flush anything buffered. Called before shutdown. */
    void flush();

    /**
     * A no-op store, so :core and its tests run with no filesystem at all and
     * a host can opt out of persistence deliberately rather than by accident.
     */
    Store MEMORY_ONLY = new Store() {
        private final Map<String, Map<String, String>> mMaps = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, List<String>> mLogs = new java.util.concurrent.ConcurrentHashMap<>();

        public void put(String c, String k, String v) {
            mMaps.computeIfAbsent(c, x -> new java.util.concurrent.ConcurrentHashMap<>()).put(k, v);
        }

        public String get(String c, String k) {
            Map<String, String> m = mMaps.get(c);
            return m == null ? null : m.get(k);
        }

        public void remove(String c, String k) {
            Map<String, String> m = mMaps.get(c);
            if (m != null) {
                m.remove(k);
            }
        }

        public Map<String, String> all(String c) {
            Map<String, String> m = mMaps.get(c);
            return m == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(m);
        }

        public void append(String l, String line) {
            mLogs.computeIfAbsent(l, x -> java.util.Collections.synchronizedList(
                    new java.util.ArrayList<>())).add(line);
        }

        public List<String> read(String l) {
            List<String> v = mLogs.get(l);
            return v == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
        }

        public void rewrite(String l, List<String> lines) {
            mLogs.put(l, java.util.Collections.synchronizedList(new java.util.ArrayList<>(lines)));
        }

        public void flush() {
        }
    };
}
