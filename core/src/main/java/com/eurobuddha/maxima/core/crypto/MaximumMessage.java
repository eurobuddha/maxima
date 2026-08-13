package com.eurobuddha.maxima.core.crypto;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.Streamable;
import com.eurobuddha.maxima.core.identity.MxAddress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.PrivateKey;

/**
 * A signed - and optionally encrypted - standalone blob.
 *
 * This is classic's {@code maxmessage} / {@code MaximumMessage}. It never
 * touches the Maxima wire: it is an offline helper for signing or sealing data
 * that will travel by some other means. Useful for exactly the things a
 * messenger needs outside the transport - signed invites, sealed backups,
 * verifiable receipts.
 *
 * <pre>
 *   MiniNumber version    (1)
 *   MiniData   data       plaintext, or a CryptoPackage when encrypted
 *   MiniData   publickey  the signer
 *   MiniData   signature  SHA256withRSA over data
 * </pre>
 */
public final class MaximumMessage implements Streamable {

    /** Classic marks the UNENCRYPTED form with this sentinel prefix. */
    public static final MiniData UNENCRYPTED_SENTINEL = new MiniData("0xFFFFFFFF");

    public MiniNumber mVersion = new MiniNumber(1);
    public MiniData mData = new MiniData("0x00");
    public MiniData mPublicKey = new MiniData("0x00");
    public MiniData mSignature = new MiniData("0x00");

    public MaximumMessage() {
    }

    public MaximumMessage(MiniData zData) {
        mData = zData;
    }

    /** Sign a blob with our Maxima identity. */
    public static MaximumMessage sign(byte[] zData, byte[] zPublicDer, PrivateKey zPrivate) {
        MaximumMessage m = new MaximumMessage(new MiniData(zData));
        m.mPublicKey = new MiniData(zPublicDer);
        m.mSignature = new MiniData(MaximaCrypto.sign(zPrivate, zData));
        return m;
    }

    /** Sign AND seal to a recipient's public key. */
    public static MaximumMessage sealTo(byte[] zData, byte[] zRecipientDer,
                                        byte[] zPublicDer, PrivateKey zPrivate) {
        byte[] cipher = Codec.serialise(MaximaCrypto.encrypt(zData, zRecipientDer));
        MaximumMessage m = new MaximumMessage(new MiniData(cipher));
        m.mPublicKey = new MiniData(zPublicDer);
        // Sign the CIPHERTEXT, matching the order the reference verifies in.
        m.mSignature = new MiniData(MaximaCrypto.sign(zPrivate, cipher));
        return m;
    }

    public boolean verify() {
        return MaximaCrypto.verify(mPublicKey.getBytes(), mData.getBytes(), mSignature.getBytes());
    }

    /** Open a sealed blob. Verify the signature FIRST. */
    public byte[] open(byte[] zRecipientPrivateDer) throws IOException {
        if (!verify()) {
            throw new IllegalStateException("signature does not verify");
        }
        return MaximaCrypto.decrypt(
                com.eurobuddha.maxima.core.msg.CryptoPackage.fromBytes(mData.getBytes()),
                zRecipientPrivateDer);
    }

    /** The signer's Mx form, as classic reports it. */
    public String mxPublicKey() {
        return MxAddress.make(mPublicKey);
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mVersion.writeDataStream(zOut);
        mData.writeDataStream(zOut);
        mPublicKey.writeDataStream(zOut);
        mSignature.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mVersion = MiniNumber.readFromStream(zIn);
        mData = MiniData.readFromStream(zIn);
        mPublicKey = MiniData.readFromStream(zIn);
        mSignature = MiniData.readFromStream(zIn);
    }

    public static MaximumMessage fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MaximumMessage(), zData);
    }

    public byte[] toBytes() {
        return Codec.serialise(this);
    }
}
