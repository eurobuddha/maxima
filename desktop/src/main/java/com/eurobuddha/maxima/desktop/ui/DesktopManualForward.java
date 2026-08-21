package com.eurobuddha.maxima.desktop.ui;

import java.util.prefs.Preferences;

/**
 * Manual port-forward settings for the desktop — the same feature the phone's
 * ManualForward gives: for a router that won't open a port automatically (no
 * UPnP/NAT-PMP), the user adds a forward rule by hand and tells us their public
 * IP + the forwarded port. DesktopNode then pins the direct listener to that
 * port, PROVES the address from outside (a relay dials it back via
 * ReachabilityManager.setManual), and advertises it — never on hope alone.
 */
public final class DesktopManualForward {

    private static final String NODE = "com/eurobuddha/maxima/desktop/manualfwd";
    public static final int DEFAULT_PORT = DesktopNode.DIRECT_PORT;   // 9536, memorable + stable

    private DesktopManualForward() { }

    private static Preferences p() { return Preferences.userRoot().node(NODE); }

    public static boolean enabled() { return p().getBoolean("enabled", false); }

    public static String publicIp() { return p().get("ip", ""); }

    public static int port() { return p().getInt("port", DEFAULT_PORT); }

    public static void set(boolean enabled, String ip, int port) {
        p().putBoolean("enabled", enabled);
        p().put("ip", ip == null ? "" : ip.trim());
        p().putInt("port", port > 0 && port < 65536 ? port : DEFAULT_PORT);
    }

    public static void setEnabled(boolean enabled) { p().putBoolean("enabled", enabled); }

    /** The LAN IPv4 a router rule forwards to (best-effort). */
    public static String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }
}
