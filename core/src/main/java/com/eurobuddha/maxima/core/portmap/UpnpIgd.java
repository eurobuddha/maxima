package com.eurobuddha.maxima.core.portmap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * UPnP IGD client: SSDP discovery plus the two SOAP calls we need.
 *
 * Self-contained on purpose. A UPnP library would drag in megabytes to do what
 * is, for our narrow use, three steps:
 *
 *   1. multicast an SSDP M-SEARCH and collect LOCATION headers
 *   2. GET the device description, find the WANIPConnection control URL
 *   3. POST AddPortMapping / DeletePortMapping / GetExternalIPAddress
 *
 * Trust model: the gateway is OUR router but its replies still cross the
 * network unauthenticated, so every parse below is bounded - fixed-size reads,
 * no entity expansion (we never feed the XML to a real parser), and value
 * extraction by tag scan only. A hostile answer can waste one attempt; it
 * cannot allocate unbounded memory or reach anything else.
 */
public final class UpnpIgd {

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    /** Both device types answer AddPortMapping; search for the root. */
    private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";

    private static final String[] SERVICE_TYPES = {
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };

    /** Never read more than this from any HTTP body. */
    private static final int MAX_BODY = 256 * 1024;

    private final int mTimeoutMs;

    /** The discovered control endpoint. */
    private String mControlUrl;
    private String mServiceType;

    public UpnpIgd(int zTimeoutMs) {
        mTimeoutMs = zTimeoutMs;
    }

    /**
     * SSDP discovery followed by description fetch.
     *
     * @return true if a usable WAN*Connection control URL was found
     */
    public boolean discover() {
        String location = ssdpSearch(null);
        if (location == null) {
            return false;
        }
        return readDescription(location);
    }

    /**
     * UNICAST discovery, aimed at a suspected gateway.
     *
     * Two reasons this exists. Multicast SSDP cannot cross a NAT layer, so
     * chained mapping (phone -> mesh router -> ISP router) can only find the
     * OUTER gateway by asking it directly. And on Android, multicast is
     * disabled without a MulticastLock and flaky with one - a unicast
     * M-SEARCH, which MiniUPnPd and friends answer happily, needs neither.
     */
    public boolean discoverUnicast(String zGatewayIp) {
        String location = ssdpSearch(zGatewayIp);
        if (location == null) {
            return false;
        }
        return readDescription(location);
    }

    /** For tests: skip SSDP and point straight at a description URL. */
    public boolean discoverAt(String zLocation) {
        return readDescription(zLocation);
    }

    public String controlUrl() {
        return mControlUrl;
    }

    /** The router's external address, or null. */
    public String externalIp() {
        String resp = soap("GetExternalIPAddress", "");
        return resp == null ? null : tagValue(resp, "NewExternalIPAddress");
    }

    /**
     * Add a TCP mapping. UPnP has no "suggested port" negotiation - the router
     * either takes exactly what you ask for or errors (718 ConflictInMapping),
     * so the caller retries with different external ports.
     */
    public boolean addTcpMapping(String zInternalIp, int zInternalPort,
                                 int zExternalPort, int zLeaseSeconds,
                                 String zDescription) {
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + zExternalPort + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>"
                + "<NewInternalPort>" + zInternalPort + "</NewInternalPort>"
                + "<NewInternalClient>" + zInternalIp + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + zDescription + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>" + zLeaseSeconds + "</NewLeaseDuration>";
        return soap("AddPortMapping", args) != null;
    }

    public boolean deleteTcpMapping(int zExternalPort) {
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + zExternalPort + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>";
        return soap("DeletePortMapping", args) != null;
    }

    // ---------------------------------------------------------------
    // SSDP
    // ---------------------------------------------------------------

    private String ssdpSearch(String zUnicastHost) {
        String host = zUnicastHost == null ? SSDP_ADDR : zUnicastHost;
        String msearch = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + host + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + SEARCH_TARGET + "\r\n"
                + "\r\n";
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(mTimeoutMs);
            byte[] req = msearch.getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(req, req.length,
                    new InetSocketAddress(host, SSDP_PORT)));

            // First answer wins. A home network has one gateway; racing
            // several is a datacentre problem we do not have.
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            String resp = new String(buf, 0, p.getLength(), StandardCharsets.US_ASCII);
            for (String line : resp.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim()
                        .equalsIgnoreCase("LOCATION")) {
                    return line.substring(colon + 1).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------
    // description + SOAP
    // ---------------------------------------------------------------

    private boolean readDescription(String zLocation) {
        String xml = httpGet(zLocation);
        if (xml == null) {
            return false;
        }
        // The description lists services as <serviceType>..</serviceType> +
        // <controlURL>..</controlURL> pairs. Find OUR service type, then take
        // the controlURL from the same <service> block.
        for (String type : SERVICE_TYPES) {
            int at = xml.indexOf(type);
            if (at < 0) {
                continue;
            }
            int blockEnd = xml.indexOf("</service>", at);
            if (blockEnd < 0) {
                blockEnd = xml.length();
            }
            String block = xml.substring(at, blockEnd);
            String control = tagValue(block, "controlURL");
            if (control == null) {
                continue;
            }
            mServiceType = type;
            mControlUrl = control.startsWith("http")
                    ? control
                    : baseOf(zLocation) + (control.startsWith("/") ? "" : "/") + control;
            return true;
        }
        return false;
    }

    private String soap(String zAction, String zArgs) {
        if (mControlUrl == null) {
            return null;
        }
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + zAction + " xmlns:u=\"" + mServiceType + "\">"
                + zArgs
                + "</u:" + zAction + "></s:Body></s:Envelope>";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(mControlUrl).openConnection();
            c.setConnectTimeout(mTimeoutMs);
            c.setReadTimeout(mTimeoutMs);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction",
                    "\"" + mServiceType + "#" + zAction + "\"");
            c.setDoOutput(true);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) {
                out.write(payload);
            }
            int code = c.getResponseCode();
            String resp = readBounded(code >= 400 ? c.getErrorStream() : c.getInputStream());
            return code == 200 ? resp : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String zUrl) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(zUrl).openConnection();
            c.setConnectTimeout(mTimeoutMs);
            c.setReadTimeout(mTimeoutMs);
            return c.getResponseCode() == 200 ? readBounded(c.getInputStream()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readBounded(InputStream zIn) throws Exception {
        if (zIn == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zIn.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_BODY) {
                // A description document is a few KB. Anything bigger is
                // either broken or hostile; stop reading either way.
                break;
            }
        }
        zIn.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Value of the first {@code <tag>...</tag>}, tag-scan only - no parser. */
    static String tagValue(String zXml, String zTag) {
        int open = zXml.indexOf("<" + zTag + ">");
        if (open < 0) {
            return null;
        }
        int start = open + zTag.length() + 2;
        int close = zXml.indexOf("</" + zTag + ">", start);
        if (close < 0 || close - start > 512) {
            return null;
        }
        return zXml.substring(start, close).trim();
    }

    /** {@code http://192.168.1.1:5000/desc.xml -> http://192.168.1.1:5000} */
    static String baseOf(String zUrl) {
        int schemeEnd = zUrl.indexOf("://");
        int pathStart = zUrl.indexOf('/', schemeEnd < 0 ? 0 : schemeEnd + 3);
        return pathStart < 0 ? zUrl : zUrl.substring(0, pathStart);
    }
}
