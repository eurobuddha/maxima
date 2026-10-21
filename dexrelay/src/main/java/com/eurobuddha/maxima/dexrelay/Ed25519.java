package com.eurobuddha.maxima.dexrelay;

import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * Ed25519 signature VERIFICATION in pure Java (RFC 8032 §5.1.7), BigInteger field arithmetic, extended
 * twisted-Edwards coordinates. Exists because two fleet relays run JDK 11, which has no EdDSA provider; the
 * JCA path would silently make those relays reject every order. Cross-checked against JCA in Ed25519Test.
 * Cofactorless check ([S]B == R + [k]A), canonical S (< L) enforced, like libsodium/tweetnacl verify.
 * Verification only — this module never holds a private key.
 */
final class Ed25519 {
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger I = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);   // sqrt(-1)
    private static final BigInteger BY = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
    private static final BigInteger BX = recoverX(BY, false);
    private static final Point B = Point.affine(BX, BY);

    private Ed25519() {}

    static boolean verify(byte[] pk, byte[] msg, byte[] sig) {
        try {
            if (pk == null || msg == null || sig == null || pk.length != 32 || sig.length != 64) return false;
            Point a = decode(pk);
            if (a == null) return false;
            byte[] rBytes = new byte[32];
            System.arraycopy(sig, 0, rBytes, 0, 32);
            Point r = decode(rBytes);
            if (r == null) return false;
            byte[] sBytes = new byte[32];
            System.arraycopy(sig, 32, sBytes, 0, 32);
            BigInteger s = littleEndian(sBytes);
            if (s.compareTo(L) >= 0) return false;
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(rBytes); sha.update(pk); sha.update(msg);
            BigInteger k = littleEndian(sha.digest()).mod(L);
            Point lhs = B.mul(s);
            Point rhs = r.add(a.mul(k));
            return lhs.sameAffine(rhs);
        } catch (Exception e) {
            return false;
        }
    }

    static BigInteger littleEndian(byte[] le) {
        byte[] be = new byte[le.length + 1];
        for (int i = 0; i < le.length; i++) be[le.length - i] = le[i];
        return new BigInteger(be);
    }

    /** RFC 8032 §5.1.3. Returns null for a non-canonical or off-curve encoding. */
    static Point decode(byte[] enc) {
        byte[] y = enc.clone();
        boolean sign = (y[31] & 0x80) != 0;
        y[31] &= 0x7F;
        BigInteger yv = littleEndian(y);
        if (yv.compareTo(P) >= 0) return null;
        BigInteger x = recoverX(yv, sign);
        if (x == null) return null;
        return Point.affine(x, yv);
    }

    private static BigInteger recoverX(BigInteger y, boolean sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger v3 = v.multiply(v).mod(P).multiply(v).mod(P);
        BigInteger v7 = v3.multiply(v3).mod(P).multiply(v).mod(P);
        BigInteger x = u.multiply(v3).mod(P).multiply(u.multiply(v7).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
        BigInteger vx2 = v.multiply(x).mod(P).multiply(x).mod(P);
        if (!vx2.equals(u)) {
            if (vx2.equals(P.subtract(u).mod(P))) x = x.multiply(I).mod(P);
            else return null;
        }
        if (x.signum() == 0 && sign) return null;
        if (x.testBit(0) != sign) x = P.subtract(x);
        return x;
    }

    /** Extended coordinates (X, Y, Z, T), x = X/Z, y = Y/Z, T = XY/Z. a = -1 formulas (EFD add-2008-hwcd-3 / dbl-2008-hwcd). */
    static final class Point {
        final BigInteger x, y, z, t;
        static final Point ZERO = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) { this.x = x; this.y = y; this.z = z; this.t = t; }

        static Point affine(BigInteger x, BigInteger y) { return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P)); }

        Point add(Point q) {
            BigInteger a = y.subtract(x).multiply(q.y.subtract(q.x)).mod(P);
            BigInteger b = y.add(x).multiply(q.y.add(q.x)).mod(P);
            BigInteger c = t.multiply(D2).mod(P).multiply(q.t).mod(P);
            BigInteger d = z.shiftLeft(1).multiply(q.z).mod(P);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Point dbl() {
            BigInteger a = x.multiply(x).mod(P);
            BigInteger b = y.multiply(y).mod(P);
            BigInteger c = z.multiply(z).shiftLeft(1).mod(P);
            BigInteger d = P.subtract(a);                       // a = -1
            BigInteger xy = x.add(y);
            BigInteger e = xy.multiply(xy).subtract(a).subtract(b).mod(P);
            BigInteger g = d.add(b), f = g.subtract(c), h = d.subtract(b);
            return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Point mul(BigInteger k) {
            Point r = ZERO;
            for (int i = k.bitLength() - 1; i >= 0; i--) {
                r = r.dbl();
                if (k.testBit(i)) r = r.add(this);
            }
            return r;
        }

        boolean sameAffine(Point q) {
            return x.multiply(q.z).mod(P).equals(q.x.multiply(z).mod(P))
                && y.multiply(q.z).mod(P).equals(q.y.multiply(z).mod(P));
        }
    }
}
