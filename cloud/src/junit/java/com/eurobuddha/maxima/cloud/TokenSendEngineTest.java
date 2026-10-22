package com.eurobuddha.maxima.cloud;

import com.eurobuddha.wallet.KeyUses;
import com.eurobuddha.wallet.TxnFactory;
import com.eurobuddha.wallet.WalletCore;
import org.junit.Test;
import org.minima.objects.Coin;
import org.minima.objects.Token;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.base.MiniString;
import org.minima.objects.mmr.MMREntryNumber;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Parlons MxUSD sends: the engine signs byte-exact token inputs and refuses a lossy descriptor
 *  BEFORE any one-time key is spent (port of apks/wallet's proven-live gate). Hermetic. */
public class TokenSendEngineTest {
    private static final String PHRASE = "parlons token send engine test phrase only";

    static final class InMemoryKeyUses implements KeyUses {
        private final Map<Integer, Integer> m = new HashMap<>();
        public synchronized int currentUses(int i) { Integer v = m.get(i); return v == null ? 0 : v; }
        public synchronized int reserveNextUse(int i) { int n = currentUses(i); m.put(i, n + 1); return n; }
        public synchronized void recordExternalUses(int i, int u) { m.put(i, Math.max(currentUses(i), u)); }
        public synchronized Map<Integer, Integer> snapshotAllUses() { return new HashMap<>(m); }
    }

    private static Token dollarToken() {
        // A dollar-like token: scale 8 like a stablecoin, JSON-object name (the lossy case).
        return new Token(new MiniData("0xC0FFEE0000000000000000000000000000000000000000000000000000000001"),
                new MiniNumber(8), new MiniNumber("1000"),
                new MiniString("{\"name\":\"MxUSD\",\"description\":\"test\"}"),
                new MiniString("RETURN TRUE"), MiniNumber.ZERO);
    }

    @Test public void mismatchedDescriptorIsRefusedBeforeSigning() {
        InMemoryKeyUses uses = new InMemoryKeyUses();
        WalletCore w = new WalletCore(PHRASE, uses);
        Token t = dollarToken();
        MiniData wrong = new MiniData("0xDEAD00000000000000000000000000000000000000000000000000000000BEEF");
        TxnFactory.InputCoin bad = new TxnFactory.InputCoin(
                new MiniData("0xEE11111111111111111111111111111111111111111111111111111111111111"),
                w.getAddress(0).getAddressData(), new MiniNumber("1000"), wrong,
                new MMREntryNumber(7), new MiniNumber("5"), true, null, t, 0);
        List<TxnFactory.InputCoin> in = new ArrayList<>();
        in.add(bad);
        try {
            new TxnFactory(w).buildSend(in, w.getAddress(1).getMinimaAddress(),
                    new MiniNumber("1"), wrong, MiniNumber.ZERO, "bad");
            fail("a lossy token descriptor must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("mismatch"));
        }
        assertEquals("no one-time key spent", 0, uses.currentUses(0));
    }

    @Test public void exportedTokenCoinBuildsSignedSendWithTokenChange() {
        InMemoryKeyUses uses = new InMemoryKeyUses();
        WalletCore w = new WalletCore(PHRASE, uses);
        Token t = dollarToken();
        MiniNumber raw = t.getScaledMinimaAmount(new MiniNumber("2.5"));   // 2.5 display units
        Coin chain = new Coin(new MiniData("0xEE22222222222222222222222222222222222222222222222222222222222222"),
                w.getAddress(0).getAddressData(), t.getScaledMinimaAmount(new MiniNumber("10")), t.getTokenID(), true);
        chain.setMMREntryNumber(new MMREntryNumber(8));
        chain.setBlockCreated(new MiniNumber("6"));
        chain.setToken(t);
        // The coinexport wire: CoinProof bytes -> Coin, token descriptor intact (TokenSend's parse).
        MiniData blob = MiniData.getMiniDataVersion(new org.minima.objects.CoinProof(chain, new org.minima.objects.mmr.MMRProof()));
        Coin parsed = org.minima.objects.CoinProof.convertMiniDataVersion(new MiniData(blob.to0xString())).getCoin();
        assertTrue(parsed.getCoinID().isEqual(chain.getCoinID()));
        assertTrue("descriptor recomputes to the coin's tokenid", parsed.getToken().getTokenID().isEqual(parsed.getTokenID()));
        List<TxnFactory.InputCoin> in = new ArrayList<>();
        in.add(TxnFactory.fromExportedCoin(parsed, 0));

        TxnFactory.BuiltTxn b = new TxnFactory(w).buildSend(in, w.getAddress(1).getMinimaAddress(),
                raw, t.getTokenID(), MiniNumber.ZERO, "ok");
        assertEquals(1, b.getNumSignatures());
        assertEquals(1, uses.currentUses(0));
        List<Coin> outs = b.getTransaction().getAllOutputs();
        assertEquals("recipient + change", 2, outs.size());
        MiniNumber total = MiniNumber.ZERO;
        for (Coin o : outs) {
            assertTrue("every output stays the token", o.getTokenID().isEqual(t.getTokenID()));
            total = total.add(o.getAmount());
        }
        assertEquals("token conserved", 0, total.compareTo(chain.getAmount()));
        assertEquals("recipient gets 2.5 display units", 0, outs.get(0).getAmount().compareTo(raw));
    }

    /** The shared resolver (phone, desktop, cloud): Minima rows from JSON, token rows ONLY via
     *  coinexport, a wrong coin from the exporter refused, display amounts scaled. */
    @Test public void tokenInputsResolveTokenRowsByteExact() throws Exception {
        WalletCore w = new WalletCore(PHRASE, new InMemoryKeyUses());
        Token t = dollarToken();
        Coin chain = new Coin(new MiniData("0xEE33333333333333333333333333333333333333333333333333333333333333"),
                w.getAddress(0).getAddressData(), t.getScaledMinimaAmount(new MiniNumber("4")), t.getTokenID(), true);
        chain.setMMREntryNumber(new MMREntryNumber(9));
        chain.setBlockCreated(new MiniNumber("7"));
        chain.setToken(t);
        String hex = MiniData.getMiniDataVersion(new org.minima.objects.CoinProof(chain,
                new org.minima.objects.mmr.MMRProof())).to0xString();

        org.minima.utils.json.JSONObject row = new org.minima.utils.json.JSONObject();
        row.put("coinid", chain.getCoinID().to0xString());
        row.put("tokenid", t.getTokenID().to0xString());
        java.util.List<String> asked = new ArrayList<>();
        List<TxnFactory.InputCoin> in = com.eurobuddha.wallet.TokenInputs.resolve(
                java.util.Collections.singletonList(row), 0, id -> { asked.add(id); return hex; });
        assertEquals(java.util.Collections.singletonList(chain.getCoinID().to0xString()), asked);
        assertTrue(in.get(0).getToken().getTokenID().isEqual(t.getTokenID()));

        org.minima.utils.json.JSONObject other = new org.minima.utils.json.JSONObject();
        other.put("coinid", "0xEE44444444444444444444444444444444444444444444444444444444444444");
        other.put("tokenid", t.getTokenID().to0xString());
        try {
            com.eurobuddha.wallet.TokenInputs.resolve(java.util.Collections.singletonList(other), 0, id -> hex);
            fail("an export of a different coin must be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("different coin"));
        }

        assertTrue(com.eurobuddha.wallet.TokenInputs.validTokenId(com.eurobuddha.wallet.TokenInputs.MXUSD_TOKENID));
        assertTrue(com.eurobuddha.wallet.TokenInputs.validTokenId("0x00"));
        assertFalse(com.eurobuddha.wallet.TokenInputs.validTokenId("0x00 address:Mx00;send"));
        assertFalse(com.eurobuddha.wallet.TokenInputs.validTokenId("0x7D39"));
    }


    /** The cloud RPC's optional tokenid is interpolated into node commands: only Minima or a full id. */
    @Test public void controlAcceptsOnlyMinimaOrAFullTokenId() {
        org.minima.utils.json.JSONObject in = new org.minima.utils.json.JSONObject();
        assertEquals("0x00", ParlonsControl.tokenIdOf(in));
        in.put("tokenid", com.eurobuddha.wallet.TokenInputs.MXUSD_TOKENID);
        assertEquals(com.eurobuddha.wallet.TokenInputs.MXUSD_TOKENID, ParlonsControl.tokenIdOf(in));
        in.put("tokenid", "0x00 address:Mx00");
        assertNull(ParlonsControl.tokenIdOf(in));
        assertEquals("MxUSD", ParlonsControl.tokenName(com.eurobuddha.wallet.TokenInputs.MXUSD_TOKENID));
        assertEquals("Minima", ParlonsControl.tokenName("0x00"));
    }


    /** An old host ignores "tokenid" and pays MINIMA: the client must refuse it beforehand
     *  (2026-10-22: a Portal MxUSD send through Parlons Node 0.2.130 paid 0.05 MINIMA). */
    @Test public void tokenSendsNeedATokenCapableHost() {
        org.minima.utils.json.JSONObject st = new org.minima.utils.json.JSONObject();
        st.put("version", "0.2.130");
        assertFalse(ParlonsRemote.hostSendsTokens(st));
        st.put("version", "0.2.135");
        assertTrue(ParlonsRemote.hostSendsTokens(st));
        st.put("version", "0.11.120");
        assertFalse(ParlonsRemote.hostSendsTokens(st));
        st.put("version", "0.11.121");
        assertTrue(ParlonsRemote.hostSendsTokens(st));
        st.put("version", "");
        assertFalse(ParlonsRemote.hostSendsTokens(st));
        st.put("tokenPayments", true);
        assertTrue(ParlonsRemote.hostSendsTokens(st));
        assertFalse(ParlonsRemote.hostSendsTokens(null));
    }

}
