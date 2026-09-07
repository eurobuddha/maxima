package com.eurobuddha.maxima.core.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * What a person needs to type into their router: this machine's LAN address and the router's
 * own address. Pure JVM, no platform hooks - the same answer on a Mac, a Linux box or Android
 * (the Android app also has ConnectivityManager's gateway, which it passes as a hint).
 */
public final class LocalAddress {

    private LocalAddress() {
    }

    /** The first up, non-loopback, non-virtual IPv4 site-local address ("192.168.1.23"), or "". */
    public static String siteLocalIp() {
        String fallback = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ni.isVirtual() || ni.getName().startsWith("docker") || ni.getName().startsWith("br-")
                                || ni.getName().startsWith("utun") || ni.getName().startsWith("vmnet")) {
                            if (fallback.isEmpty()) {
                                fallback = ip;
                            }
                            continue;
                        }
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * The default gateway ("192.168.1.1"): asked of the OS (`ip route` on Linux/Android,
     * `route -n get default` on macOS), else the .1 convention on our own /24, else "".
     */
    public static String defaultGateway() {
        String g = run(new String[]{"ip", "route", "show", "default"}, "default via ");
        if (g.isEmpty()) {
            g = run(new String[]{"route", "-n", "get", "default"}, "gateway: ");
        }
        if (g.isEmpty()) {
            String lan = siteLocalIp();
            int dot = lan.lastIndexOf('.');
            if (dot > 0) {
                g = lan.substring(0, dot) + ".1";
            }
        }
        return g;
    }

    private static String run(String[] zCmd, String zAfter) {
        try {
            Process p = new ProcessBuilder(zCmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            for (String line : out.split("\n")) {
                int i = line.indexOf(zAfter);
                if (i >= 0) {
                    String rest = line.substring(i + zAfter.length()).trim();
                    int sp = rest.indexOf(' ');
                    String cand = sp > 0 ? rest.substring(0, sp) : rest;
                    if (cand.matches("[0-9.]+")) {
                        return cand;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
