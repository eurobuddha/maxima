package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.Hex;
import com.eurobuddha.maxima.core.codec.MiniByte;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.identity.Base32;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.msg.MLSPacketGETReq;
import com.eurobuddha.maxima.core.msg.MLSPacketGETResp;
import com.eurobuddha.maxima.core.msg.MLSPacketSET;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.msg.MaximaInternal;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.msg.MaximaPackage;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INTEROP GO/NO-GO GATE.
 *
 * Every vector in fixtures/golden-vectors.json was produced by the REAL Minima
 * reference implementation (minima.jar). Our codec must reproduce each one
 * byte-for-byte. A single mismatch means we are invisible on the live network,
 * so this test is deliberately exhaustive and fails loudly.
 *
 * Plain main() so it runs with zero test-framework dependencies.
 */
public class ParityTest {

    static int passed = 0;
    static int failed = 0;
    static Map<String, String> golden = new LinkedHashMap<>();

    // Same deterministic filler the generator used.
    static byte[] filler(int len, int seed) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) ((i * 31 + seed * 7 + 11) & 0xFF);
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static void check(String name, String actualHex) {
        String want = golden.get(name);
        if (want == null) {
            System.out.println("  ?? MISSING VECTOR  " + name);
            failed++;
            return;
        }
        if (want.equals(actualHex)) {
            passed++;
        } else {
            failed++;
            System.out.println("  XX MISMATCH  " + name);
            System.out.println("       expected " + want);
            System.out.println("       actual   " + actualHex);
        }
    }

    static void check(String name, Streamable obj) {
        check(name, hex(Codec.serialise(obj)));
    }

    static void checkAscii(String name, String ascii) {
        check(name, hex(ascii.getBytes(StandardCharsets.UTF_8)));
    }

    public static void main(String[] args) throws Exception {
        Path fixture = Paths.get(args.length > 0 ? args[0] : "fixtures/golden-vectors.json");
        String json = new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8);

        Matcher m = Pattern.compile("\\{\"name\":\"(.*?)\",\"kind\":\".*?\",\"note\":\".*?\",\"hex\":\"(.*?)\"\\}")
                .matcher(json);
        while (m.find()) {
            golden.put(m.group(1), m.group(2));
        }
        System.out.println("Loaded " + golden.size() + " golden vectors from " + fixture);
        System.out.println("Asserting :core is byte-identical to the Minima reference...\n");

        // ---------- MiniData ----------
        check("minidata.empty", new MiniData(new byte[0]));
        check("minidata.one", new MiniData("0x00"));
        check("minidata.ff", new MiniData("0xFF"));
        check("minidata.32", new MiniData(filler(32, 1)));
        check("minidata.162", new MiniData(filler(162, 2)));
        check("minidata.128", new MiniData(filler(128, 3)));
        {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            new MiniData(filler(32, 4)).writeHashToStream(dos);
            dos.close();
            check("minidata.hash32", hex(bos.toByteArray()));
        }

        // ---------- MiniString ----------
        check("ministring.empty", new MiniString(""));
        check("ministring.version", new MiniString("1.0"));
        check("ministring.greetver", new MiniString("1.0.46"));
        check("ministring.app.contact", new MiniString("**maxima_contact_ctrl**"));
        check("ministring.app.checkconnect", new MiniString("**maxima_check_connect**"));
        check("ministring.app.mlsset", new MiniString("**maxima_mls_set**"));
        check("ministring.app.mlsget", new MiniString("**maxima_mls_get**"));
        check("ministring.utf8", new MiniString("\u00e9\u20ac\ud83d\ude80"));

        // ---------- MiniNumber ----------
        check("mininumber.zero", new MiniNumber(0));
        check("mininumber.one", new MiniNumber(1));
        check("mininumber.minusone", new MiniNumber(-1));
        check("mininumber.256", new MiniNumber(256));
        check("mininumber.timemilli", new MiniNumber(1755000000000L));
        check("mininumber.half", new MiniNumber(new BigDecimal("0.5")));
        check("mininumber.neghalf", new MiniNumber(new BigDecimal("-0.5")));
        check("mininumber.big", new MiniNumber(new BigDecimal("1000000000000000000")));

        // ---------- MiniByte ----------
        check("minibyte.0", new MiniByte(0));
        check("minibyte.1", new MiniByte(1));
        check("minibyte.255", new MiniByte(255));

        // ---------- Ack frames ----------
        check("ack.fail", hex(Frame.ack(Frame.RESPONSE_FAIL)));
        check("ack.ok", hex(Frame.ack(Frame.RESPONSE_OK)));
        check("ack.unknown", hex(Frame.ack(Frame.RESPONSE_UNKNOWN)));
        check("ack.toobig", hex(Frame.ack(Frame.RESPONSE_TOOBIG)));
        check("ack.wronghash", hex(Frame.ack(Frame.RESPONSE_WRONGHASH)));

        // ---------- base32 / Mx address ----------
        for (String s : new String[]{"0x00", "0x01", "0xFF", "0x000001", "0xDEADBEEF"}) {
            checkAscii("base32.encode." + s, Base32.encode(new MiniData(s).getBytes()));
        }
        checkAscii("base32.encode.162", Base32.encode(filler(162, 5)));
        checkAscii("mxaddress.pubkey162", MxAddress.make(new MiniData(filler(162, 6))));
        checkAscii("mxaddress.short", MxAddress.make(new MiniData("0xDEADBEEF")));

        // ---------- SHA3-256 ----------
        check("sha3.empty", hex(Hashes.sha3(new byte[0])));
        check("sha3.abc", hex(Hashes.sha3("abc".getBytes(StandardCharsets.UTF_8))));
        check("sha3.filler32", hex(Hashes.sha3(filler(32, 1))));

        // ---------- MaximaMessage ----------
        MaximaMessage mm = new MaximaMessage();
        mm.mRandom = new MiniData(filler(32, 10));
        mm.mFrom = new MiniData(filler(162, 11));
        mm.mTo = new MiniData(filler(162, 12));
        mm.mTimeMilli = new MiniNumber(1755000000000L);
        mm.mApplication = new MiniString("**maxima_contact_ctrl**");
        mm.mData = new MiniData(filler(64, 13));
        byte[] mmBytes = Codec.serialise(mm);
        check("maximamessage.canonical", hex(mmBytes));
        check("msgid.canonical", hex(mm.msgid().getBytes()));

        // ---------- MaximaInternal ----------
        MaximaInternal mi = new MaximaInternal();
        mi.mFrom = new MiniData(filler(162, 11));
        mi.mData = new MiniData(mmBytes);
        mi.mSignature = new MiniData(filler(128, 14));
        check("maximainternal.canonical", mi);

        // ---------- CryptoPackage ----------
        CryptoPackage cp = new CryptoPackage(
                new MiniData(filler(16, 15)),
                new MiniData(filler(128, 16)),
                new MiniData(filler(80, 17)));
        byte[] cpBytes = Codec.serialise(cp);
        check("cryptopackage.canonical", hex(cpBytes));

        // ---------- MaximaPackage ----------
        MaximaPackage mp = new MaximaPackage(new MiniData(filler(162, 12)), new MiniData(cpBytes));
        byte[] mpBytes = Codec.serialise(mp);
        check("maximapackage.canonical", hex(mpBytes));
        check("customhash.of.maximapackage", hex(Hashes.sha3(mpBytes)));

        // ---------- CTRL ----------
        MaximaCTRLMessage ctrlId = MaximaCTRLMessage.id(new MiniData(filler(162, 18)));
        check("ctrl.type_id", ctrlId);
        check("ctrl.type_id.nio", hex(Frame.body(Frame.MSG_MAXIMA_CTRL, ctrlId)));
        // Bare Mx key, no @host - matches what real nodes actually send.
        String bareMls = MxAddress.make(new MiniData(filler(162, 19)));
        check("ctrl.type_mls", MaximaCTRLMessage.mls(bareMls));

        // The guard must reject an address-with-host, which would double the
        // host on the peer's side.
        try {
            MaximaCTRLMessage.mls("Mx1234ABCD@1.2.3.4:9001");
            failed++;
            System.out.println("  XX TYPE_MLS guard did not reject an @host payload");
        } catch (IllegalArgumentException expected) {
            passed++;
        }

        // ---------- MLS ----------
        MLSPacketSET set = new MLSPacketSET("Mx1234ABCD@1.2.3.4:9001");
        set.addValidPublicKey("0x30819F0001");
        set.addValidPublicKey("0x30819F0002");
        check("mls.set", set);
        check("mls.getreq", new MLSPacketGETReq("0x30819F0001", "0xAABBCCDD"));
        check("mls.getresp", new MLSPacketGETResp(
                "0x30819F0001", "Mx1234ABCD@1.2.3.4:9001", "0xAABBCCDD"));

        // ---------- round-trip decode ----------
        System.out.println("\nRound-trip decode checks:");
        roundTrip("MaximaMessage", mmBytes, MaximaMessage.fromBytes(mmBytes));
        roundTrip("MaximaPackage", mpBytes, MaximaPackage.fromBytes(mpBytes));
        roundTrip("CryptoPackage", cpBytes, CryptoPackage.fromBytes(cpBytes));
        roundTrip("MaximaInternal", Codec.serialise(mi), MaximaInternal.fromBytes(Codec.serialise(mi)));
        roundTrip("MLSPacketSET", Codec.serialise(set), MLSPacketSET.fromBytes(Codec.serialise(set)));
        roundTrip("MaximaCTRLMessage", Codec.serialise(ctrlId),
                MaximaCTRLMessage.fromBytes(Codec.serialise(ctrlId)));

        // Mx address round-trip must recover the exact key bytes.
        byte[] key = filler(162, 6);
        byte[] back = MxAddress.convert(MxAddress.make(new MiniData(key))).getBytes();
        if (java.util.Arrays.equals(key, back)) {
            passed++;
            System.out.println("  ok MxAddress round-trip (162-byte key recovered)");
        } else {
            failed++;
            System.out.println("  XX MxAddress round-trip FAILED");
        }

        // Hex empty-string quirk.
        if ("".equals(Hex.encode(new byte[0]))) {
            passed++;
            System.out.println("  ok Hex.encode(empty) == \"\" (not \"0x\")");
        } else {
            failed++;
            System.out.println("  XX Hex.encode(empty) quirk not reproduced");
        }

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("=====================================");
        if (failed > 0) {
            System.out.println("INTEROP GATE FAILED - do not proceed to the network milestones.");
            System.exit(1);
        }
        System.out.println("Byte-for-byte parity with the Minima reference confirmed.");
    }

    static void roundTrip(String name, byte[] original, Streamable decoded) {
        byte[] re = Codec.serialise(decoded);
        if (java.util.Arrays.equals(original, re)) {
            passed++;
            System.out.println("  ok " + name + " re-serialises identically");
        } else {
            failed++;
            System.out.println("  XX " + name + " round-trip mismatch");
        }
    }
}
