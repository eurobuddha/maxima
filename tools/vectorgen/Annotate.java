import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.msg.MaximaInternal;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.Frame;

import java.security.KeyPair;

/**
 * Emits a byte-by-byte annotated breakdown of a REAL Maxima frame, for the
 * walkthrough document.
 *
 * Generated rather than hand-written so the documentation cannot drift away
 * from what the code actually puts on the wire.
 */
public class Annotate {

    static int off = 0;
    static byte[] all;

    static String hex(byte[] b, int from, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + len && i < b.length; i++) {
            sb.append(String.format("%02X", b[i]));
        }
        return sb.toString();
    }

    /** Print a field: offset, bytes (truncated), and what it is. */
    static void f(int len, String name, String note) {
        String h = hex(all, off, len);
        if (h.length() > 32) {
            h = h.substring(0, 32) + "..";
        }
        System.out.printf("  %04d  %-34s %-28s %s%n", off, h, name, note);
        off += len;
    }

    static void section(String title) {
        System.out.println();
        System.out.println("  ---- " + title + " ----");
    }

    public static void main(String[] args) throws Exception {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i + 1);
        }
        MaximaIdentity me = MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
        KeyPair kp = me.keyPair();
        byte[] pub = me.publicKey();

        MaximaSender.Built built = MaximaSender.build(
                pub, kp.getPrivate(), pub,
                "myapp", "hello".getBytes("UTF-8"), 1755000000000L);

        byte[] body = Frame.body(Frame.MSG_MAXIMA_TXPOW, built.unit);
        // A complete frame = int32 length + body.
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
        dos.writeInt(body.length);
        dos.write(body);
        dos.close();
        all = bos.toByteArray();

        System.out.println("A COMPLETE MAXIMA FRAME, " + all.length + " BYTES");
        System.out.println("  offset  bytes                              field                        meaning");
        System.out.println("  " + "-".repeat(104));

        section("TCP frame header (Frame.java)");
        f(4, "int32 length", "= " + body.length + " bytes of body follow");
        f(1, "uint8 type", "0x0A = 10 = MSG_MAXIMA_TXPOW");

        section("MaxTxPoW (MaxTxPoW.java)");
        f(4, "MiniString len", "= 3");
        f(3, "  \"1.0\"", "version, parsed then discarded");

        section("MaximaPackage - PLAINTEXT, this is all a relay reads");
        f(4, "MiniString len", "= 3");
        f(3, "  \"1.0\"", "version");
        f(4, "MiniData len", "= 162");
        f(162, "  to (routing key)", "recipient's X.509 DER pubkey, IN THE CLEAR");
        int cpLen = Codec.serialise(
                CryptoPackage.fromBytes(built.unit.mMaxima.mData.getBytes())).length;
        f(4, "MiniData len", "= " + cpLen + "  (the CryptoPackage)");

        section("CryptoPackage - everything below here is encrypted to `to`");
        f(4, "MiniData len", "= 16");
        f(16, "  iv", "AES-CBC initialisation vector");
        f(4, "MiniData len", "= 128");
        f(128, "  secret", "AES-128 key, RSA-wrapped to the recipient");
        int ctLen = built.unit.mMaxima.mData.getLength() - (4 + 16) - (4 + 128) - 4;
        f(4, "MiniData len", "= " + ctLen);
        f(ctLen, "  ciphertext", "AES/CBC/PKCS5 over the MaximaInternal");

        System.out.println();
        System.out.println("  ---- the TxPoW carrier follows (header + hasBody flag) ----");
        System.out.printf("  %04d  %-34s %-28s %s%n", off, "...",
                "TxPoW", "carrier; only customHash is checked");

        // Now show what is INSIDE the ciphertext.
        byte[] plain = com.eurobuddha.maxima.core.crypto.MaximaCrypto.decrypt(
                CryptoPackage.fromBytes(built.unit.mMaxima.mData.getBytes()),
                kp.getPrivate().getEncoded());

        System.out.println();
        System.out.println();
        System.out.println("DECRYPTED: MaximaInternal, " + plain.length + " bytes");
        System.out.println("  " + "-".repeat(104));
        all = plain;
        off = 0;
        f(4, "MiniData len", "= 162");
        f(162, "  from", "sender's pubkey (the SIGNER)");
        MaximaInternal mi = MaximaInternal.fromBytes(plain);
        f(4, "MiniData len", "= " + mi.mData.getLength() + "  (the MaximaMessage)");
        f(mi.mData.getLength(), "  data", "the MaximaMessage, signed below");
        f(4, "MiniData len", "= 128");
        f(128, "  signature", "SHA256withRSA over `data`");

        System.out.println();
        System.out.println();
        System.out.println("INNERMOST: MaximaMessage, " + mi.mData.getLength() + " bytes");
        System.out.println("  NOTE THE ORDER: random, from, to, TIME, application, data");
        System.out.println("  " + "-".repeat(104));
        all = mi.mData.getBytes();
        off = 0;
        MaximaMessage mm = MaximaMessage.fromBytes(all);
        f(4, "MiniData len", "= 32");
        f(32, "  random", "makes every msgid unique");
        f(4, "MiniData len", "= 162");
        f(162, "  from", "must equal MaximaInternal.from");
        f(4, "MiniData len", "= 162");
        f(162, "  to", "");
        int tl = Codec.serialise(mm.mTimeMilli).length;
        f(tl, "MiniNumber timeMilli", "scale, len, then BigInteger BE");
        f(4, "MiniString len", "= 5");
        f(5, "  \"myapp\"", "the application string");
        f(4, "MiniData len", "= 5");
        f(5, "  \"hello\"", "the payload");

        System.out.println();
        System.out.println();
        System.out.println("THE TWO HASHES - same shape, different objects, easy to confuse");
        System.out.println("  " + "-".repeat(104));
        System.out.println("  msgid      = SHA3-256(MaximaMessage) = " + built.msgid.to0xString());
        System.out.println("               both peers derive this identically -> dedup key");
        System.out.println("  customHash = SHA3-256(MaximaPackage) = "
                + built.unit.mTxPoW.getHeader().mCustomHash.to0xString());
        System.out.println("               sits in the TxPoW header; the ONLY thing a receiver verifies");

        System.out.println();
        System.out.println("THE FIVE ACK FRAMES - senders byte-compare the whole body");
        System.out.println("  " + "-".repeat(104));
        String[] names = {"FAIL", "OK", "UNKNOWN", "TOOBIG", "WRONGHASH"};
        for (int i = 0; i < names.length; i++) {
            byte[] ack = Frame.ack(i);
            System.out.printf("  %-10s %s   (04-byte len omitted; this is the body)%n",
                    names[i], hex(ack, 0, ack.length));
        }
    }
}
