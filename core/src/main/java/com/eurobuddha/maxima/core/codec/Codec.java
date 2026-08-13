package com.eurobuddha.maxima.core.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Serialise / deserialise helpers for {@link Streamable}s. */
public final class Codec {

    private Codec() {
    }

    public static byte[] serialise(Streamable zObj) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            zObj.writeDataStream(dos);
            dos.flush();
            dos.close();
            return bos.toByteArray();
        } catch (IOException e) {
            // Writing to memory cannot fail for well-formed objects.
            throw new IllegalStateException("Serialisation failed", e);
        }
    }

    public static <T extends Streamable> T deserialise(T zTarget, byte[] zData) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(zData);
        DataInputStream dis = new DataInputStream(bis);
        zTarget.readDataStream(dis);
        dis.close();
        return zTarget;
    }
}
