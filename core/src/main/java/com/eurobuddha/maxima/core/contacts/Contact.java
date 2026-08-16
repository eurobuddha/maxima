package com.eurobuddha.maxima.core.contacts;

import com.eurobuddha.maxima.core.rpc.Capabilities;

import java.util.ArrayList;
import java.util.List;

/** One peer we can reach, and everything we know about reaching them. */
public final class Contact {

    /** Their Maxima identity key, uppercase 0x hex. The stable id. */
    public String publicKey;

    /** Where WE send to them. Classic keeps one; we keep all of them. */
    public final List<String> addresses = new ArrayList<>();

    /** Where THEY send to us - the address we last told them about. */
    public String myAddress = "";

    public String name = "noname";
    public String icon = "0x00";
    public String minimaAddress = "";
    public String mls = "";

    /** Display-only chain agreement fields. Zeros when running chainless. */
    public String topBlock = "0";
    public String checkBlock = "0";
    public String checkHash = "0x00";

    /** What they can do beyond classic. Empty set == a classic peer. */
    public Capabilities capabilities = Capabilities.none();

    /** The peer's self-declared node kind: "core" (a full Minima/desktop node
     *  running our maxima), "" for our phone app, absent for classic peers.
     *  A classic-invisible extradata key, like {@link Capabilities}.
     *  volatile: written by the pump/self-heal threads, read by the UI thread. */
    public volatile String kind = "";

    /** volatile: bumped by the pump + self-heal threads, read by the UI poll. */
    public volatile long lastSeen;

    public Contact() {
    }

    public Contact(String zPublicKey) {
        publicKey = zPublicKey;
    }

    /** The address to try first. */
    public String primaryAddress() {
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    /**
     * Replace the known address set.
     * A multi-homed peer sends several; a classic peer sends exactly one.
     */
    public void setAddresses(List<String> zAddresses) {
        addresses.clear();
        for (String a : zAddresses) {
            if (a != null && !a.isEmpty() && !addresses.contains(a)) {
                addresses.add(a);
            }
        }
    }

    public boolean isClassic() {
        return capabilities.isClassic();
    }

    @Override
    public String toString() {
        return name + " [" + (publicKey == null ? "?" : publicKey.substring(0, Math.min(14, publicKey.length())))
                + "...] " + addresses.size() + " addr, caps=" + capabilities;
    }
}
