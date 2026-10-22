package com.eurobuddha.maxima.app.wallet;

import com.eurobuddha.wallet.TokenInputs;
import com.eurobuddha.wallet.TxnFactory;

import org.json.JSONObject;

import java.util.List;

/** Android glue for {@link TokenInputs}: coinexport through the {@link WalletPublisher}
 *  (paired node first, gateway fallback). The token logic itself is shared with desktop and cloud. */
public final class TokenSend {

    public static final String MXUSD_TOKENID = TokenInputs.MXUSD_TOKENID;
    public static final String MXUSD_NAME = TokenInputs.MXUSD_NAME;
    public static final String MINIMA_TOKENID = TokenInputs.MINIMA_TOKENID;
    public static final String MINIMA_NAME = TokenInputs.MINIMA_NAME;

    private static final long WAIT_MS = 45_000;

    private TokenSend() {
    }

    public static boolean isMinima(String zTokenId) { return TokenInputs.isMinima(zTokenId); }

    public static org.minima.objects.base.MiniNumber rawAmount(org.minima.utils.json.JSONArray zCoins,
            String zTokenId, org.minima.objects.base.MiniNumber zDisplay) {
        return TokenInputs.rawAmount(zCoins, zTokenId, zDisplay);
    }

    public static List<TxnFactory.InputCoin> resolveInputs(WalletPublisher zPub,
            List<org.minima.utils.json.JSONObject> zSelected, int zKeyIndex) throws Exception {
        return TokenInputs.resolve(zSelected, zKeyIndex,
                coinid -> WalletPublisher.blobOf(await(cb -> zPub.coinExport(coinid, cb))));
    }

    /** One publisher call, awaited (publisher callbacks arrive on its own threads). */
    public interface Call { void run(WalletPublisher.Cb zCb); }
    public static JSONObject await(Call zCall) throws Exception {
        final Object lock = new Object();
        final JSONObject[] resp = new JSONObject[1];
        final String[] err = new String[1];
        zCall.run(new WalletPublisher.Cb() {
            public void onResult(JSONObject r) { synchronized (lock) { resp[0] = r; lock.notifyAll(); } }
            public void onError(String m) { synchronized (lock) { err[0] = m; lock.notifyAll(); } }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (resp[0] == null && err[0] == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                lock.wait(remaining);
            }
        }
        if (resp[0] == null) throw new IllegalStateException(err[0] != null ? err[0] : "node timeout");
        return resp[0];
    }
}
