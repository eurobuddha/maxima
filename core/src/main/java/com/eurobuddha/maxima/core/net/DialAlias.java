package com.eurobuddha.maxima.core.net;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where to actually dial for a logical host:port. One use: a node's OWN relay, known to the world
 * by its public address, that the node itself must reach over loopback - most home routers do not
 * hairpin, so dialling your own public IP from inside times out (seen live 2026-09-07) while the
 * outside world connects fine. The logical address stays the pool key and the directory anchor
 * ({@code Mx…@public:port}); only the socket goes elsewhere. Empty by default: nothing changes.
 */
/*
 * Process-global on purpose: an in-process relay is "this process" for EVERY account the JVM
 * hosts (a --tenants host as much as a single node), so one alias table serves them all.
 */
public final class DialAlias {

    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();

    private DialAlias() {
    }

    /** Dial {@code zActualHostPort} whenever {@code zHostPort} is asked for. */
    public static void set(String zHostPort, String zActualHostPort) {
        if (zHostPort == null || zActualHostPort == null || zHostPort.equals(zActualHostPort)) {
            return;
        }
        ALIASES.put(zHostPort.trim(), zActualHostPort.trim());
    }

    public static void clear(String zHostPort) {
        if (zHostPort != null) {
            ALIASES.remove(zHostPort.trim());
        }
    }

    /** The socket address to connect to for this logical host and port. */
    public static InetSocketAddress resolve(String zHost, int zPort) {
        String actual = ALIASES.get(zHost + ":" + zPort);
        if (actual == null) {
            return new InetSocketAddress(zHost, zPort);
        }
        int c = actual.lastIndexOf(':');
        try {
            return new InetSocketAddress(actual.substring(0, c), Integer.parseInt(actual.substring(c + 1)));
        } catch (Exception e) {
            return new InetSocketAddress(zHost, zPort);
        }
    }
}
