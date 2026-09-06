package com.eurobuddha.maxima.wake;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The APNs provider token: a JWT signed ES256 with the publisher's .p8 key (Apple's "token-based
 * connection"). Cached for 50 minutes (Apple accepts tokens up to an hour old, rejects reuse
 * past that, and rate-limits refreshes under 20 minutes).
 */
public final class ApnsJwt {

    static final long REFRESH_MS = 50 * 60_000L;

    private final PrivateKey mKey;
    private final String mKeyId;
    private final String mTeamId;
    private volatile String mToken = "";
    private volatile long mIssued;

    public ApnsJwt(byte[] zP8Pem, String zKeyId, String zTeamId) throws Exception {
        mKey = parseP8(zP8Pem);
        mKeyId = zKeyId;
        mTeamId = zTeamId;
    }

    /** The current token, minted or refreshed as needed. */
    public synchronized String token() throws Exception {
        long now = System.currentTimeMillis();
        if (mToken.isEmpty() || now - mIssued > REFRESH_MS) {
            mToken = mint(now / 1000);
            mIssued = now;
        }
        return mToken;
    }

    String mint(long zIat) throws Exception {
        String header = b64url(("{\"alg\":\"ES256\",\"kid\":\"" + mKeyId + "\"}").getBytes(StandardCharsets.UTF_8));
        String claims = b64url(("{\"iss\":\"" + mTeamId + "\",\"iat\":" + zIat + "}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(mKey);
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] der = sig.sign();
        return signingInput + "." + b64url(derToJose(der, 32));
    }

    /** Apple's AuthKey_XXXX.p8 is a PEM PKCS#8 EC private key. */
    static PrivateKey parseP8(byte[] zPem) throws Exception {
        String s = new String(zPem, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(s);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** JOSE wants the raw r||s (64 bytes for P-256), not the DER SEQUENCE Java produces. */
    static byte[] derToJose(byte[] zDer, int zPartLen) {
        // SEQUENCE { INTEGER r, INTEGER s }
        int i = 2;
        if ((zDer[1] & 0x80) != 0) {
            i = 2 + (zDer[1] & 0x7F);
        }
        if (zDer[i] != 0x02) {
            throw new IllegalArgumentException("bad DER signature");
        }
        int rLen = zDer[i + 1];
        byte[] r = new byte[rLen];
        System.arraycopy(zDer, i + 2, r, 0, rLen);
        i += 2 + rLen;
        if (zDer[i] != 0x02) {
            throw new IllegalArgumentException("bad DER signature");
        }
        int sLen = zDer[i + 1];
        byte[] s = new byte[sLen];
        System.arraycopy(zDer, i + 2, s, 0, sLen);
        byte[] out = new byte[zPartLen * 2];
        copyFixed(new BigInteger(1, r), out, 0, zPartLen);
        copyFixed(new BigInteger(1, s), out, zPartLen, zPartLen);
        return out;
    }

    private static void copyFixed(BigInteger v, byte[] out, int off, int len) {
        byte[] b = v.toByteArray();
        int start = Math.max(0, b.length - len);
        int n = b.length - start;
        System.arraycopy(b, start, out, off + (len - n), n);
    }

    static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
