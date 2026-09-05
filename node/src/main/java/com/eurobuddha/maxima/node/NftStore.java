package com.eurobuddha.maxima.node;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * NFT / token-art hosting on the node: the files an on-chain token points at, served by the
 * node's own public TLS front so a marketplace or explorer can link to them directly.
 *
 * <ul>
 *   <li>Single files are CONTENT-ADDRESSED: {@code <sha256>.<ext>} — the URL never changes and
 *       anyone can verify the bytes against the hash the token metadata carries.</li>
 *   <li>State-NFT collections need {@code base + index + ext}: they live under
 *       {@code c/<16-hex id>/<index>.<ext>} with a {@code manifest.json} recording each item's
 *       sha256 so the folder is verifiable too.</li>
 * </ul>
 * Uploads arrive over the paired channel in chunks (offset-idempotent, like media uploads) into
 * {@code tmp/<uid>.part}; the last chunk is verified against the announced sha256 before the
 * file is moved into place. Reads are public GETs on the gateway ({@code /nft/...}), strictly
 * pattern-matched so nothing outside this folder can ever be addressed.
 */
final class NftStore {

    static final Pattern SINGLE = Pattern.compile("[0-9a-f]{64}\\.[a-z0-9]{2,5}");
    static final Pattern COLLECTION_FILE = Pattern.compile("c/[0-9a-f]{16}/(?:[0-9]{1,4}\\.[a-z0-9]{2,5}|manifest\\.json)");
    private static final Pattern EXT = Pattern.compile("[a-z0-9]{2,5}");
    /** The most a single hosted file may be (the token metadata links to it; a marketplace loads it). */
    static final long MAX_FILE = 32L * 1024 * 1024;

    private final Path mRoot;
    private final Path mTmp;
    private final String mPublicBase;   // "" when the operator has not set -Dparlons.node.public

    NftStore(File zDataFolder, String zPublicBase) throws IOException {
        mRoot = zDataFolder.toPath().resolve("nft");
        mTmp = mRoot.resolve("tmp");
        Files.createDirectories(mTmp);
        Files.createDirectories(mRoot.resolve("c"));
        String b = zPublicBase == null ? "" : zPublicBase.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        mPublicBase = b;
    }

    /** e.g. https://store.eurobuddha.com/parlons-node — "" if unset. */
    String publicBase() { return mPublicBase; }

    /** The public URL of a stored relative path ("" when no public base is configured). */
    String urlOf(String zRel) {
        return mPublicBase.isEmpty() ? "" : mPublicBase + "/nft/" + zRel;
    }

    /** A fresh collection folder id; its base URL ends with "/" so base + index + ext works. */
    JSONObject newCollection() throws IOException {
        byte[] r = new byte[8];
        new SecureRandom().nextBytes(r);
        StringBuilder id = new StringBuilder();
        for (byte b : r) id.append(String.format("%02x", b));
        Files.createDirectories(mRoot.resolve("c").resolve(id.toString()));
        JSONObject out = new JSONObject();
        out.put("collection", id.toString());
        out.put("base", mPublicBase.isEmpty() ? "" : mPublicBase + "/nft/c/" + id + "/");
        return out;
    }

    /**
     * One upload chunk. First chunk (off 0) opens the part file; the announced {@code size} and
     * {@code sha256} are checked when the last byte lands. Offset-idempotent: a retried chunk
     * (off &lt; current length) is acknowledged, a gap is refused.
     *
     * @return {@code {done:false, received:n}} or, when complete,
     *         {@code {done:true, path, url, sha256, size}}
     */
    synchronized JSONObject put(String zUid, String zExt, long zSize, String zSha, long zOff,
                                byte[] zChunk, String zCollection, int zIndex) throws Exception {
        if (zUid == null || !zUid.matches("[A-Za-z0-9_-]{6,64}")) throw new IllegalArgumentException("bad upload id");
        String ext = zExt == null ? "" : zExt.toLowerCase(Locale.ROOT).replace(".", "");
        if (!EXT.matcher(ext).matches()) throw new IllegalArgumentException("bad extension");
        if (zSize <= 0 || zSize > MAX_FILE) throw new IllegalArgumentException("size must be 1.." + MAX_FILE + " bytes");
        if (zSha == null || !zSha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 required (64 hex, lowercase)");
        if (zCollection != null && !zCollection.isEmpty()) {
            if (!zCollection.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("bad collection id");
            if (zIndex < 0 || zIndex > 9999) throw new IllegalArgumentException("index must be 0..9999");
            if (!Files.isDirectory(mRoot.resolve("c").resolve(zCollection))) throw new IllegalArgumentException("unknown collection");
        }
        Path part = mTmp.resolve(zUid + ".part");
        long have = Files.exists(part) ? Files.size(part) : 0;
        if (zOff > have) throw new IllegalArgumentException("gap: have " + have + " bytes, chunk at " + zOff);
        if (zOff < have) {
            JSONObject ack = new JSONObject();
            ack.put("done", false);
            ack.put("received", have);
            return ack;                                   // duplicate of a chunk we already have
        }
        if (have + zChunk.length > zSize) throw new IllegalArgumentException("more bytes than announced");
        try (RandomAccessFile raf = new RandomAccessFile(part.toFile(), "rw")) {
            raf.seek(have);
            raf.write(zChunk);
        }
        long now = have + zChunk.length;
        if (now < zSize) {
            JSONObject more = new JSONObject();
            more.put("done", false);
            more.put("received", now);
            return more;
        }
        // complete: verify, then place
        String actual = sha256Hex(part);
        if (!actual.equals(zSha)) {
            Files.deleteIfExists(part);
            throw new IllegalArgumentException("sha256 mismatch: the upload was corrupted, try again");
        }
        String rel;
        if (zCollection != null && !zCollection.isEmpty()) {
            rel = "c/" + zCollection + "/" + zIndex + "." + ext;
            Path target = mRoot.resolve(rel);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            noteManifest(zCollection, zIndex + "." + ext, actual, zSize);
        } else {
            rel = actual + "." + ext;
            Path target = mRoot.resolve(rel);
            if (Files.exists(target)) {
                Files.deleteIfExists(part);               // identical content already hosted
            } else {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        JSONObject done = new JSONObject();
        done.put("done", true);
        done.put("path", rel);
        done.put("url", urlOf(rel));
        done.put("sha256", actual);
        done.put("size", zSize);
        return done;
    }

    /** Every hosted file (never the tmp parts), with its size and public URL. */
    JSONObject list() throws IOException {
        JSONArray files = new JSONArray();
        try (java.util.stream.Stream<Path> s = Files.walk(mRoot)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String rel = mRoot.relativize(p).toString().replace(File.separatorChar, '/');
                if (rel.startsWith("tmp/")) return;
                if (!SINGLE.matcher(rel).matches() && !COLLECTION_FILE.matcher(rel).matches()) return;
                JSONObject f = new JSONObject();
                f.put("path", rel);
                f.put("url", urlOf(rel));
                try { f.put("size", Files.size(p)); } catch (IOException e) { f.put("size", -1); }
                files.add(f);
            });
        }
        JSONObject out = new JSONObject();
        out.put("base", mPublicBase);
        out.put("files", files);
        return out;
    }

    /** Delete one hosted file (a collection's manifest entry is left; it records history). */
    boolean delete(String zRel) throws IOException {
        Path p = resolve(zRel);
        if (p == null) return false;
        return Files.deleteIfExists(p);
    }

    /** A hosted file for a gateway path, or null when the path is not a hosted file. */
    Path resolve(String zRel) {
        if (zRel == null) return null;
        if (!SINGLE.matcher(zRel).matches() && !COLLECTION_FILE.matcher(zRel).matches()) return null;
        Path p = mRoot.resolve(zRel).normalize();
        if (!p.startsWith(mRoot)) return null;
        return p;
    }

    static String contentType(String zRel) {
        String ext = zRel.substring(zRel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "png":  return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "svg":  return "image/svg+xml";
            case "avif": return "image/avif";
            case "mp4":  return "video/mp4";
            case "webm": return "video/webm";
            case "mp3":  return "audio/mpeg";
            case "json": return "application/json";
            case "txt":  return "text/plain; charset=utf-8";
            case "glb":  return "model/gltf-binary";
            default:     return "application/octet-stream";
        }
    }

    private void noteManifest(String zCollection, String zFile, String zSha, long zSize) throws IOException {
        Path m = mRoot.resolve("c").resolve(zCollection).resolve("manifest.json");
        JSONObject manifest = new JSONObject();
        if (Files.exists(m)) {
            try {
                Object o = new org.minima.utils.json.parser.JSONParser().parse(
                        new String(Files.readAllBytes(m), StandardCharsets.UTF_8));
                if (o instanceof JSONObject) manifest = (JSONObject) o;
            } catch (Exception ignored) { }
        }
        JSONObject items = manifest.get("items") instanceof JSONObject ? (JSONObject) manifest.get("items") : new JSONObject();
        JSONObject item = new JSONObject();
        item.put("sha256", zSha);
        item.put("size", zSize);
        items.put(zFile, item);
        manifest.put("collection", zCollection);
        manifest.put("items", items);
        Files.write(m, manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(Path zFile) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(zFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
