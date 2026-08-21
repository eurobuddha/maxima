package com.eurobuddha.maxima.core.identity;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.Hashes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The {@code Mx...} address form.
 *
 * Framing before base32 encoding:
 * <pre>
 *   byte    0x01          mandatory non-zero guard (stops base32 truncating leading zeros)
 *   int16   datalen       big-endian
 *   byte[]  data          the raw key/hash
 *   byte[4] checksum      first 4 bytes of SHA3-256(data)
 * </pre>
 *
 * A 162-byte RSA-1024 X.509 public key yields a ~273 character address. That is
 * expected, not a bug.
 */
public final class MxAddress {

    private MxAddress() {
    }

    /** Reference: Address.makeMinimaAddress */
    public static String make(MiniData zAddress) {
        byte[] data = zAddress.getBytes();

        byte[] hash = Hashes.sha3(data);
        byte[] checksum = new byte[4];
        System.arraycopy(hash, 0, checksum, 0, 4);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            dos.write(1);
            dos.writeShort(data.length);
            dos.write(data);
            dos.write(checksum);
            dos.close();
            bos.close();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid MxAddress: " + e, e);
        }

        return Base32.encode(bos.toByteArray());
    }

    public static String make(byte[] zAddress) {
        return make(new MiniData(zAddress));
    }

    /** Reference: Address.convertMinimaAddress. Throws if the guard or checksum is wrong. */
    public static MiniData convert(String zMxAddress) {
        byte[] decode = Base32.decode(zMxAddress);

        ByteArrayInputStream bis = new ByteArrayInputStream(decode);
        DataInputStream dis = new DataInputStream(bis);

        byte[] data;
        byte[] checksum = new byte[4];
        try {
            int one = dis.read();
            if (one != 1) {
                throw new IllegalArgumentException(
                        "Invalid MxAddress - should start with 1: " + zMxAddress);
            }
            int datalen = dis.readUnsignedShort();   // signed readShort -> negative -> NegativeArraySizeException
            if (datalen < 0 || datalen > 4096) {
                throw new IllegalArgumentException("Invalid MxAddress length");
            }
            data = new byte[datalen];
            dis.readFully(data);
            dis.readFully(checksum);
            dis.close();
            bis.close();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid MxAddress: " + zMxAddress + " " + e, e);
        }

        byte[] hash = Hashes.sha3(data);
        for (int i = 0; i < 4; i++) {
            if (hash[i] != checksum[i]) {
                throw new IllegalArgumentException(
                        "Invalid MxAddress - checksum wrong for " + zMxAddress);
            }
        }

        return new MiniData(data);
    }

    /**
     * Reference validation (maxextra.checkValidMxAddress) is only: starts with
     * "Mx", contains '@', contains ':'. Kept identical so we accept exactly what
     * classic accepts.
     */
    public static boolean isValidContactAddress(String zAddress) {
        return zAddress != null
                && zAddress.startsWith("Mx")
                && zAddress.contains("@")
                && zAddress.contains(":");
    }
}
