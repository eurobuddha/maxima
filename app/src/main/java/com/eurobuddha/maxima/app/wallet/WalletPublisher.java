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

    public void balance(String zAddress, Cb zCb) {
        cmd("balance megammr:true address:" + zAddress, zCb);
    }

    public void coins(String zAddress, Cb zCb) {
        cmd("coins megammr:true address:" + zAddress, zCb);
    }

    public void trackScript(String zScript, Cb zCb) {
        cmd("newscript trackall:true script:\"" + zScript + "\"", zCb);
    }

    /** Publish a LOCALLY-SIGNED txn: txnimport -> txnbasics -> txnpost. */
    public void publish(String zImportCmd, String zId, String zPostCmd, Cb zCb) {
        if (usingCore()) {
            // Same three-step relay, through the local node.
            mNode.cmd(zImportCmd, wrap(step1 -> {
                if (!step1.optBoolean("status", false)) {
                    zCb.onError("txnimport: " + step1.optString("error", step1.toString()));
                    return;
                }
                mNode.cmd("txnbasics id:" + zId, wrap(step2 -> {
                    if (!step2.optBoolean("status", false)) {
                        zCb.onError("txnbasics: " + step2.optString("error", step2.toString()));
                        return;
                    }
                    mNode.cmd(zPostCmd, wrap(step3 -> {
                        if (!step3.optBoolean("status", false)) {
                            zCb.onError("txnpost: " + step3.optString("error", step3.toString()));
                            return;
                        }
                        zCb.onResult(step3);
                    }, zCb));
                }, zCb));
            }, zCb));
        } else {
            mGateway.publish(zImportCmd, zId, zPostCmd, wrapG(zCb));
        }
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
