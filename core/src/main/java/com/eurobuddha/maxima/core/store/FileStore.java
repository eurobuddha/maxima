package com.eurobuddha.maxima.core.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link Store}. No dependencies, no database.
 *
 * One file per collection, one record per line, tab-separated key and value.
 * The value is escaped so a newline or tab in a payload cannot corrupt the
 * file - the obvious bug in every hand-rolled line format.
 *
 * Writes are ATOMIC: a full rewrite goes to a temp file and is renamed over the
 * original. A relay killed mid-write must not come back with a half-written
 * mailbox, and rename is the only cheap way to guarantee that.
 *
 * Keyed collections are cached in memory and rewritten on change, which is fine
 * at this scale (thousands of small records) and keeps reads free. Append logs
 * are appended to directly.
 */
public final class FileStore implements Store {

    private final File mDir;
    private final Map<String, Map<String, String>> mCache = new ConcurrentHashMap<>();

    public FileStore(File zDir) {
        mDir = zDir;
        if (!mDir.exists() && !mDir.mkdirs()) {
            throw new IllegalStateException("Cannot create data directory: " + mDir);
        }
    }

    // ---------------------------------------------------------------
    // keyed records
    // ---------------------------------------------------------------

    @Override
    public synchronized void put(String zCollection, String zKey, String zValue) {
        load(zCollection).put(zKey, zValue);
        persist(zCollection);
    }

    @Override
    public synchronized String get(String zCollection, String zKey) {
        return load(zCollection).get(zKey);
    }

    @Override
    public synchronized void remove(String zCollection, String zKey) {
        if (load(zCollection).remove(zKey) != null) {
            persist(zCollection);
        }
    }

    @Override
    public synchronized Map<String, String> all(String zCollection) {
        return new LinkedHashMap<>(load(zCollection));
    }

    private Map<String, String> load(String zCollection) {
        return mCache.computeIfAbsent(zCollection, c -> {
            Map<String, String> m = new LinkedHashMap<>();
            File f = file(c + ".tsv");
            if (!f.exists()) {
                return m;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t > 0) {
                        m.put(unescape(line.substring(0, t)), unescape(line.substring(t + 1)));
                    }
                }
            } catch (IOException e) {
                // A corrupt collection must not take the process down. Losing
                // one file is recoverable; refusing to start is not.
                System.err.println("[store] could not read " + f + ": " + e);
            }
            return m;
        });
    }

    private void persist(String zCollection) {
        Map<String, String> m = mCache.get(zCollection);
        if (m == null) {
            return;
        }
        List<String> lines = new ArrayList<>(m.size());
        for (Map.Entry<String, String> e : m.entrySet()) {
            lines.add(escape(e.getKey()) + "\t" + escape(e.getValue()));
        }
        writeAtomic(file(zCollection + ".tsv"), lines);
    }

    // ---------------------------------------------------------------
    // append logs
    // ---------------------------------------------------------------

    @Override
    public synchronized void append(String zLog, String zLine) {
        File f = file(zLog + ".log");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
            w.write(escape(zLine));
            w.newLine();
        } catch (IOException e) {
            System.err.println("[store] append failed on " + f + ": " + e);
        }
    }

    @Override
    public synchronized List<String> read(String zLog) {
        List<String> out = new ArrayList<>();
        File f = file(zLog + ".log");
        if (!f.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    out.add(unescape(line));
                }
            }
        } catch (IOException e) {
            System.err.println("[store] read failed on " + f + ": " + e);
        }
        return out;
    }

    @Override
    public synchronized void rewrite(String zLog, List<String> zLines) {
        List<String> esc = new ArrayList<>(zLines.size());
        for (String l : zLines) {
            esc.add(escape(l));
        }
        writeAtomic(file(zLog + ".log"), esc);
    }

    @Override
    public void flush() {
        // Every write already lands on disk before returning.
    }

    // ---------------------------------------------------------------

    private File file(String zName) {
        // Never let a collection name escape the data directory.
        return new File(mDir, zName.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** temp + rename, so an interrupted write cannot leave a half file. */
    private void writeAtomic(File zTarget, List<String> zLines) {
        File tmp = new File(zTarget.getParentFile(), zTarget.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            for (String l : zLines) {
                w.write(l);
                w.newLine();
            }
            w.flush();
            // force the bytes to disk BEFORE the rename. Rename gives atomicity
            // of visibility, not durability: on some filesystems a crash right
            // after rename can expose the new name with unflushed (empty)
            // contents - the exact data-loss this class exists to prevent.
            fos.getFD().sync();
        } catch (IOException e) {
            System.err.println("[store] write failed on " + tmp + ": " + e);
            return;
        }
        if (!tmp.renameTo(zTarget)) {
            // Windows and some Android filesystems refuse rename-over.
            if (!zTarget.delete() || !tmp.renameTo(zTarget)) {
                System.err.println("[store] could not replace " + zTarget);
            }
        }
    }

    /** Keeps tabs and newlines out of the line format. */
    static String escape(String zValue) {
        return zValue.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    static String unescape(String zValue) {
        StringBuilder sb = new StringBuilder(zValue.length());
        for (int i = 0; i < zValue.length(); i++) {
            char c = zValue.charAt(i);
            if (c == '\\' && i + 1 < zValue.length()) {
                char n = zValue.charAt(++i);
                switch (n) {
                    case 't': sb.append('\t'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
