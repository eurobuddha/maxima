package com.eurobuddha.maxima.app.wallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * WHERE wallet commands go — the FreezePeach synergy, preserved:
 *
 *   minimaCore installed and paired  ->  the LOCAL node over broadcast IPC
 *   otherwise                        ->  the hosted gateway (/cmd proxy),
 *                                        exactly as FreezePeach always worked
 *
 * Signing NEVER moves: the seed and the Winternitz walk stay on this device;
 * both backends only ever see reads and the pre-signed txnimport/txnbasics/
 * txnpost relay. A user with just Maxima has a complete wallet; installing
 * minimaCore later upgrades the publish path automatically, same phrase, same
 * key space (index 1000 - see MaximaWallet).
 */
public final class WalletPublisher {

    public interface Cb {
        void onResult(JSONObject r);

        void onError(String msg);
    }

    /**
     * The Parlons FLEET — MegaMMR Parlons Nodes, each serving a read+relay-only {@code /cmd}
     * gateway behind TLS. Tried in order with automatic failover (see GatewayNode). The token is
     * a shared-public credential, deliberately shipped: the gateway cannot move funds (allow-list
     * of reads + relay of pre-signed txns), it only keeps anonymous scrapers off the nodes.
     */
    static final String[] FLEET_GATEWAY_URLS = {
            "https://store.eurobuddha.com/parlons-node/cmd",   // sally      - Amsterdam, NL
            "https://eurobuddha.com/parlons-node/cmd",         // eurobuddha - Helsinki, FI
    };
    static final String FLEET_GATEWAY_TOKEN =
            "9cb300300968390a91c2b998720b1385f6851242e48ab3021e724536ac9d4468";
    /** The first fleet node doubles as "the default" the Wallet-node sheet displays. */
    static final String DEFAULT_GATEWAY_URL = FLEET_GATEWAY_URLS[0];
    static final String DEFAULT_GATEWAY_TOKEN = FLEET_GATEWAY_TOKEN;
    /** The pre-0.6.49 default (the hosted proxy on maxlite). A phone that once "reset" to it
     *  stored this literal string; treat it as "the default" so such phones follow the fleet. */
    static final String LEGACY_GATEWAY_URL = "https://relay.privateprivate.org/cmd";

    private static final String CORE_PKG = "org.minimarex.minimacore";

    /**
     * Gateways DISCOVERED with the relays (a Parlons Node's cape advertises its node's gateway
     * in its greeting - see PeerDiscovery.gateways()). Set once by the engine host; null = none.
     * With it the fleet's gateway capacity grows with its nodes; the two compiled-in URLs stay
     * as the floor. Trust model, same as for relays: a discovered gateway is a fleet node's;
     * signing never leaves the phone, so the worst a bad one can do is a wrong read or a dropped
     * relay, which the next gateway corrects.
     */
    private static volatile java.util.concurrent.Callable<java.util.List<GatewayNode.Endpoint>> sDiscovered;

    public static void setDiscoveredGateways(
            java.util.concurrent.Callable<java.util.List<GatewayNode.Endpoint>> zSource) {
        sDiscovered = zSource;
    }

    /**
     * The fleet as this wallet sees it: discovered gateways plus the compiled-in floor, in a
     * RANDOM order that the transport then sticks with - so a population of phones spreads over
     * every gateway instead of all starting on the first compiled-in one. (Coins imported and
     * transactions built on a gateway live on THAT gateway, which is why the order is fixed per
     * publisher and only failover moves it.)
     */
    static java.util.List<GatewayNode.Endpoint> fleetEndpoints() {
        java.util.LinkedHashMap<String, GatewayNode.Endpoint> all = new java.util.LinkedHashMap<>();
        java.util.concurrent.Callable<java.util.List<GatewayNode.Endpoint>> src = sDiscovered;
        if (src != null) {
            try {
                for (GatewayNode.Endpoint e : src.call()) {
                    if (e != null && e.usable()) all.put(e.url, e);
                }
            } catch (Exception ignored) {
            }
        }
        for (String u : FLEET_GATEWAY_URLS) {
            all.putIfAbsent(u, new GatewayNode.Endpoint(u, FLEET_GATEWAY_TOKEN));
        }
        java.util.List<GatewayNode.Endpoint> eps = new java.util.ArrayList<>(all.values());
        java.util.Collections.shuffle(eps);
        return eps;
    }

    private final Context mCtx;
    private final GatewayNode mGateway;
    private final NodeLink mNode;
    /** Our wallet's hex address, learned in {@link #prepare}. Needed for node-side reads. */
    private volatile String mAddr = null;
    /** True once the paired node has our address tracked AND every historic coin
     *  back-filled into its tracked set — only then are reads/publish safe to route
     *  to the node (before that a node read would under-report the balance). */
    private volatile boolean mNodeReady = false;

    public WalletPublisher(Context zCtx, NodeLink zNodeOrNull) {
        mCtx = zCtx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        String url = gatewayUrl(zCtx);
        if (usesDefaultGateway(zCtx)) {
            // The fleet, with failover: discovered gateways + the compiled-in floor, random start.
            // A user-configured node is a single fixed endpoint.
            mGateway = new GatewayNode(fleetEndpoints(), main);
        } else {
            mGateway = new GatewayNode(url, gatewayToken(zCtx), main);
        }
        mNode = zNodeOrNull;
    }

    /** True when the wallet follows the shipped default (the fleet) rather than a user's own node. */
    public static boolean usesDefaultGateway(Context zCtx) {
        String url = gatewayUrl(zCtx);
        return url.isEmpty() || DEFAULT_GATEWAY_URL.equals(url) || LEGACY_GATEWAY_URL.equals(url);
    }

    /** The gateway URL in use right now (the fleet node that last answered, or the user's node). */
    public String activeGatewayUrl() {
        return mGateway.currentUrl();
    }

    /** Is minimaCore installed on this phone at all? */
    public static boolean coreInstalled(Context zCtx) {
        try {
            zCtx.getPackageManager().getPackageInfo(CORE_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Which backend answers right now (for the UI). */
    public String backendName() {
        return useNode() ? "local minimaCore node" : "gateway";
    }

    /** minimaCore installed AND paired/enabled right now. */
    private boolean usingCore() {
        return mNode != null && mNode.isEnabled();
    }

    /** Use the paired node for wallet ops: it's enabled AND its tracked set is prepared
     *  (address tracked + historic coins back-filled). Falls back to the gateway otherwise,
     *  so a node that goes away mid-session, or one we couldn't fully back-fill, never
     *  under-reports the balance. */
    private boolean useNode() {
        return usingCore() && mNodeReady;
    }

    // ---- the uniform surface ----

    public void cmd(String zCommand, Cb zCb) {
        if (usingCore()) {
            mNode.cmd(zCommand, wrap(zCb));
        } else {
            mGateway.cmd(zCommand, wrapG(zCb));
        }
    }

    /**
     * Run a command ALWAYS against the hosted MegaMMR gateway, never a paired local
     * node. The whole wallet — balance/coins reads, the funded-before-tracked coin
     * backfill, and the publish relay — depends on a MegaMMR node: a user's own
     * Minima node is virtually never a MegaMMR node, so {@code megammr:true} reads
     * come back EMPTY on it (the "balance shows 0" bug), and coins imported on the
     * gateway can only be spent by publishing through that SAME gateway. This is
     * exactly how FreezePeach works — one hosted MegaMMR node serves any address —
     * so the wallet path pins to the gateway and only chat/IPC use the paired node.
     */
    public void gcmd(String zCommand, Cb zCb) {
        mGateway.cmd(zCommand, wrapG(zCb));
    }

    public void balance(String zAddress, Cb zCb) {
        if (useNode()) {
            mNode.cmd("balance address:" + zAddress,
                    wrapNodeOrGateway("balance megammr:true address:" + zAddress, zCb));
        } else {
            gcmd("balance megammr:true address:" + zAddress, zCb);
        }
    }

    public void coins(String zAddress, Cb zCb) {
        if (useNode()) {
            mNode.cmd("coins address:" + zAddress,
                    wrapNodeOrGateway("coins megammr:true address:" + zAddress, zCb));
        } else {
            gcmd("coins megammr:true address:" + zAddress, zCb);
        }
    }

    /** A node callback that, on node error, transparently retries the equivalent command on
     *  the gateway — so a flaky or vanished node never breaks a read. */
    private NodeLink.Cb wrapNodeOrGateway(final String zGatewayCmd, final Cb zCb) {
        return new NodeLink.Cb() {
            public void onResult(JSONObject r) { zCb.onResult(r); }
            public void onError(String m)      { gcmd(zGatewayCmd, zCb); }
        };
    }

    /** Legacy entry point (gateway-track only). Prefer {@link #prepare}. */
    public void trackScript(String zScript, Cb zCb) {
        gcmd("newscript trackall:true script:\"" + zScript + "\"", zCb);
    }

    /**
     * Register our address and, when a node is paired, prepare it for node-side wallet ops:
     *   1. {@code newscript trackall:true} — forward tracking on the node,
     *   2. back-fill — pull our existing coins from the gateway (megammr) and
     *      {@code coinimport track:true} each into the node's tracked set, so node
     *      balance/coins (and later publish) see the FULL wallet. A plain node cannot
     *      discover coins funded before it started tracking; this closes that gap.
     * {@link #mNodeReady} flips true only when EVERY historic coin imports — otherwise
     * reads stay on the gateway so the balance is never under-reported. With no node paired
     * this is just the gateway track (unchanged behaviour).
     */
    public void prepare(String zScript, String zHexAddr, Cb zCb) {
        mAddr = zHexAddr;
        // Keep the gateway aware of our script too (idempotent) so the fallback path works.
        mGateway.trackScript(zScript, new GatewayNode.Cb() {
            public void onResult(JSONObject r) {}
            public void onError(String m) {}
        });
        if (!usingCore()) {
            gcmd("newscript trackall:true script:\"" + zScript + "\"", zCb);
            return;
        }
        mNode.cmd("newscript trackall:true script:\"" + zScript + "\"", new NodeLink.Cb() {
            public void onResult(JSONObject r) { reconcile(zHexAddr, zCb); }
            public void onError(String m)      { if (zCb != null) zCb.onError(m); } // stay on gateway
        });
    }

    /**
     * Decide whether the node is ready to serve the wallet — NODE-FIRST, so a down gateway
     * never blocks it (the resilience requirement). Read the node's OWN tracked coins, then:
     *   - gateway reachable  → import only the coins the node is missing; ready iff all land.
     *   - gateway unreachable → trust the node's tracked set and go ready anyway.
     * If the node read itself fails we stay on the gateway (never under-report).
     */
    private void reconcile(final String zHexAddr, final Cb zCb) {
        mNode.cmd("coins address:" + zHexAddr, new NodeLink.Cb() {
            public void onResult(JSONObject nodeCoins) {
                final java.util.Set<String> have = idsOf(nodeCoins);
                mGateway.coins(zHexAddr, new GatewayNode.Cb() {
                    public void onResult(JSONObject gw) {
                        java.util.List<String> missing = new java.util.ArrayList<>();
                        for (String id : idsOf(gw)) if (!have.contains(id)) missing.add(id);
                        importNext(missing, 0, new int[]{0}, zCb);   // ready iff every missing imports
                    }
                    public void onError(String m) {
                        // Gateway down — trust what the node already tracks; use the node.
                        mNodeReady = true;
                        if (zCb != null) zCb.onResult(new JSONObject());
                    }
                });
            }
            public void onError(String m) {
                if (zCb != null) zCb.onError("node coins: " + m);   // stay on gateway
            }
        });
    }

    /** Coin-ids present in a {coins} response (works for node and gateway replies alike). */
    private static java.util.Set<String> idsOf(JSONObject coinsResp) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            org.json.JSONArray arr = coinsResp.optJSONArray("response");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String cid = arr.getJSONObject(i).optString("coinid", "");
                    if (!cid.isEmpty()) out.add(cid);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void importNext(final java.util.List<String> zIds, final int zI,
                            final int[] zOk, final Cb zCb) {
        if (zI >= zIds.size()) {
            mNodeReady = (zOk[0] == zIds.size());   // ready only if every historic coin landed
            if (zCb != null) {
                if (mNodeReady) zCb.onResult(new JSONObject());
                else zCb.onError("backfill incomplete: " + zOk[0] + "/" + zIds.size());
            }
            return;
        }
        mGateway.coinExport(zIds.get(zI), new GatewayNode.Cb() {
            public void onResult(JSONObject ex) {
                String blob = blobOf(ex);
                if (blob == null || blob.isEmpty()) { importNext(zIds, zI + 1, zOk, zCb); return; }
                mNode.cmd("coinimport data:" + blob + " track:true", new NodeLink.Cb() {
                    public void onResult(JSONObject r) {
                        if (r.optBoolean("status", false)) zOk[0]++;
                        importNext(zIds, zI + 1, zOk, zCb);
                    }
                    public void onError(String m) { importNext(zIds, zI + 1, zOk, zCb); }
                });
            }
            public void onError(String m) { importNext(zIds, zI + 1, zOk, zCb); }
        });
    }

    /** Extract the coinexport blob (0x…) from a gateway response, tolerating a bare-string
     *  response and a nested {@code {response:{data:…}}} shape. */
    private static String blobOf(JSONObject ex) {
        Object resp = ex.opt("response");
        if (resp instanceof String) return ((String) resp).trim();
        JSONObject ro = ex.optJSONObject("response");
        if (ro != null) {
            String d = ro.optString("data", ro.optString("coin", ""));
            if (!d.isEmpty()) return d.trim();
        }
        return null;
    }

    /**
     * Publish a LOCALLY-SIGNED txn: txnimport -> txnbasics -> txnpost. Routes to the paired
     * node when it's prepared — {@link #prepare}/{@link #reconcile} have imported our coins
     * (and their MMR proofs) into the node's tracked set, so {@code txnbasics} can attach them
     * there. Otherwise the hosted gateway. Signing already happened on-device; both backends
     * only relay. Every failure path {@code txndelete}s so a half-built txn can't be re-spent.
     */
    public void publish(String zImportCmd, String zId, String zPostCmd, Cb zCb) {
        if (useNode()) {
            nodePublish(zImportCmd, zId, zPostCmd, zCb);
        } else {
            mGateway.publish(zImportCmd, zId, zPostCmd, wrapG(zCb));
        }
    }

    /** txnimport -> txnbasics -> txnpost through the paired node; txndelete on any failure. */
    private void nodePublish(final String zImportCmd, final String zId,
                             final String zPostCmd, final Cb zCb) {
        mNode.cmd(zImportCmd, new NodeLink.Cb() {
            public void onResult(JSONObject r1) {
                if (!r1.optBoolean("status", false)) {
                    nodeFail(zId, "txnimport: " + r1.optString("error", r1.toString()), zCb); return;
                }
                mNode.cmd("txnbasics id:" + zId, new NodeLink.Cb() {
                    public void onResult(JSONObject r2) {
                        if (!r2.optBoolean("status", false)) {
                            nodeFail(zId, "txnbasics: " + r2.optString("error", r2.toString()), zCb); return;
                        }
                        mNode.cmd(zPostCmd, new NodeLink.Cb() {
                            public void onResult(JSONObject r3) {
                                if (!r3.optBoolean("status", false)) {
                                    nodeFail(zId, "txnpost: " + r3.optString("error", r3.toString()), zCb); return;
                                }
                                if (zCb != null) zCb.onResult(r3);
                            }
                            public void onError(String m) { nodeFail(zId, m, zCb); }
                        });
                    }
                    public void onError(String m) { nodeFail(zId, m, zCb); }
                });
            }
            public void onError(String m) { nodeFail(zId, m, zCb); }
        });
    }

    /** Clean the half-built txn off the node, THEN report the failure. */
    private void nodeFail(final String zId, final String zMsg, final Cb zCb) {
        mNode.cmd("txndelete id:" + zId, new NodeLink.Cb() {
            public void onResult(JSONObject r) { if (zCb != null) zCb.onError(zMsg); }
            public void onError(String m)      { if (zCb != null) zCb.onError(zMsg); }
        });
    }

    // ---- adapters between the two backends' callback shapes ----

    private interface ROnly {
        void ok(JSONObject r);
    }

    private NodeLink.Cb wrap(ROnly zOk, Cb zErrTo) {
        return new NodeLink.Cb() {
            @Override
            public void onResult(JSONObject r) {
                zOk.ok(r);
            }

            @Override
            public void onError(String m) {
                zErrTo.onError(m);
            }
        };
    }

    private NodeLink.Cb wrap(Cb zCb) {
        return new NodeLink.Cb() {
            @Override
            public void onResult(JSONObject r) {
                zCb.onResult(r);
            }

            @Override
            public void onError(String m) {
                zCb.onError(m);
            }
        };
    }

    private GatewayNode.Cb wrapG(Cb zCb) {
        return new GatewayNode.Cb() {
            @Override
            public void onResult(JSONObject r) {
                zCb.onResult(r);
            }

            @Override
            public void onError(String m) {
                zCb.onError(m);
            }
        };
    }

    // ---- gateway config (user-overridable; sane shipped defaults) ----

    static String gatewayUrl(Context zCtx) {
        return zCtx.getSharedPreferences("maxima_wallet", Context.MODE_PRIVATE)
                .getString("gateway_url", DEFAULT_GATEWAY_URL);
    }

    static String gatewayToken(Context zCtx) {
        return zCtx.getSharedPreferences("maxima_wallet", Context.MODE_PRIVATE)
                .getString("gateway_token", DEFAULT_GATEWAY_TOKEN);
    }

    // ---- public node-config surface (the "Wallet node" sheet, like FreezePeach) ----

    /** The wallet-node endpoint currently in use (your own node's /cmd, or the default). */
    public static String currentGatewayUrl(Context zCtx)   { return gatewayUrl(zCtx); }

    public static String currentGatewayToken(Context zCtx) { return gatewayToken(zCtx); }

    /** The shipped default (the first Parlons fleet node; shown as the reset target). */
    public static String defaultGatewayUrl()   { return DEFAULT_GATEWAY_URL; }

    /** Every fleet gateway, in failover order (full URLs, for display). */
    public static String[] fleetGatewayUrls()  { return FLEET_GATEWAY_URLS.clone(); }

    public static String defaultGatewayToken() { return DEFAULT_GATEWAY_TOKEN; }

    /**
     * Point the wallet at a node endpoint. An empty value restores the shipped default,
     * exactly like FreezePeach ({@code wallet_node_url}/{@code wallet_node_token}). The
     * caller must rebuild its WalletPublisher afterwards so the new endpoint takes effect.
     */
    public static void saveGateway(Context zCtx, String zUrl, String zToken) {
        String url = (zUrl == null) ? "" : zUrl.trim();
        String tok = (zToken == null) ? "" : zToken.trim();
        zCtx.getSharedPreferences("maxima_wallet", Context.MODE_PRIVATE).edit()
                .putString("gateway_url", url.isEmpty() ? DEFAULT_GATEWAY_URL : url)
                .putString("gateway_token", tok.isEmpty() ? DEFAULT_GATEWAY_TOKEN : tok)
                .apply();
    }
}
