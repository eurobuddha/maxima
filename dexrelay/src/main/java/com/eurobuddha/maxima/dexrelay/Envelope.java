package com.eurobuddha.maxima.dexrelay;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validated SPEC §3 envelope: an {@code order} (signed by {@code signPk}) or a {@code msg} (signed by the signPk
 * half of {@code from}). Cheap shape checks run first, the Ed25519 check last. {@link #raw} is the exact JSON
 * accepted, fanned out verbatim so every client verifies the same bytes the relay did.
 */
final class Envelope {
    enum Kind { ORDER, MSG }

    static final class Invalid extends IllegalArgumentException {
        Invalid(String why) { super(why); }
    }

    static final int MAX_ORDER_BYTES = 16 * 1024;
    static final int MAX_MSG_BYTES = 256 * 1024;
    static final long MAX_AHEAD_ORDER = 24 * 3600;
    static final long MAX_AHEAD_MSG = 600;
    static final int MAX_COINS = 32;

    private static final Set<String> ORDER_KEYS = new HashSet<>(Arrays.asList(
            "v", "t", "market", "side", "price", "amount", "coins", "scripts", "address", "orderId", "seq", "expiry", "pubId", "signPk", "sig"));
    private static final Set<String> MSG_KEYS = new HashSet<>(Arrays.asList("v", "t", "to", "from", "blob", "expiry", "sig"));
    private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,8})?$");
    private static final Pattern MARKET = Pattern.compile("^[A-Za-z0-9/_-]{1,32}$");
    /** v1 backs orders with simple-address coins only (the taker registers these with newscript; txnsign auto signs them). */
    private static final Pattern SIMPLE_SCRIPT = Pattern.compile("^RETURN SIGNEDBY\\(0x[0-9A-Fa-f]{64}\\)$");

    final Kind kind;
    final String raw;
    final long seq, expiry;
    // order
    final String market, side, price, amount, address, orderId, signPk, pubId;
    final List<String> coins;
    // msg
    final String to, from, blob;

    private Envelope(Kind kind, String raw, Map<String, Object> m) {
        this.kind = kind; this.raw = raw;
        this.expiry = (Long) m.get("expiry");
        if (kind == Kind.ORDER) {
            market = (String) m.get("market"); side = (String) m.get("side"); price = (String) m.get("price");
            amount = (String) m.get("amount"); address = (String) m.get("address"); orderId = (String) m.get("orderId");
            signPk = (String) m.get("signPk"); pubId = (String) m.get("pubId"); seq = (Long) m.get("seq");
            @SuppressWarnings("unchecked") List<String> c = (List<String>) (List<?>) m.get("coins");
            coins = Collections.unmodifiableList(c);
            to = null; from = null; blob = null;
        } else {
            to = (String) m.get("to"); from = (String) m.get("from"); blob = (String) m.get("blob");
            seq = 0; market = null; side = null; price = null; amount = null; address = null; orderId = null; signPk = null; pubId = null; coins = null;
        }
    }

    /** signPk|orderId, lowercased so case variants of the same key never make two orders. */
    String key() { return signPk.toLowerCase() + "|" + orderId.toLowerCase(); }

    boolean isCancel() { return kind == Kind.ORDER && isCancelAmount(amount); }

    static boolean isCancelAmount(String a) {
        for (int i = 0; i < a.length(); i++) { char c = a.charAt(i); if (c != '0' && c != '.') return false; }
        return true;
    }

    static Envelope parse(String input, long nowSeconds) {
        if (input == null) throw new Invalid("empty");
        String raw = input.trim();
        if (raw.length() > MAX_MSG_BYTES) throw new Invalid("too big");
        Map<String, Object> m;
        try { m = Json.parseObject(raw); } catch (IllegalArgumentException e) { throw new Invalid("bad json: " + e.getMessage()); }

        if (!Long.valueOf(1L).equals(m.get("v"))) throw new Invalid("v must be 1");
        Object t = m.get("t");
        Kind kind;
        if ("order".equals(t)) kind = Kind.ORDER;
        else if ("msg".equals(t)) kind = Kind.MSG;
        else throw new Invalid("t must be order or msg");

        Set<String> allowed = kind == Kind.ORDER ? ORDER_KEYS : MSG_KEYS;
        if (!m.keySet().equals(allowed)) throw new Invalid("keys must be exactly " + allowed);
        if (kind == Kind.ORDER && raw.getBytes(StandardCharsets.UTF_8).length > MAX_ORDER_BYTES) throw new Invalid("order too big");

        Object expiryO = m.get("expiry");
        if (!(expiryO instanceof Long)) throw new Invalid("expiry must be an integer");
        long expiry = (Long) expiryO;
        if (expiry <= nowSeconds) throw new Invalid("expired");
        if (expiry > nowSeconds + (kind == Kind.ORDER ? MAX_AHEAD_ORDER : MAX_AHEAD_MSG)) throw new Invalid("expiry too far ahead");

        String sig = str(m, "sig");
        if (!Hex.is0x(sig, 64)) throw new Invalid("sig must be 0x + 64 bytes");

        byte[] pk;
        if (kind == Kind.ORDER) {
            if (!MARKET.matcher(str(m, "market")).matches()) throw new Invalid("bad market");
            String side = str(m, "side");
            if (!"buy".equals(side) && !"sell".equals(side)) throw new Invalid("side must be buy or sell");
            String price = str(m, "price");
            if (!DECIMAL.matcher(price).matches() || isCancelAmount(price)) throw new Invalid("bad price");
            if (!DECIMAL.matcher(str(m, "amount")).matches()) throw new Invalid("bad amount");
            Object coinsO = m.get("coins");
            if (!(coinsO instanceof List)) throw new Invalid("coins must be an array");
            List<?> coins = (List<?>) coinsO;
            if (coins.size() > MAX_COINS) throw new Invalid("too many coins");
            for (Object c : coins) if (!(c instanceof String) || !Hex.is0x((String) c, 32)) throw new Invalid("bad coinid");
            Object scriptsO = m.get("scripts");
            if (!(scriptsO instanceof List) || ((List<?>) scriptsO).size() > MAX_COINS) throw new Invalid("bad scripts");
            for (Object sc : (List<?>) scriptsO) if (!(sc instanceof String) || !SIMPLE_SCRIPT.matcher((String) sc).matches()) throw new Invalid("bad script (simple RETURN SIGNEDBY only)");
            if (!Hex.is0x(str(m, "address"), 32)) throw new Invalid("bad address");
            if (!Hex.is0x(str(m, "orderId"), 8)) throw new Invalid("bad orderId");
            Object seqO = m.get("seq");
            if (!(seqO instanceof Long) || (Long) seqO < 0) throw new Invalid("seq must be a non-negative integer");
            String signPk = str(m, "signPk");
            if (!Hex.is0x(signPk, 32)) throw new Invalid("bad signPk");
            String pubId = str(m, "pubId");
            if (!Hex.is0x(pubId, 64)) throw new Invalid("bad pubId (publicId = 0x + 64 bytes)");
            if (!pubId.substring(2 + 64).equalsIgnoreCase(signPk.substring(2))) throw new Invalid("pubId must end with signPk");
            pk = Hex.decode(signPk.substring(2));
        } else {
            String to = str(m, "to"), from = str(m, "from"), blob = str(m, "blob");
            if (!Hex.is0x(to, 64)) throw new Invalid("bad to (publicId = 0x + 64 bytes)");
            if (!Hex.is0x(from, 64)) throw new Invalid("bad from (publicId = 0x + 64 bytes)");
            if (!Hex.isBareHex(blob, MAX_MSG_BYTES / 2)) throw new Invalid("bad blob hex");
            pk = Hex.decode(from.substring(2 + 64));      // signPk is the second 32 bytes of the publicId
        }

        byte[] msg = Json.canonicalWithout(m, "sig").getBytes(StandardCharsets.UTF_8);
        if (!Ed25519.verify(pk, msg, Hex.decode(sig.substring(2)))) throw new Invalid("bad signature");
        return new Envelope(kind, raw, m);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof String)) throw new Invalid(k + " must be a string");
        return (String) v;
    }
}
