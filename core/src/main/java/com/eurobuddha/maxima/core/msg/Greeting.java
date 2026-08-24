package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The P2P handshake message (NIO type 0).
 *
 * Sending this is REQUIRED on an outgoing connection if you want to receive
 * relayed Maxima traffic - the reference only raises MAXIMA_CONNECTED from its
 * greeting handler. Plain sending needs no greeting at all.
 *
 * Version negotiation is effectively absent: the only check is that a peer is
 * rejected iff {@code TEST_PARAMS != version.contains("TEST")}. So on mainnet
 * any version string without "TEST" is accepted.
 *
 * {@code extraData.port} is what determines the ip:port that becomes your host
 * identity string, so it matters more than it looks.
 */
public final class Greeting implements Streamable {

    private MiniString mVersion;
    /** Raw JSON text: {"welcome":..,"host":..,"port":..,"peers":[..]} */
    private MiniString mExtraData;
    private MiniNumber mTopBlock;
    private final List<MiniData> mChain = new ArrayList<>();

    public Greeting() {
        mVersion = new MiniString("");
        mExtraData = new MiniString("{}");
        mTopBlock = new MiniNumber(0);
    }

    public Greeting(String zVersion, String zExtraDataJson, long zTopBlock) {
        mVersion = new MiniString(zVersion);
        mExtraData = new MiniString(zExtraDataJson);
        mTopBlock = new MiniNumber(zTopBlock);
    }

    public String getVersion() {
        return mVersion.toString();
    }

    public String getExtraData() {
        return mExtraData.toString();
    }

    public MiniNumber getTopBlock() {
        return mTopBlock;
    }

    public List<MiniData> getChain() {
        return mChain;
    }

    /**
     * A greeting suitable for a comms-only peer: no chain, topBlock -1.
     *
     * {@code host} is OMITTED unless we genuinely know our public address.
     * A receiver treats extraData.host as an override of the address it dialled
     * (NIOMessage.java:342-344), so advertising a bind address of "0.0.0.0"
     * replaces a perfectly good public IP with a useless one - and classic then
     * rejects the whole host as internal, because its blocklist includes
     * anything starting "0." (MaximaManager.java:524-533). Measured against a
     * stock 1.0.46.8 node: it connected, then logged
     *   "Invalid IP for MAXIMA host ( is internal ) 0.0.0.0:9501"
     * and refused to use us. Saying nothing is strictly better than saying
     * something wrong: the client already knows the address it dialled.
     *
     * @param zHost our public host, or null/empty/0.0.0.0 to stay silent
     */
    public static Greeting commsOnly(String zVersion, String zHost, int zPort) {
        return commsOnly(zVersion, zHost, zPort, java.util.Collections.emptyList());
    }

    /**
     * A comms-only greeting that also shares relays we have VERIFIED, in the
     * exact shape classic uses for peer lists: a JSON array of "ip:port"
     * strings (InetSocketAddressIO.addressesListToJSONArray). This is how
     * relay-gossip discovery travels — no new message types, just the greeting
     * vocabulary classic already has.
     */
    public static Greeting commsOnly(String zVersion, String zHost, int zPort,
                                     List<String> zPeers) {
        return commsOnly(zVersion, zHost, zPort, zPeers, 0);
    }

    /**
     * As above, but also advertise our host CAPACITY - how many peers we are
     * willing to host as a relay. Rides as a {@code "cap"} key in the extraData
     * JSON, exactly like {@code host}/{@code port}/{@code peers}: classic reads
     * the keys it knows and ignores the rest, so this is invisible to a stock
     * node and never required for classic comms. Capacity is a MERIT input for
     * host selection (see {@code HostPool.HostRecord.score}), never a node type.
     *
     * @param zCapacity peers we will host; 0 (or less) omits the claim
     */
    public static Greeting commsOnly(String zVersion, String zHost, int zPort,
                                     List<String> zPeers, int zCapacity) {
        return commsOnly(zVersion, zHost, zPort, zPeers, zCapacity, false);
    }

    /**
     * As above, plus a {@code "pool":"true"} claim when this relay is an OPEN
     * staticMLS pool member (open-resolve on). Rides in the same extraData JSON
     * as {@code host}/{@code port}/{@code cap}/{@code peers}: a classic or older
     * node ignores the key, so it is invisible on the wire and never required.
     * A client reads {@link #poolOf} to auto-discover pool relays instead of a
     * central out-of-band list.
     *
     * @param zPool true if this relay open-resolves (public staticMLS pool host)
     */
    public static Greeting commsOnly(String zVersion, String zHost, int zPort,
                                     List<String> zPeers, int zCapacity, boolean zPool) {
        boolean known = zHost != null && !zHost.isEmpty()
                && !zHost.equals("0.0.0.0") && !zHost.equals("::");
        StringBuilder peers = new StringBuilder("[");
        for (int i = 0; i < zPeers.size(); i++) {
            if (i > 0) {
                peers.append(',');
            }
            peers.append('"').append(zPeers.get(i)).append('"');
        }
        peers.append(']');
        String json = "{\"welcome\":\"Maxima\""
                + (known ? ",\"host\":\"" + zHost + "\"" : "")
                + ",\"port\":\"" + zPort + "\""
                + (zCapacity > 0 ? ",\"cap\":\"" + zCapacity + "\"" : "")
                + (zPool ? ",\"pool\":\"true\"" : "")
                + ",\"peers\":" + peers + "}";
        return new Greeting(zVersion, json, -1);
    }

    /** True if the greeting advertises OPEN staticMLS pool membership
     *  ({@code "pool":"true"}). Absent (classic / older relay) = false. */
    public static boolean poolOf(String zExtraDataJson) {
        return "true".equals(flatValue(zExtraDataJson, "pool"));
    }

    /**
     * The "peers" array from a greeting's extra data — flat "ip:port" strings,
     * classic's wire shape. Anything malformed is skipped, never thrown.
     */
    public static List<String> peersOf(String zExtraDataJson) {
        List<String> out = new ArrayList<>();
        if (zExtraDataJson == null) {
            return out;
        }
        int k = zExtraDataJson.indexOf("\"peers\"");
        if (k < 0) {
            return out;
        }
        int open = zExtraDataJson.indexOf('[', k);
        int close = open < 0 ? -1 : zExtraDataJson.indexOf(']', open);
        if (open < 0 || close < 0) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([^\"]{1,64}:\\d{1,5})\"")
                .matcher(zExtraDataJson.substring(open, close + 1));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The "host" claim from a greeting's extra data, or "" if absent. */
    public static String hostOf(String zExtraDataJson) {
        return flatValue(zExtraDataJson, "host");
    }

    /** The "port" claim from a greeting's extra data, or -1 if absent/bad. */
    public static int portOf(String zExtraDataJson) {
        try {
            return Integer.parseInt(flatValue(zExtraDataJson, "port"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The "cap" (host capacity) claim from a greeting's extra data, or 0 if
     *  absent/bad. A classic node never sends it, so 0 = "unspecified" and the
     *  scorer applies a neutral capacity factor (never a penalty). */
    public static int capOf(String zExtraDataJson) {
        try {
            int c = Integer.parseInt(flatValue(zExtraDataJson, "cap"));
            return Math.max(0, c);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String flatValue(String zJson, String zKey) {
        if (zJson == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + zKey + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(zJson);
        return m.find() ? m.group(1) : "";
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mVersion.writeDataStream(zOut);
        mExtraData.writeDataStream(zOut);
        mTopBlock.writeDataStream(zOut);
        MiniNumber.writeToStream(zOut, mChain.size());
        for (MiniData id : mChain) {
            id.writeDataStream(zOut);
        }
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mVersion = MiniString.readFromStream(zIn);
        mExtraData = MiniString.readFromStream(zIn);
        mTopBlock = MiniNumber.readFromStream(zIn);
        mChain.clear();
        int len = MiniNumber.readFromStream(zIn).getAsInt();
        for (int i = 0; i < len; i++) {
            mChain.add(MiniData.readFromStream(zIn));
        }
    }

    public static Greeting readFromStream(DataInputStream zIn) throws IOException {
        Greeting g = new Greeting();
        g.readDataStream(zIn);
        return g;
    }

    public static Greeting fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new Greeting(), zData);
    }
}
