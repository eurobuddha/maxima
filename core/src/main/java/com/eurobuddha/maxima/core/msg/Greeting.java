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
        boolean known = zHost != null && !zHost.isEmpty()
                && !zHost.equals("0.0.0.0") && !zHost.equals("::");
        String json = "{\"welcome\":\"Maxima\""
                + (known ? ",\"host\":\"" + zHost + "\"" : "")
                + ",\"port\":\"" + zPort + "\",\"peers\":[]}";
        return new Greeting(zVersion, json, -1);
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
