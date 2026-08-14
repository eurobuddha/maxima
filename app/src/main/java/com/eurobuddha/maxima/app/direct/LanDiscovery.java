package com.eurobuddha.maxima.app.direct;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.core.MaximaNode;

import java.nio.charset.StandardCharsets;

/**
 * Tier 2 LAN discovery (phase E): find contacts on the same network and talk to
 * them directly, with no relay and no internet.
 *
 * Two phones on one Wi-Fi advertise {@code _maxima._tcp} via multicast DNS
 * (Android's {@link NsdManager}). Each carries its identity in a TXT record and
 * points at its direct endpoint's port. When we discover a service whose
 * identity matches one of our CONTACTS, we hand the node a LAN address for them;
 * {@code sendToContact} then tries it FIRST, so a message to someone in the same
 * room takes the local path.
 *
 * This is a pure bonus and never load-bearing: a LAN address that goes stale
 * (they left the network) just fails and the send falls through to the relays.
 * NSD is also famously flaky across OEMs, so every callback here is defensive
 * and a failure only means "no LAN shortcut", never a broken transport.
 *
 * Privacy: a LAN address is an RFC1918 address, which classic Maxima rejects
 * outright, so it is only ever usable between our own builds. We advertise our
 * identity on the local segment only - the same information a contact already
 * holds - and match discovered peers against contacts we already have.
 */
public final class LanDiscovery {

    private static final String SERVICE_TYPE = "_maxima._tcp.";
    private static final String TXT_IDENTITY = "id";

    private final Context mCtx;
    private final MaximaNode mNode;

    private NsdManager mNsd;
    private NsdManager.RegistrationListener mReg;
    private NsdManager.DiscoveryListener mDisc;
    private volatile boolean mRunning;
    private String mServiceName = "";

    public LanDiscovery(Context zCtx, MaximaNode zNode) {
        mCtx = zCtx;
        mNode = zNode;
    }

    /**
     * Advertise ourselves on zPort and start discovering peers.
     *
     * @param zPort our direct endpoint's LOCAL port (the LAN reaches it directly;
     *              no port mapping is involved on the same segment)
     */
    public synchronized void start(int zPort) {
        if (mRunning || zPort <= 0) {
            return;
        }
        try {
            mNsd = (NsdManager) mCtx.getSystemService(Context.NSD_SERVICE);
            if (mNsd == null) {
                return;
            }
            register(zPort);
            discover();
            mRunning = true;
        } catch (Exception e) {
            EventLog.add("LAN discovery unavailable: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        mRunning = false;
        if (mNsd == null) {
            return;
        }
        try {
            if (mReg != null) {
                mNsd.unregisterService(mReg);
            }
        } catch (Exception ignored) {
        }
        try {
            if (mDisc != null) {
                mNsd.stopServiceDiscovery(mDisc);
            }
        } catch (Exception ignored) {
        }
        mReg = null;
        mDisc = null;
    }

    // ---------------------------------------------------------------

    private void register(int zPort) {
        NsdServiceInfo info = new NsdServiceInfo();
        // A stable-ish, unique-ish instance name. Collisions are auto-resolved
        // by NsdManager appending a suffix, which is fine - we match on the TXT
        // identity, never the display name.
        info.setServiceName("maxima-" + shortId());
        info.setServiceType(SERVICE_TYPE);
        info.setPort(zPort);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            info.setAttribute(TXT_IDENTITY, mNode.identity().publicKeyHex());
        }

        mReg = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo s) {
                mServiceName = s.getServiceName();
            }

            public void onRegistrationFailed(NsdServiceInfo s, int err) {
            }

            public void onServiceUnregistered(NsdServiceInfo s) {
            }

            public void onUnregistrationFailed(NsdServiceInfo s, int err) {
            }
        };
        mNsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, mReg);
    }

    private void discover() {
        mDisc = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {
            }

            public void onServiceFound(NsdServiceInfo s) {
                if (!SERVICE_TYPE.equals(s.getServiceType() == null
                        ? "" : s.getServiceType())
                        && !s.getServiceType().contains("_maxima._tcp")) {
                    return;
                }
                if (s.getServiceName().equals(mServiceName)) {
                    return;   // ourselves
                }
                resolve(s);
            }

            public void onServiceLost(NsdServiceInfo s) {
                // We do not know the identity from a lost event alone; the LAN
                // address will simply fail and the send falls through to relays.
                // A stale LAN entry costs one failed attempt, not a lost message.
            }

            public void onDiscoveryStopped(String type) {
            }

            public void onStartDiscoveryFailed(String type, int err) {
            }

            public void onStopDiscoveryFailed(String type, int err) {
            }
        };
        mNsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDisc);
    }

    private void resolve(NsdServiceInfo zService) {
        try {
            mNsd.resolveService(zService, new NsdManager.ResolveListener() {
                public void onServiceResolved(NsdServiceInfo s) {
                    try {
                        byte[] idBytes = null;
                        if (s.getAttributes() != null) {
                            idBytes = s.getAttributes().get(TXT_IDENTITY);
                        }
                        if (idBytes == null || s.getHost() == null) {
                            return;
                        }
                        String peerId = new String(idBytes, StandardCharsets.UTF_8);
                        String hostPort = s.getHost().getHostAddress() + ":" + s.getPort();
                        // Only matters if they are a contact; the node ignores
                        // the rest. Tried first by sendToContact from now on.
                        if (mNode.contact(peerId) != null) {
                            mNode.noteLanPeer(peerId, hostPort);
                            EventLog.add("LAN peer found: " + mNode.contact(peerId).name
                                    + " at " + hostPort);
                        }
                    } catch (Exception ignored) {
                    }
                }

                public void onResolveFailed(NsdServiceInfo s, int err) {
                    // Common and harmless; try again next time it is seen.
                }
            });
        } catch (Exception ignored) {
            // resolveService can throw IllegalArgumentException if a resolve is
            // already in flight on some OEMs. Ignore - we will see it again.
        }
    }

    private String shortId() {
        String hex = mNode.identity().publicKeyHex();
        return hex.length() > 10 ? hex.substring(2, 10) : hex;
    }
}
