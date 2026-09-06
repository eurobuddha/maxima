package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.Hex;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MLSPacketGETReq;
import com.eurobuddha.maxima.core.msg.MLSPacketGETResp;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.msg.MaximaInternal;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.msg.MaximaPackage;
import com.eurobuddha.maxima.core.msg.TxHeader;
import com.eurobuddha.maxima.core.msg.TxPoW;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.rpc.RpcEnvelope;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Golden vectors for the SWIFT port (Parlons Cloud for iOS): everything a device must produce
 * bit-exactly, dumped from :core (which is itself gated against the Minima reference by
 * {@link ParityTest}). Same JSON shape as fixtures/golden-vectors.json so one loader reads both.
 *
 * Where a construction is randomised on purpose (RSA-PKCS1 wrapping of the AES key), the vector
 * gives the inputs and the deterministic parts, and the wrapped secret is verified by DECRYPTING
 * it with the recipient's seed-derived private key rather than by byte comparison.
 *
 * Text values (addresses, JSON) are stored as the hex of their UTF-8 bytes, kind "text".
 *
 * Regenerate: ./gradlew :core:parlonsVectors ; export to the Swift package: :core:exportSwiftVectors
 */
public class ParlonsVectorGen {

    static final List<String> OUT = new ArrayList<>();

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static void vec(String name, String kind, String note, String hexOut) {
        OUT.add("  {\"name\":\"" + esc(name) + "\",\"kind\":\"" + esc(kind)
                + "\",\"note\":\"" + esc(note) + "\",\"hex\":\"" + hexOut + "\"}");
    }

    static void text(String name, String note, String value) {
        vec(name, "text", note, hex(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] ser(Streamable s) {
        return Codec.serialise(s);
    }

    static byte[] filler(int len, int seed) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) ((i * 31 + seed * 7 + 11) & 0xFF);
        return b;
    }

    static String pad(int n) {
        return n < 10 ? "0" + n : Integer.toString(n);
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. seed phrase -> seed ----------
        String phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art";
        vec("bip39.seed", "Bip39.toSeed", "SHA3-256 of the UPPERCASE space-joined 24 words (no PBKDF2)",
                hex(Bip39.toSeed(phrase).getBytes()));
        text("bip39.phrase", "the phrase the seed above comes from", phrase);

        // ---------- 2. identities: the pinned vectors + a prime-search corpus ----------
        String[][] pinned = {
                {"maxima-identity-test-vector-1", "identity"},
                {"maxima-identity-test-vector-1", "host-1"},
                {"maxima-identity-test-vector-2", "identity"},
        };
        for (String[] v : pinned) {
            emitIdentity("identity.pinned." + v[0] + "." + v[1], v[0].getBytes(StandardCharsets.UTF_8), v[1]);
        }
        for (int i = 0; i < 64; i++) {
            byte[] seed = ("parlons-ios-prime-corpus-" + pad(i)).getBytes(StandardCharsets.UTF_8);
            emitIdentity("identity.corpus." + pad(i) + ".identity", seed, "identity");
            if (i < 16) {
                emitIdentity("identity.corpus." + pad(i) + ".host", seed, "host|127.0.0.1:9501");
            }
        }

        // ---------- 3. addresses ----------
        byte[] seedA = "maxima-identity-test-vector-1".getBytes(StandardCharsets.UTF_8);
        byte[] seedB = "maxima-identity-test-vector-2".getBytes(StandardCharsets.UTF_8);
        KeyPair kpA = DeterministicRsa.derive(seedA, "identity");
        KeyPair kpB = DeterministicRsa.derive(seedB, "identity");
        byte[] derA = kpA.getPublic().getEncoded();
        byte[] derB = kpB.getPublic().getEncoded();
        text("mx.identity.A", "MxAddress.make(DER of vector-1 identity)", MxAddress.make(derA));
        text("mx.identity.B", "MxAddress.make(DER of vector-2 identity)", MxAddress.make(derB));
        byte[] lead = new byte[40];
        System.arraycopy(filler(37, 9), 0, lead, 3, 37);   // three leading zero bytes (the guard byte case)
        vec("mx.edge.leadingzeros.data", "bytes", "data with 3 leading zero bytes", hex(lead));
        text("mx.edge.leadingzeros", "MxAddress.make of the data above", MxAddress.make(lead));
        byte[] small = {0x00};
        text("mx.edge.onezero", "MxAddress.make([0x00])", MxAddress.make(small));
        byte[] f = filler(64, 5);
        vec("mx.edge.64.data", "bytes", "64 filler bytes", hex(f));
        text("mx.edge.64", "MxAddress.make of the 64 filler bytes", MxAddress.make(f));
        text("mx.roundtrip.check", "MxAddress.convert(make(x)) == x for the A identity: '1' if true",
                Arrays.equals(MxAddress.convert(MxAddress.make(derA)).getBytes(), derA) ? "1" : "0");

        // ---------- 4. the fixed message pipeline (A -> B) ----------
        long t = 1725000000000L;
        List<String> replyTo = Arrays.asList(MxAddress.make(derA) + "@127.0.0.1:9501",
                MxAddress.make(derA) + "@10.0.0.2:9501");
        byte[] payload = "{\"peer\":\"0xABCD\",\"body\":\"hello from ios\"}".getBytes(StandardCharsets.UTF_8);
        RpcEnvelope req = RpcEnvelope.request("0x0102030405060708090A0B0C0D0E0F10", "parlons.chat.send", replyTo, payload);
        vec("rpc.request", "RpcEnvelope", "type 0, id, method, 2 replyTo, JSON payload", hex(ser(req)));
        vec("rpc.response", "RpcEnvelope", "type 1", hex(ser(RpcEnvelope.response("0x0102030405060708090A0B0C0D0E0F10",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)))));
        vec("rpc.error", "RpcEnvelope", "type 2, payload = MiniString bytes of the message",
                hex(ser(RpcEnvelope.error("0x0102030405060708090A0B0C0D0E0F10", "unpaired device"))));

        MaximaMessage mm = new MaximaMessage();
        mm.mRandom = new MiniData(filler(32, 1));
        mm.mFrom = new MiniData(derA);
        mm.mTo = new MiniData(derB);
        mm.mTimeMilli = new MiniNumber(t);
        mm.mApplication = new MiniString(RpcEnvelope.APPLICATION);
        mm.mData = new MiniData(ser(req));
        byte[] maxdata = ser(mm);
        vec("msg.maximamessage", "MaximaMessage", "random(filler 32/1), from A, to B, time 1725000000000, app rpc, data = rpc.request",
                hex(maxdata));
        vec("msg.msgid", "Hashes.sha3", "SHA3-256 of msg.maximamessage", hex(Hashes.sha3(maxdata)));
        byte[] sig = MaximaCrypto.sign(kpA.getPrivate(), maxdata);
        vec("msg.signature", "SHA256withRSA", "A's PKCS#1 v1.5 signature over msg.maximamessage (deterministic)", hex(sig));
        MaximaInternal mi = new MaximaInternal();
        mi.mFrom = new MiniData(derA);
        mi.mData = new MiniData(maxdata);
        mi.mSignature = new MiniData(sig);
        byte[] internal = ser(mi);
        vec("msg.internal", "MaximaInternal", "from A, data, signature", hex(internal));

        byte[] iv = filler(16, 2);
        byte[] aesKey = filler(16, 3);
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
        byte[] ciphertext = aes.doFinal(internal);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, kpB.getPublic());
        byte[] wrapped = rsa.doFinal(aesKey);
        vec("msg.iv", "bytes", "fixed IV (filler 16/2)", hex(iv));
        vec("msg.aeskey", "bytes", "fixed AES-128 key (filler 16/3)", hex(aesKey));
        vec("msg.ciphertext", "AES/CBC/PKCS5", "msg.internal under the fixed key+iv (deterministic)", hex(ciphertext));
        vec("msg.secret", "RSA/ECB/PKCS1", "aeskey wrapped to B (randomised padding: verify by decrypting with B's key)", hex(wrapped));
        CryptoPackage cp = new CryptoPackage(new MiniData(iv), new MiniData(wrapped), new MiniData(ciphertext));
        vec("msg.cryptopackage", "CryptoPackage", "iv | secret | data (secret is randomised; parse + decrypt)", hex(ser(cp)));
        MaximaPackage mp = new MaximaPackage(new MiniData(derB), new MiniData(ser(cp)));
        byte[] pkg = ser(mp);
        vec("msg.package", "MaximaPackage", "\"1.0\" | to B | cryptopackage bytes", hex(pkg));
        MiniData customHash = new MiniData(Hashes.sha3(pkg));
        vec("msg.customhash", "Hashes.sha3", "SHA3-256 of msg.package", hex(customHash.getBytes()));

        TxPoW carrier = TxPoW.carrier(customHash, t);
        vec("msg.carrier.unmined", "TxPoW", "carrier(customHash, time) with nonce 0", hex(ser(carrier)));
        vec("msg.carrier.unmined.header", "TxHeader", "the header alone", hex(ser(carrier.getHeader())));
        vec("msg.carrier.unmined.txpowid", "Hashes.sha3", "SHA3-256 of the header bytes", hex(carrier.txPowId().getBytes()));
        MaxTxPoW unmined = new MaxTxPoW(mp, carrier);
        vec("msg.maxtxpow.unmined", "MaxTxPoW", "\"1.0\" | package | carrier (nonce 0)", hex(ser(unmined)));
        vec("msg.frame.unmined", "Frame.body", "type 10 frame body of msg.maxtxpow.unmined", hex(Frame.body(Frame.MSG_MAXIMA_TXPOW, unmined)));

        TxPoW mined = TxPoW.carrier(customHash, t);
        if (!mined.mine(TxPoW.MINE_BUDGET_MS)) throw new IllegalStateException("mining failed");
        vec("msg.mined.header", "TxHeader", "a MINED header (its SHA3 meets MIN_TXPOW_VAL)", hex(ser(mined.getHeader())));
        vec("msg.mined.nonce", "MiniNumber", "the nonce that mined it", hex(ser(mined.getHeader().mNonce)));
        vec("msg.mined.txpowid", "Hashes.sha3", "SHA3-256 of msg.mined.header", hex(mined.txPowId().getBytes()));
        vec("pow.min_txpow_val", "BigInteger", "(2^256-1)/10000 as 32 bytes BE", hex(toFixed(TxPoW.MIN_TXPOW_VAL.toByteArray(), 32)));
        vec("pow.mine_target", "BigInteger", "MIN_TXPOW_VAL*10/11 as 32 bytes BE", hex(toFixed(TxPoW.MINE_TARGET.toByteArray(), 32)));

        // ---------- 5. a header with real super-parents (the RLE) ----------
        TxHeader h = new TxHeader();
        h.mNonce = new MiniNumber(123456789L);
        h.mTimeMilli = new MiniNumber(t);
        h.mBlockNumber = new MiniNumber(42);
        h.setAllSuperParents(TxHeader.ZERO_HASH);
        h.mSuperParents[0] = new MiniData(filler(32, 11));
        h.mSuperParents[1] = new MiniData(filler(32, 11));
        h.mSuperParents[2] = new MiniData(filler(32, 12));
        h.mSuperParents[5] = new MiniData(filler(32, 13));
        h.mMMRRoot = new MiniData(filler(32, 14));
        h.mMMRTotal = new MiniNumber(7);
        h.mCustomHash = new MiniData(filler(32, 15));
        h.mTxBodyHash = new MiniData(filler(32, 16));
        vec("txheader.superparents", "TxHeader", "nonce 123456789, block 42, super-parents [11,11,12,0,0,13,0...], mmr 7", hex(ser(h)));
        vec("txheader.superparents.hash", "Hashes.sha3", "SHA3-256 of txheader.superparents", hex(Hashes.sha3(ser(h))));

        // ---------- 6. mailbox possession ack ----------
        KeyPair host = DeterministicRsa.derive(seedA, "host|127.0.0.1:9501");
        byte[] hostDer = host.getPublic().getEncoded();
        for (long seq : new long[] {0L, 7L}) {
            ByteArrayOutputStream cb = new ByteArrayOutputStream();
            DataOutputStream cd = new DataOutputStream(cb);
            cd.write("maxack".getBytes(StandardCharsets.US_ASCII));
            cd.write(hostDer);
            cd.writeLong(seq);
            cd.flush();
            byte[] canonical = cb.toByteArray();
            byte[] asig = MaximaCrypto.sign(host.getPrivate(), canonical);
            vec("mailbox.ack.canonical." + seq, "bytes", "\"maxack\" || host DER || int64 BE seq", hex(canonical));
            vec("mailbox.ack.signature." + seq, "SHA256withRSA", "host key signature over the canonical", hex(asig));
            ByteArrayOutputStream ab = new ByteArrayOutputStream();
            DataOutputStream ad = new DataOutputStream(ab);
            new MiniData(hostDer).writeDataStream(ad);
            new MiniNumber(seq).writeDataStream(ad);
            new MiniData(asig).writeDataStream(ad);
            ad.flush();
            MaximaCTRLMessage ack = new MaximaCTRLMessage(41);
            ack.setData(new MiniData(ab.toByteArray()));
            vec("mailbox.ack.frame." + seq, "Frame.body", "CTRL type 41 frame body: MiniData(key)|MiniNumber(seq)|MiniData(sig)",
                    hex(Frame.body(Frame.MSG_MAXIMA_CTRL, ack)));
        }
        ByteArrayOutputStream pb = new ByteArrayOutputStream();
        DataOutputStream pd = new DataOutputStream(pb);
        new MiniData(hostDer).writeDataStream(pd);
        new MiniNumber(0).writeDataStream(pd);
        pd.flush();
        MaximaCTRLMessage probe = new MaximaCTRLMessage(40);
        probe.setData(new MiniData(pb.toByteArray()));
        vec("mailbox.probe.frame", "Frame.body", "the relay's CTRL type 40 possession probe for the host key, seq 0",
                hex(Frame.body(Frame.MSG_MAXIMA_CTRL, probe)));

        // ---------- 7. attach frames ----------
        vec("greeting.device", "Frame.body", "commsOnly(\"1.0.48\", \"\", 0): host omitted, topBlock -1, no chain",
                hex(Frame.body(Frame.MSG_GREETING, Greeting.commsOnly("1.0.48", "", 0))));
        text("greeting.device.extradata", "the hand-built extraData JSON", Greeting.commsOnly("1.0.48", "", 0).getExtraData());
        vec("greeting.relay.sample", "Frame.body", "a relay greeting with host, cap 512, pool, conns 3, 2 peers",
                hex(Frame.body(Frame.MSG_GREETING, Greeting.commsOnly("1.0.48", "1.2.3.4", 9501,
                        Arrays.asList("5.6.7.8:9501", "9.9.9.9:8001"), 512, true, 3, "", ""))));
        vec("ctrl.id.frame", "Frame.body", "CTRL TYPE_ID carrying the host key DER (162 bytes, raw)",
                hex(Frame.body(Frame.MSG_MAXIMA_CTRL, MaximaCTRLMessage.id(new MiniData(hostDer)))));
        vec("ctrl.mls.frame", "Frame.body", "CTRL TYPE_MLS carrying a bare Mx string (UTF-8, no @host)",
                hex(Frame.body(Frame.MSG_MAXIMA_CTRL, MaximaCTRLMessage.mls(MxAddress.make(derB)))));
        vec("frame.ack.ok", "Frame.ack", "MSG_PING carrying MiniData([0x01])", hex(Frame.ack(Frame.RESPONSE_OK)));
        vec("frame.ack.fail", "Frame.ack", "MSG_PING carrying MiniData([0x00])", hex(Frame.ack(Frame.RESPONSE_FAIL)));
        vec("frame.singleping", "Frame.singlePing", "keep-alive", hex(Frame.singlePing()));

        // ---------- 8. MLS ----------
        String keyBHex = new MiniData(derB).to0xString();
        vec("mls.getreq", "MLSPacketGETReq", "GET for B's key with nonce 0xAABBCCDD", hex(ser(new MLSPacketGETReq(keyBHex, "0xAABBCCDD"))));
        vec("mls.getresp", "MLSPacketGETResp", "answer: B at Mx(B)@1.2.3.4:9501, nonce 0xAABBCCDD",
                hex(ser(new MLSPacketGETResp(keyBHex, MxAddress.make(derB) + "@1.2.3.4:9501", "0xAABBCCDD"))));
        text("mls.permanent.B", "MAX# form: MAX#<B key 0x hex>#<Mx(B)@1.2.3.4:9501>",
                "MAX#" + keyBHex + "#" + MxAddress.make(derB) + "@1.2.3.4:9501");

        // ---------- write ----------
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"Golden vectors for the Swift port of the Parlons device protocol, dumped from :core ")
          .append("(itself parity-gated against the Minima reference). Text vectors hold the UTF-8 bytes as hex.\",\n");
        sb.append("  \"_source\": \"com.eurobuddha.maxima.core.ParlonsVectorGen\",\n");
        sb.append("  \"vectors\": [\n");
        sb.append(String.join(",\n", OUT));
        sb.append("\n  ]\n}\n");
        String out = args.length > 0 ? args[0] : "parlons-vectors.json";
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(out)))) {
            pw.print(sb);
        }
        System.out.println("Wrote " + OUT.size() + " vectors to " + out);
    }

    static void emitIdentity(String name, byte[] seed, String ctx) {
        KeyPair kp = DeterministicRsa.derive(seed, ctx);
        RSAPrivateCrtKey pk = (RSAPrivateCrtKey) kp.getPrivate();
        vec(name + ".seed", "bytes", "raw seed bytes (UTF-8 of the label)", hex(seed));
        text(name + ".ctx", "derivation context", ctx);
        vec(name + ".der", "X.509 SPKI", "162-byte public key DER", hex(kp.getPublic().getEncoded()));
        vec(name + ".p", "BigInteger", "prime p (the larger)", hex(pk.getPrimeP().toByteArray()));
        vec(name + ".q", "BigInteger", "prime q", hex(pk.getPrimeQ().toByteArray()));
    }

    static byte[] toFixed(byte[] be, int len) {
        byte[] out = new byte[len];
        int n = Math.min(be.length, len);
        System.arraycopy(be, be.length - n, out, len - n, n);
        return out;
    }
}
