import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.minima.objects.Address;
import org.minima.objects.base.MiniByte;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.base.MiniString;
import org.minima.system.network.maxima.MaximaCTRLMessage;
import org.minima.system.network.maxima.message.MaximaInternal;
import org.minima.system.network.maxima.message.MaximaMessage;
import org.minima.system.network.maxima.message.MaximaPackage;
import org.minima.system.network.maxima.mls.MLSPacketGETReq;
import org.minima.system.network.maxima.mls.MLSPacketGETResp;
import org.minima.system.network.maxima.mls.MLSPacketSET;
import org.minima.utils.BaseConverter;
import org.minima.utils.Crypto;
import org.minima.utils.Streamable;
import org.minima.utils.encrypt.CryptoPackage;

/**
 * Golden-vector generator.
 *
 * Drives the REAL Minima reference implementation (minima.jar) to emit
 * byte-exact fixtures. Our :core implementation must reproduce every one of
 * these exactly, or we are invisible on the live Maxima network.
 *
 * This is a build-time dev tool, not shipped code.
 */
public class VectorGen {

    static final List<String> OUT = new ArrayList<>();

    // ---------- helpers ----------

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static byte[] ser(Streamable s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        s.writeDataStream(dos);
        dos.flush();
        dos.close();
        return bos.toByteArray();
    }

    /** The NIO frame body exactly as NIOManager.createNIOMessage builds it: type byte then object. */
    static byte[] nioBody(int type, Streamable s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        new MiniByte(type).writeDataStream(dos);
        s.writeDataStream(dos);
        dos.flush();
        dos.close();
        return bos.toByteArray();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
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

    static void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // Deterministic filler bytes so vectors are reproducible.
    static byte[] filler(int len, int seed) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) ((i * 31 + seed * 7 + 11) & 0xFF);
        return b;
    }

    public static void main(String[] args) throws Exception {

        // ---------- 1. MiniData ----------
        vec("minidata.empty", "MiniData", "zero-length", hex(ser(new MiniData(new byte[0]))));
        vec("minidata.one", "MiniData", "single byte 0x00", hex(ser(new MiniData("0x00"))));
        vec("minidata.ff", "MiniData", "single byte 0xFF", hex(ser(new MiniData("0xFF"))));
        vec("minidata.32", "MiniData", "32-byte random-field width", hex(ser(new MiniData(filler(32, 1)))));
        vec("minidata.162", "MiniData", "162 bytes = RSA-1024 X.509 DER pubkey width", hex(ser(new MiniData(filler(162, 2)))));
        vec("minidata.128", "MiniData", "128 bytes = RSA-1024 signature width", hex(ser(new MiniData(filler(128, 3)))));

        // writeHashToStream is a DIFFERENT method used by TxHeader for customHash/txBodyHash
        {
            MiniData h = new MiniData(filler(32, 4));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            h.writeHashToStream(dos);
            dos.close();
            vec("minidata.hash32", "MiniData.writeHashToStream", "32-byte hash form", hex(bos.toByteArray()));
        }

        // ---------- 2. MiniString ----------
        vec("ministring.empty", "MiniString", "\"\"", hex(ser(new MiniString(""))));
        vec("ministring.version", "MiniString", "\"1.0\" protocol version", hex(ser(new MiniString("1.0"))));
        vec("ministring.greetver", "MiniString", "\"1.0.46\" greeting version", hex(ser(new MiniString("1.0.46"))));
        vec("ministring.app.contact", "MiniString", "**maxima_contact_ctrl**", hex(ser(new MiniString("**maxima_contact_ctrl**"))));
        vec("ministring.app.checkconnect", "MiniString", "**maxima_check_connect**", hex(ser(new MiniString("**maxima_check_connect**"))));
        vec("ministring.app.mlsset", "MiniString", "**maxima_mls_set**", hex(ser(new MiniString("**maxima_mls_set**"))));
        vec("ministring.app.mlsget", "MiniString", "**maxima_mls_get**", hex(ser(new MiniString("**maxima_mls_get**"))));
        vec("ministring.utf8", "MiniString", "multibyte UTF-8 é€🚀", hex(ser(new MiniString("é€🚀"))));

        // ---------- 3. MiniNumber ----------
        vec("mininumber.zero", "MiniNumber", "0", hex(ser(new MiniNumber(0))));
        vec("mininumber.one", "MiniNumber", "1", hex(ser(new MiniNumber(1))));
        vec("mininumber.minusone", "MiniNumber", "-1", hex(ser(new MiniNumber(-1))));
        vec("mininumber.256", "MiniNumber", "256 (multi-byte unscaled)", hex(ser(new MiniNumber(256))));
        vec("mininumber.timemilli", "MiniNumber", "1755000000000 typical timeMilli", hex(ser(new MiniNumber(1755000000000L))));
        vec("mininumber.half", "MiniNumber", "0.5 (scale exercise)", hex(ser(new MiniNumber(new BigDecimal("0.5")))));
        vec("mininumber.neghalf", "MiniNumber", "-0.5", hex(ser(new MiniNumber(new BigDecimal("-0.5")))));
        // Reference enforces a 2^64 ceiling (MiniNumber.checkLimits) - stay under it.
        vec("mininumber.big", "MiniNumber", "1000000000000000000 (near 2^64 ceiling)", hex(ser(new MiniNumber(new BigDecimal("1000000000000000000")))));

        // ---------- 4. MiniByte ----------
        vec("minibyte.0", "MiniByte", "0", hex(ser(new MiniByte(0))));
        vec("minibyte.1", "MiniByte", "1", hex(ser(new MiniByte(1))));
        vec("minibyte.255", "MiniByte", "255 -> 0xFF", hex(ser(new MiniByte(255))));

        // ---------- 5. Ack frame bodies (senders byte-compare these) ----------
        String[] ackNames = {"FAIL", "OK", "UNKNOWN", "TOOBIG", "WRONGHASH"};
        for (int i = 0; i < ackNames.length; i++) {
            byte[] body = nioBody(8, new MiniData(new byte[]{(byte) i}));
            vec("ack." + ackNames[i].toLowerCase(), "NIOBody(MSG_PING=8)",
                    "MAXIMA_RESPONSE_" + ackNames[i], hex(body));
        }

        // ---------- 6. Mx address / base32 ----------
        String[] b32in = {"0x00", "0x01", "0xFF", "0x000001", "0xDEADBEEF"};
        for (String s : b32in) {
            byte[] raw = new MiniData(s).getBytes();
            vec("base32.encode." + s, "BaseConverter.encode32", "raw " + s,
                    hex(BaseConverter.encode32(raw).getBytes("UTF-8")));
        }
        vec("base32.encode.162", "BaseConverter.encode32", "162-byte pubkey-width input",
                hex(BaseConverter.encode32(filler(162, 5)).getBytes("UTF-8")));

        MiniData pubkeyish = new MiniData(filler(162, 6));
        String mxAddr = Address.makeMinimaAddress(pubkeyish);
        vec("mxaddress.pubkey162", "Address.makeMinimaAddress",
                "162-byte RSA pubkey -> Mx address (ASCII of result)",
                hex(mxAddr.getBytes("UTF-8")));
        vec("mxaddress.short", "Address.makeMinimaAddress", "4-byte input",
                hex(Address.makeMinimaAddress(new MiniData("0xDEADBEEF")).getBytes("UTF-8")));

        // ---------- 7. SHA3-256 ----------
        vec("sha3.empty", "Crypto.hashData", "SHA3-256 of empty", hex(Crypto.getInstance().hashData(new byte[0])));
        vec("sha3.abc", "Crypto.hashData", "SHA3-256 of \"abc\"", hex(Crypto.getInstance().hashData("abc".getBytes("UTF-8"))));
        vec("sha3.filler32", "Crypto.hashData", "SHA3-256 of filler(32,1)", hex(Crypto.getInstance().hashData(filler(32, 1))));

        // ---------- 8. MaximaMessage ----------
        MaximaMessage mm = new MaximaMessage();
        setField(mm, "mRandom", new MiniData(filler(32, 10)));
        mm.mFrom = new MiniData(filler(162, 11));
        mm.mTo = new MiniData(filler(162, 12));
        mm.mTimeMilli = new MiniNumber(1755000000000L);
        mm.mApplication = new MiniString("**maxima_contact_ctrl**");
        mm.mData = new MiniData(filler(64, 13));
        byte[] mmBytes = ser(mm);
        vec("maximamessage.canonical", "MaximaMessage",
                "field order random,from,to,time,application,data", hex(mmBytes));

        // msgid = SHA3-256 of the serialised MaximaMessage
        vec("msgid.canonical", "SHA3-256(MaximaMessage)",
                "msgid both sides compute identically", hex(Crypto.getInstance().hashData(mmBytes)));

        // ---------- 9. MaximaInternal ----------
        MaximaInternal mi = new MaximaInternal();
        mi.mFrom = new MiniData(filler(162, 11));
        mi.mData = new MiniData(mmBytes);
        mi.mSignature = new MiniData(filler(128, 14));
        byte[] miBytes = ser(mi);
        vec("maximainternal.canonical", "MaximaInternal", "from,data,signature", hex(miBytes));

        // ---------- 10. CryptoPackage ----------
        CryptoPackage cp = new CryptoPackage();
        setField(cp, "mIvParam", new MiniData(filler(16, 15)));
        setField(cp, "mSecret", new MiniData(filler(128, 16)));
        setField(cp, "mData", new MiniData(filler(80, 17)));
        byte[] cpBytes = ser(cp);
        vec("cryptopackage.canonical", "CryptoPackage", "iv(16),secret(128),data", hex(cpBytes));

        // ---------- 11. MaximaPackage ----------
        MaximaPackage mp = new MaximaPackage(new MiniData(filler(162, 12)), new MiniData(cpBytes));
        byte[] mpBytes = ser(mp);
        vec("maximapackage.canonical", "MaximaPackage", "version \"1.0\", to, data", hex(mpBytes));
        vec("customhash.of.maximapackage", "SHA3-256(MaximaPackage)",
                "must equal TxPoW.customHash", hex(Crypto.getInstance().hashData(mpBytes)));

        // ---------- 12. MaximaCTRLMessage ----------
        MaximaCTRLMessage ctrlId = new MaximaCTRLMessage(MaximaCTRLMessage.MAXIMACTRL_TYPE_ID);
        ctrlId.setData(new MiniData(filler(162, 18)));
        vec("ctrl.type_id", "MaximaCTRLMessage", "type 0 = raw X.509 DER pubkey", hex(ser(ctrlId)));
        vec("ctrl.type_id.nio", "NIOBody(MSG_MAXIMA_CTRL=9)", "framed body", hex(nioBody(9, ctrlId)));

        // TYPE_MLS carries the BARE Mx key with NO @host - the receiver appends
        // the observed socket address itself (MaximaManager: setMaximaMLS(pk+"@"+fullAddress)).
        // Confirmed against live traffic from a real node.
        MaximaCTRLMessage ctrlMls = new MaximaCTRLMessage(MaximaCTRLMessage.MAXIMACTRL_TYPE_MLS);
        String bareMls = Address.makeMinimaAddress(new MiniData(filler(162, 19)));
        ctrlMls.setData(new MiniData(bareMls.getBytes("UTF-8")));
        vec("ctrl.type_mls", "MaximaCTRLMessage",
                "type 1 = raw UTF-8 of a BARE Mx key (no @host)", hex(ser(ctrlMls)));

        // ---------- 13. MLS packets ----------
        MLSPacketSET set = new MLSPacketSET("Mx1234ABCD@1.2.3.4:9001");
        set.addValidPublicKey("0x30819F0001");
        set.addValidPublicKey("0x30819F0002");
        vec("mls.set", "MLSPacketSET", "identity, count(MiniNumber), validPubKeys[]", hex(ser(set)));

        MLSPacketGETReq req = new MLSPacketGETReq("0x30819F0001", "0xAABBCCDD");
        vec("mls.getreq", "MLSPacketGETReq", "publicKey, randomUID", hex(ser(req)));

        MLSPacketGETResp resp = new MLSPacketGETResp("0x30819F0001", "Mx1234ABCD@1.2.3.4:9001", "0xAABBCCDD");
        vec("mls.getresp", "MLSPacketGETResp",
                "WIRE order publicKey,currentAddress,randomUID (differs from ctor order)", hex(ser(resp)));

        // ---------- write ----------
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"Golden vectors generated from the REAL Minima reference implementation. ")
          .append("Our :core codec must reproduce every hex string byte-for-byte.\",\n");
        sb.append("  \"_source\": \"org.minima classes from minima.jar\",\n");
        sb.append("  \"vectors\": [\n");
        sb.append(String.join(",\n", OUT));
        sb.append("\n  ]\n}\n");

        String out = args.length > 0 ? args[0] : "golden-vectors.json";
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(out)))) {
            pw.print(sb);
        }
        System.out.println("Wrote " + OUT.size() + " vectors to " + out);
    }
}
