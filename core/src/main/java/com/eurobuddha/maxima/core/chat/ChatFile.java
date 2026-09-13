package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** A private file offer, carried ONLY inside the existing end-to-end sealed chat body. */
public final class ChatFile {
    public static final String PREFIX = "pf1:";
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    public static final int MAX_REF_CHARS = 65536;
    public final String id, name, mime, key, nonce, digest, torrent;
    public final long size;

    public ChatFile(String id, String name, String mime, long size, String key,
                    String nonce, String digest, String torrent) {
        if (id == null || !id.matches("[a-f0-9]{32}") || size < 0 || size > MAX_BYTES
                || name == null || name.isEmpty() || name.length() > 240
                || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\')
                || mime == null || mime.length() > 160 || mime.chars().anyMatch(c -> c < 32 || c == 127)
                || key == null || !key.matches("[a-f0-9]{64}")
                || nonce == null || !nonce.matches("[a-f0-9]{16}")
                || digest == null || !digest.matches("[a-f0-9]{64}")
                || torrent == null || torrent.length() > 32768) {
            throw new IllegalArgumentException("Invalid private file offer");
        }
        this.id = id; this.name = name; this.mime = mime; this.size = size;
        this.key = key; this.nonce = nonce; this.digest = digest; this.torrent = torrent;
    }

    public String ref() {
        String json = new Json.Writer().put("v", "1").put("id", id).put("name", name)
                .put("mime", mime).put("size", Long.toString(size)).put("key", key)
                .put("nonce", nonce).put("digest", digest).put("torrent", torrent).done();
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public String body() { return ChatMedia.wrap(mime, ref(), name); }
    public static boolean isFile(String body) { return ChatMedia.ref(body).startsWith(PREFIX); }
    public static ChatFile fromBody(String body) { return parse(ChatMedia.ref(body)); }

    public static ChatFile parse(String ref) {
        if (ref == null || !ref.startsWith(PREFIX) || ref.length() > MAX_REF_CHARS)
            throw new IllegalArgumentException("Unsupported file offer; update Parlons");
        try {
            Map<String, String> m = Json.parse(new String(Base64.getUrlDecoder()
                    .decode(ref.substring(PREFIX.length())), StandardCharsets.UTF_8));
            if (!"1".equals(m.get("v"))) throw new IllegalArgumentException();
            return new ChatFile(m.get("id"), m.get("name"), m.get("mime"), Long.parseLong(m.get("size")),
                    m.get("key"), m.get("nonce"), m.get("digest"), m.get("torrent"));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid private file offer"); }
    }
}
