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

    /** The shared-public gateway credential, deliberately shipped (read+relay only,
     *  cannot move funds - see freezepeach-wallet-node/proxy.js allowlist). */
    static final String DEFAULT_GATEWAY_URL = "https://relay.privateprivate.org/cmd";
    static final String DEFAULT_GATEWAY_TOKEN =
            "c9e6177419dc7e0f200390152c6296e2180fd317071c83f8db74ceab61286188";

    private static final String CORE_PKG = "org.minimarex.minimacore";

    private final Context mCtx;
    private final GatewayNode mGateway;
    private final NodeLink mNode;

    public WalletPublisher(Context zCtx, NodeLink zNodeOrNull) {
        mCtx = zCtx.getApplicationContext();
        mGateway = new GatewayNode(gatewayUrl(zCtx), gatewayToken(zCtx),
                new Handler(Looper.getMainLooper()));
        mNode = zNodeOrNull;
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
        return usingCore() ? "local minimaCore node" : "gateway";
    }

    private boolean usingCore() {
        return mNode != null && mNode.isEnabled();
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
        gcmd("balance megammr:true address:" + zAddress, zCb);
    }

    public void coins(String zAddress, Cb zCb) {
        gcmd("coins megammr:true address:" + zAddress, zCb);
    }

    public void trackScript(String zScript, Cb zCb) {
        gcmd("newscript trackall:true script:\"" + zScript + "\"", zCb);
    }

    /**
     * Publish a LOCALLY-SIGNED txn: txnimport -> txnbasics -> txnpost. ALWAYS through
     * the hosted MegaMMR gateway (see {@link #gcmd}): txnbasics must attach the coins'
     * MMR proofs from the node that actually tracks them, which — after the backfill —
     * is the gateway, not the user's node.
     */
    public void publish(String zImportCmd, String zId, String zPostCmd, Cb zCb) {
        // Publishing via a paired local node would require that node to be a MegaMMR
        // node AND to already track our backfilled coins (so txnbasics can attach their
        // proofs) — a normal paired node is neither. So publish through the same hosted
        // gateway the coins were imported to. (Node-side publishing can return when the
        // node also does the backfill.)
        mGateway.publish(zImportCmd, zId, zPostCmd, wrapG(zCb));
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

    // ---- gateway config (user-overridable later; sane shipped defaults) ----

    static String gatewayUrl(Context zCtx) {
        return zCtx.getSharedPreferences("maxima_wallet", Context.MODE_PRIVATE)
                .getString("gateway_url", DEFAULT_GATEWAY_URL);
    }

    static String gatewayToken(Context zCtx) {
        return zCtx.getSharedPreferences("maxima_wallet", Context.MODE_PRIVATE)
                .getString("gateway_token", DEFAULT_GATEWAY_TOKEN);
    }
}
