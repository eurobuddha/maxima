package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;


import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Parlons Cloud wallet (the NFTwallet companion app, ported whole). Tabs:
 * Balances / Gallery / Mint / Send / Receive / Coins / History.
 * Talks to the account's Parlons Node over the paired control channel ({@link NodeApi}).
 */
public class WalletActivity extends AppCompatActivity {

    /** The wallet screens read the chain tip from here (also used by the tip poll). */

    public static final int TAB_BALANCES = 0, TAB_GALLERY = 1, TAB_MINT = 2,
            TAB_SEND = 3, TAB_RECEIVE = 4, TAB_COINS = 5, TAB_HISTORY = 6;
    /** Legacy alias — the coin-picker tab was called "Wallet" in the utxo donor. */
    public static final int TAB_WALLET = TAB_COINS;

    private NodeApi node;
    private HistoryDb historyDb;

    private BaseView[] views;
    private MainPager pager;
    private ViewPager viewPager;
    private View pairingBanner;
    private TextView blockNo;
    private DistributeManager distribute;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reload;

    // ----- QR scan (zxing-embedded). Registered in onCreate; one consumer at a time. -----
    private androidx.activity.result.ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions> scanLauncher;
    private java.util.function.Consumer<String> scanConsumer;

    // ----- image pick (NFT art). Registered in onCreate; one consumer at a time. -----
    private androidx.activity.result.ActivityResultLauncher<String> imageLauncher;
    private java.util.function.Consumer<android.net.Uri> imageConsumer;
    private androidx.activity.result.ActivityResultLauncher<String> imagesLauncher;
    private java.util.function.Consumer<java.util.List<android.net.Uri>> imagesConsumer;

    // ----- wallet state -----
    private final List<Coin> coins = new ArrayList<>();
    private final Set<String> sendableIds = new HashSet<>();
    private final List<TokenBalance> balances = new ArrayList<>();
    // All of my wallet addresses (incl. zero-balance): each entry is {hexAddress, miniAddress}.
    private final List<String[]> myAddresses = new ArrayList<>();
    private final Set<String> myKeys = new HashSet<>();   // this node's public keys (for classifying own simple addresses)
    private String defaultMiniAddress = "";
    private int chainBlock = 0;
    private int lastScriptsBlock = -1;                 // throttle the ~27 KB scripts fetch
    private static final int SCRIPTS_EVERY = 20;       // blocks between scripts refreshes
    private String circulatingSupply = "";             // status.minima — live total Minima (1bn − burnt)

    // ----- selection state (single tokenid at a time) -----
    private final LinkedHashSet<String> selectedCoinIds = new LinkedHashSet<>();
    private String selectedTokenid = null;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList("sel_ids", new ArrayList<>(selectedCoinIds));
        if (selectedTokenid != null) out.putString("sel_tok", selectedTokenid);
        if (views != null) out.putStringArray("send_fields", ((SendView) views[TAB_SEND]).fieldValues());
        if (viewPager != null) out.putInt("cur_tab", viewPager.getCurrentItem());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);                 // must precede view construction (views read Design)
        Format.load(this);                 // ditto — every list renders amounts through Format
        setContentView(R.layout.activity_wallet);

        // Edge-to-edge is forced on targetSdk 35 — pad the root by the status/nav bar insets so the
        // top bar isn't drawn under the status bar.
        View root = findViewById(R.id.main);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            // Pad up by the keyboard when it's open so the Send fields sit above it (the ScrollView then scrolls).
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        historyDb = new HistoryDb(this);

        // QR scanner: must register before RESUMED. The library's CaptureActivity handles the
        // runtime CAMERA permission prompt itself.
        scanLauncher = registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), result -> {
            java.util.function.Consumer<String> consumer = scanConsumer;
            scanConsumer = null;
            if (consumer != null && result.getContents() != null) consumer.accept(result.getContents());
        });

        imageLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
            java.util.function.Consumer<android.net.Uri> consumer = imageConsumer;
            imageConsumer = null;
            if (consumer != null && uri != null) consumer.accept(uri);
        });

        imagesLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(), uris -> {
            java.util.function.Consumer<java.util.List<android.net.Uri>> consumer = imagesConsumer;
            imagesConsumer = null;
            if (consumer != null && uris != null && !uris.isEmpty()) consumer.accept(uris);
        });

        pairingBanner = findViewById(R.id.pairingBanner);
        blockNo = findViewById(R.id.blockNo);
        Button openNodeBtn = findViewById(R.id.openNodeBtn);
        openNodeBtn.setVisibility(View.GONE);   // the node is the account's; pairing lives on the Node tab

        // The "‹" back affordance where the old style toggle sat: one design, no toggle.
        TextView designToggle = findViewById(R.id.designToggle);
        designToggle.setText("‹");
        designToggle.setTextSize(24f);
        designToggle.setOnClickListener(v -> finish());

        TextView settingsBtn = findViewById(R.id.settingsBtn);
        settingsBtn.setOnClickListener(v -> SettingsDialog.show(this));
        settingsBtn.setTextColor(Design.accent());

        // Construct the IPC BEFORE the views/tab restore: restoring the saved tab fires the
        // tab-selected listener synchronously, and Receive/History immediately call node.cmd().
        node = new NodeApi(this, enabled -> {
            if (enabled) {
                setPaired(true);
                requestReload();
            } else {
                setPaired(false);
                schedulePairingRetry();
            }
        });
        // The REGISTER broadcast is a one-shot — lost if Minima Core isn't running yet. Retry
        // until the first pairing signal arrives (reRegister() is a no-op while writes are in flight).
        schedulePairingRetry();

        views = new BaseView[]{
                new BalancesView(this),
                new GalleryView(this),
                new MintView(this),
                new SendView(this),
                new ReceiveView(this),
                new WalletView(this),
                new HistoryView(this)
        };
        pager = new MainPager(views, new String[]{
                "Balances", "Gallery", "Mint", "Send", "Receive", "Coins", "History"});

        viewPager = findViewById(R.id.pager);
        viewPager.setOffscreenPageLimit(6);
        viewPager.setAdapter(pager);

        // Back steps out of a drilled-into Gallery collection before it leaves the app — otherwise
        // the only way out of a collection is the on-screen crumb, which reads as a trap.
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                GalleryView g = (GalleryView) views[TAB_GALLERY];
                if (viewPager.getCurrentItem() == TAB_GALLERY && g.onBackPressed()) return;
                setEnabled(false);           // let the default finish-the-Activity behaviour run
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        TabLayout tabs = findViewById(R.id.tabs);
        tabs.setupWithViewPager(viewPager);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                views[tab.getPosition()].refresh();
                views[tab.getPosition()].onShown();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {
                views[tab.getPosition()].refresh();
                views[tab.getPosition()].onShown();
            }
        });

        // The theme toggle uses recreate(); restore selection, typed Send fields, and the open tab across it.
        if (savedInstanceState != null) {
            java.util.ArrayList<String> sel = savedInstanceState.getStringArrayList("sel_ids");
            if (sel != null) { selectedCoinIds.clear(); selectedCoinIds.addAll(sel); }
            selectedTokenid = savedInstanceState.getString("sel_tok");
            ((SendView) views[TAB_SEND]).setFieldValues(savedInstanceState.getStringArray("send_fields"));
            int tab = savedInstanceState.getInt("cur_tab", 0);
            viewPager.setCurrentItem(tab, false);
            views[tab].refresh();   // paint the restored tab now; the rest refresh on tab-select / data reload
        }

        // Apply the chosen design language to the shell chrome.
        root.setBackgroundColor(Design.bg());
        viewPager.setBackgroundColor(Design.bg());
        ((TextView) findViewById(R.id.brandTitle)).setTextColor(Design.heading());   // dapp: title is --heading, not accent
        TextView brandSub = findViewById(R.id.brandSub);
        brandSub.setTextColor(Design.dim());
        String ver;
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { ver = "?"; }
        brandSub.setText("YOUR NODE'S WALLET  ·  TOKENS · NFTS  ·  v" + ver);
        blockNo.setTextColor(Design.dim());
        designToggle.setTextColor(Design.heading());
        pairingBanner.setBackgroundColor(Design.accentSoft());
        ((TextView) findViewById(R.id.pairingTitle)).setTextColor(Design.accent());
        ((TextView) findViewById(R.id.pairingBody)).setTextColor(Design.text());
        tabs.setBackgroundColor(Design.bg());
        tabs.setTabTextColors(Design.dim(), Design.heading());   // dapp: active tab text is --heading
        tabs.setSelectedTabIndicatorColor(Design.accent());
        tabs.setSelectedTabIndicatorHeight((int) (3 * getResources().getDisplayMetrics().density));  // 3px inset bar
        wireTabScrollArrows(tabs);

        // Live updates: no broadcast from a remote node — poll the tip on the wallet lane and
        // fan out a reload only when the block actually changed (see blockPoll).
        // Resumes any persisted multi-batch Distribute job.
        distribute = new DistributeManager(this);
    }

    /** Poll the chain tip while in front; a changed block triggers the coalesced reload. */
    private static final long BLOCK_POLL_MS = 25_000;
    private final Runnable blockPoll = new Runnable() {
        @Override public void run() {
            if (!FOREGROUND || node == null) return;
            node.cmd("block", new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    JSONObject r = json.optJSONObject("response");
                    String b = r == null ? "" : r.optString("block", "");
                    int now = -1;
                    try { now = Integer.parseInt(b); } catch (Exception ignored) { }
                    if (now > 0 && now != chainBlock) requestReload();
                    ui.postDelayed(blockPoll, BLOCK_POLL_MS);
                }
                @Override public void onError(String message) {
                    ui.postDelayed(blockPoll, BLOCK_POLL_MS);
                }
            });
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        // Refresh from the node, then keep the tip polled while we are in front.
        requestReload();
        ui.removeCallbacks(blockPoll);
        ui.postDelayed(blockPoll, BLOCK_POLL_MS);
        // If the user returns while parked on History, refetch it (otherwise it waits for the next block).
        if (currentTab() == TAB_HISTORY) views[TAB_HISTORY].onShown();
    }

    // Re-send the one-shot REGISTER every 10 s until the node answers (see NodeApi.reRegister).
    private static final long PAIRING_RETRY_MS = 10000;
    private final Runnable pairingRetry = new Runnable() {
        @Override public void run() {
            if (node == null || node.isEnabled()) return;
            node.reRegister();
            ui.postDelayed(this, PAIRING_RETRY_MS);
        }
    };

    private void schedulePairingRetry() {
        ui.removeCallbacks(pairingRetry);
        ui.postDelayed(pairingRetry, PAIRING_RETRY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        FOREGROUND = false;
        ui.removeCallbacks(blockPoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(reloadTask);
        ui.removeCallbacks(pairingRetry);
        if (node != null) node.onDestroy();
        ui.removeCallbacks(blockPoll);
    }

    // ===== loading =====

    /** Pull coins, sendable set, balances, default address and chain tip from the node. */
    public void reload() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", "");
                    if (b.isEmpty()) {
                        JSONObject h = r.optJSONObject("header");
                        if (h != null) b = h.optString("block", "");
                    }
                    try { chainBlock = Integer.parseInt(b); } catch (Exception ignored) {}
                    blockNo.setText("#" + chainBlock);
                    // History self-fetches (bounded) only when it's the visible tab.
                    views[TAB_HISTORY].onNewBlock();
                }
            }
            @Override public void onError(String message) { handleErr(message); }
        });

        loadCoins();

        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                applyBalances(json.optJSONArray("response"));
                views[TAB_BALANCES].refresh();
                views[TAB_SEND].refresh();
            }
            @Override public void onError(String message) { handleErr(message); }
        });

        // status carries the live circulating supply (status.minima = 1bn − burnt) shown on the Minima
        // balance card. Small summary response; refresh Balances once it lands.
        node.cmd("status", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String m = r.optString("minima", "");
                    if (!m.isEmpty()) { circulatingSupply = m; views[TAB_BALANCES].refresh(); }
                }
            }
            @Override public void onError(String message) {}
        });

        // getaddress (~0.5 KB) is only needed for the Receive tab — fetched there on demand, not every
        // reload. scripts (~27 KB) lists the wallet's addresses, which barely change — fetched rarely.
        maybeRefreshScripts();

        // State-NFT collection minting is not offered here; a mint left in flight elsewhere only
        // advances when its card is tapped on the Mint tab (no automatic engine tick).
    }

    /**
     * The coin list, fetched in a way that survives the 256 KB IPC cap.
     *
     * This wallet's own State NFT collections embed up to ImageTools.STATE_IMG_BUDGET base64 chars
     * of art PER COIN, so a
     * couple of collections can push an unbounded {@code coins relevant:true} over the cap — and an
     * over-cap reply comes back as the node's stub (ERR_TOO_LONG), leaving the wallet with NO coins
     * at all: empty Gallery, empty coin control, no explanation. So: try the whole list once, and on
     * an over-cap reply fall back to fetching per token from the (small) balance list, which keeps
     * each reply to one token's coins. Mirrors the adaptive-paging rule the History tab follows.
     */
    private void loadCoins() {
        node.cmd("coins relevant:true", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                coins.clear();
                addCoins(json.optJSONArray("response"));
                loadSendable();
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_TOO_LONG.equals(message)) { loadCoinsPerToken(); return; }
                handleErr(message);
            }
        });
    }

    /**
     * Fallback: one bounded `coins … tokenid:` per known token, accumulated into the coin list.
     *
     * The token list comes from `balance`, which reload() fires CONCURRENTLY — on a cold start or
     * a rotation it hasn't landed yet, and an empty list here would silently yield an empty wallet
     * until the next block. So when balances aren't loaded, fetch them first (small reply) and
     * chain from there.
     */
    private void loadCoinsPerToken() {
        coins.clear();
        if (balances.isEmpty()) {
            node.cmd("balance", new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    applyBalances(json.optJSONArray("response"));
                    fetchCoinsForKnownTokens();
                }
                @Override public void onError(String message) { loadSendable(); }
            });
            return;
        }
        fetchCoinsForKnownTokens();
    }

    private void fetchCoinsForKnownTokens() {
        final java.util.List<String> tokenids = new ArrayList<>();
        for (TokenBalance b : balances) if (Util.isValidHexId(b.tokenid)) tokenids.add(b.tokenid);
        if (tokenids.isEmpty()) { loadSendable(); return; }
        fetchCoinsFor(tokenids, 0);
    }

    /** Parse coins, dropping any whose command-bound fields aren't well formed. */
    private void addCoins(JSONArray arr) {
        if (arr == null) return;
        int dropped = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            Coin coin = Coin.from(c);
            if (!coin.isWellFormed()) { dropped++; continue; }
            if (StateNft.isBuried(coin)) continue;   // in the graveyard — not part of the wallet
            coins.add(coin);
        }
        if (dropped > 0) {
            Toast.makeText(this, dropped + " malformed coin(s) ignored — a token's metadata looks hostile.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void fetchCoinsFor(final java.util.List<String> tokenids, final int i) {
        if (i >= tokenids.size()) { loadSendable(); return; }
        node.cmd("coins relevant:true tokenid:" + tokenids.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                addCoins(json.optJSONArray("response"));
                fetchCoinsFor(tokenids, i + 1);
            }
            @Override public void onError(String message) {
                // One oversized token's coins are skipped rather than losing the whole list.
                fetchCoinsFor(tokenids, i + 1);
            }
        });
    }

    /** Replace the balance list from a `balance` reply, dropping malformed rows. */
    private void applyBalances(JSONArray arr) {
        balances.clear();
        if (arr == null) return;
        int dropped = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;
            TokenBalance tb = TokenBalance.from(b);
            if (tb.isWellFormed()) balances.add(tb); else dropped++;
        }
        if (dropped > 0) {
            Toast.makeText(this, dropped + " malformed balance row(s) ignored — a token's metadata looks hostile.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** The sendable subset marks which coins can be spent (script coins are never sendable). */
    private void loadSendable() {
        node.cmd("coins relevant:true sendable:true", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json2) {
                sendableIds.clear();
                JSONArray a2 = json2.optJSONArray("response");
                if (a2 != null) {
                    for (int i = 0; i < a2.length(); i++) {
                        JSONObject c = a2.optJSONObject(i);
                        if (c != null) sendableIds.add(c.optString("coinid", ""));
                    }
                }
                for (Coin c : coins) c.sendable = sendableIds.contains(c.coinid);
                pruneSelection();
                refreshAll();
                // Advance any running multi-batch Distribute job (change coin may have confirmed).
                if (distribute != null) distribute.onCoinsUpdated();
            }
            @Override public void onError(String message) { refreshAll(); }
        });
    }

    /** scripts (~27 KB) lists the wallet's stable default-address pool (64 pre-generated), so it barely
     *  changes — fetch on first load and then only every ~20 blocks, not every reload. Populates
     *  myAddresses (used by Wallet zero-balance rows + History direction classification) + refreshes Wallet. */
    private void maybeRefreshScripts() {
        if (!myAddresses.isEmpty() && chainBlock - lastScriptsBlock < SCRIPTS_EVERY) return;
        lastScriptsBlock = chainBlock;
        // Fetch this node's public keys first, so we can recognise our OWN simple single-key addresses that
        // aren't in the default-64 pool (e.g. a newaddress-minted address like a PandaPools pool payout $OADR)
        // and NOT mislabel them "CONTRACT". Keys are small + stable; refreshed on the same ~20-block cadence.
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject kj) { collectKeys(kj); loadScripts(); }
            @Override public void onError(String m) { loadScripts(); }   // no keys → fall back to default-only classification
        });
    }

    private void collectKeys(JSONObject kj) {
        myKeys.clear();
        Object resp = kj.opt("response");
        JSONArray arr = null;
        if (resp instanceof JSONArray) arr = (JSONArray) resp;
        else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) myKeys.add(pk.toLowerCase()); }
        }
    }

    private void loadScripts() {
        node.cmd("scripts", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                myAddresses.clear();
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) {
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.optJSONObject(i);
                        if (s == null) continue;
                        String hex = s.optString("address", "");
                        String mini = s.optString("miniaddress", hex);
                        // A wallet address = a default address, OR a simple single-key address whose key is OURS
                        // (the node holds it, e.g. a newaddress-minted $OADR). Both are fully spendable by this
                        // node, so neither should be flagged "CONTRACT". A covenant is not simple (publickey 0x00),
                        // so it stays classified as a contract.
                        String pk = s.optString("publickey", "").toLowerCase();
                        boolean wallet = s.optBoolean("default", false)
                                || (s.optBoolean("simple", false) && !pk.isEmpty() && myKeys.contains(pk));
                        if (!hex.isEmpty() && wallet && seen.add(hex)) {
                            myAddresses.add(new String[]{hex, mini});
                        }
                    }
                }
                views[TAB_WALLET].refresh();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    /** Set by the Receive tab when it fetches a getaddress (no longer fetched on every reload). */
    public void setDefaultAddress(String addr) {
        if (addr != null && !addr.isEmpty()) defaultMiniAddress = addr;
    }

    /** Re-render the History tab. */
    public void refreshHistory() {
        views[TAB_HISTORY].refresh();
    }

    private void handleErr(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) {
            setPaired(false);
        } else if (NodeApi.ERR_TOO_LONG.equals(message)) {
            Toast.makeText(this, "The node reply was too large — showing what's loaded.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    public void refreshAll() {
        for (BaseView v : views) v.refresh();
    }

    // ===== pairing UX =====

    private void setPaired(boolean paired) {
        pairingBanner.setVisibility(paired ? View.GONE : View.VISIBLE);
    }

    // ===== selection =====

    public void toggleCoin(Coin c) {
        if (selectedCoinIds.contains(c.coinid)) {
            selectedCoinIds.remove(c.coinid);
            if (selectedCoinIds.isEmpty()) selectedTokenid = null;
        } else {
            if (selectedTokenid != null && !selectedTokenid.equals(c.tokenid)) {
                selectedCoinIds.clear();
            }
            selectedTokenid = c.tokenid;
            selectedCoinIds.add(c.coinid);
        }
        views[TAB_WALLET].refresh();
    }

    public void clearSelection() {
        selectedCoinIds.clear();
        selectedTokenid = null;
        views[TAB_WALLET].refresh();
    }

    /** Drop selected coins that no longer exist (e.g. spent). */
    private void pruneSelection() {
        Set<String> live = new HashSet<>();
        for (Coin c : coins) live.add(c.coinid);
        selectedCoinIds.retainAll(live);
        if (selectedCoinIds.isEmpty()) selectedTokenid = null;
    }

    public List<Coin> selectedCoins() {
        List<Coin> out = new ArrayList<>();
        for (Coin c : coins) if (selectedCoinIds.contains(c.coinid)) out.add(c);
        return out;
    }

    public String selectedTotalString() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Coin c : selectedCoins()) {
            try { sum = sum.add(new BigDecimal(c.amount)); } catch (Exception ignored) {}
        }
        return Util.tidyAmount(sum.toPlainString());
    }

    // ===== accessors for views =====

    public NodeApi node() { return node; }
    public HistoryDb history() { return historyDb; }
    public List<Coin> coins() { return coins; }
    public List<TokenBalance> balances() { return balances; }
    public List<String[]> myAddresses() { return myAddresses; }
    /** Live circulating Minima supply (status.minima = 1bn − burnt), or "" until status has loaded. */
    public String circulatingSupply() { return circulatingSupply; }
    public String defaultAddress() { return defaultMiniAddress; }
    public boolean isSelected(String coinid) { return selectedCoinIds.contains(coinid); }
    public String selectedTokenid() { return selectedTokenid; }
    public void goToTab(int pos) { viewPager.setCurrentItem(pos); }
    private String designTag() {
        // Any light mode (Clean or Original) shows LIGHT; Current/Original-Dark show DARK.
        return Design.isDark() ? "DARK" : "LIGHT";
    }

    public int chainBlock() { return chainBlock; }

    /** Launch the QR scanner; the consumer gets the decoded text (or is dropped on cancel). */
    public void scanQr(java.util.function.Consumer<String> consumer) {
        scanConsumer = consumer;
        com.journeyapps.barcodescanner.ScanOptions opts = new com.journeyapps.barcodescanner.ScanOptions();
        opts.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        opts.setPrompt("Scan a Minima address QR");
        opts.setBeepEnabled(false);
        opts.setOrientationLocked(true);
        try { scanLauncher.launch(opts); }
        catch (Exception e) {
            scanConsumer = null;
            Toast.makeText(this, "Could not open the camera.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Jump to the Send tab with a token preselected in quick mode (Balances → Send). */
    public void sendToken(String tokenid) {
        ((SendView) views[TAB_SEND]).preselectToken(tokenid);
        goToTab(TAB_SEND);
    }

    /**
     * Open the system image picker in MULTI-select mode; the consumer gets every chosen Uri in
     * selection order (dropped on cancel). Used to fill a whole collection's image slots at once.
     */
    public void pickImages(java.util.function.Consumer<java.util.List<android.net.Uri>> consumer) {
        imagesConsumer = consumer;
        try { imagesLauncher.launch("image/*"); }
        catch (Exception e) {
            imagesConsumer = null;
            Toast.makeText(this, "Could not open the image picker.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Open the system image picker; the consumer gets the chosen Uri (dropped on cancel). */
    public void pickImage(java.util.function.Consumer<android.net.Uri> consumer) {
        imageConsumer = consumer;
        try { imageLauncher.launch("image/*"); }
        catch (Exception e) {
            imageConsumer = null;
            Toast.makeText(this, "Could not open the image picker.", Toast.LENGTH_SHORT).show();
        }
    }

    /** The currently visible tab index (ViewPager page). */
    public int currentTab() { return viewPager.getCurrentItem(); }
    public DistributeManager distribute() { return distribute; }
    /** Distribute progress is shown on the Wallet tab now (Tools is a dropdown there). */
    public void refreshTools() { views[TAB_WALLET].refresh(); }

    /** Coalesce bursts of NEWBLOCK/NEWBALANCE/onResume into a single reload. */
    public void requestReload() {
        ui.removeCallbacks(reloadTask);
        // 400 ms coalesces the NEWBLOCK + NEWBALANCE burst into a single reload (less node load).
        ui.postDelayed(reloadTask, 400);
    }

    /**
     * Chevrons either side of the tab strip. Seven tabs don't fit a narrow (or folded) display and
     * a scrollable TabLayout gives no hint that more exists — so show an arrow only on the side
     * that actually has something left to reveal, and scroll by most of a screen on tap.
     */
    private void wireTabScrollArrows(final TabLayout tabs) {
        final TextView left = findViewById(R.id.tabScrollLeft);
        final TextView right = findViewById(R.id.tabScrollRight);
        left.setTextColor(Design.accent());
        right.setTextColor(Design.accent());

        View.OnClickListener scroll = v -> {
            int by = Math.max(1, (int) (tabs.getWidth() * 0.7f));
            tabs.smoothScrollBy(v == left ? -by : by, 0);
            // Re-check once the fling settles; TabLayout gives us no scroll-end callback.
            tabs.postDelayed(() -> updateTabArrows(tabs, left, right), 350);
        };
        left.setOnClickListener(scroll);
        right.setOnClickListener(scroll);

        tabs.getViewTreeObserver().addOnScrollChangedListener(() -> updateTabArrows(tabs, left, right));
        tabs.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> updateTabArrows(tabs, left, right));
        // Selecting a tab scrolls the strip, which changes what's still off-screen.
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) {
                tabs.postDelayed(() -> updateTabArrows(tabs, left, right), 250);
            }
        });
        // The strip's inner width isn't known until it has been measured AND populated, and the
        // exact frame that happens on varies by device — so re-check a few times as it settles.
        for (int delay : new int[]{0, 150, 500, 1200}) {
            tabs.postDelayed(() -> updateTabArrows(tabs, left, right), delay);
        }
    }

    /**
     * Measure the overflow directly rather than asking {@code canScrollHorizontally}, which answers
     * from a scroll range that isn't populated until the strip has been laid out — so it reported
     * "nothing to scroll" and the chevrons never appeared at all.
     */
    private int lastLeftVis = -1, lastRightVis = -1;

    private void updateTabArrows(TabLayout tabs, View left, View right) {
        if (tabs == null || left == null || right == null) return;
        View strip = tabs.getChildCount() > 0 ? tabs.getChildAt(0) : null;
        if (strip == null) return;
        int slack = (int) (2 * getResources().getDisplayMetrics().density);
        int overflow = strip.getWidth() - tabs.getWidth();
        int x = tabs.getScrollX();
        final int wantLeft = x > slack ? View.VISIBLE : View.GONE;
        final int wantRight = overflow > slack && x < overflow - slack ? View.VISIBLE : View.GONE;
        if (wantLeft == lastLeftVis && wantRight == lastRightVis) return;
        lastLeftVis = wantLeft;
        lastRightVis = wantRight;
        left.setVisibility(wantLeft);
        right.setVisibility(wantRight);
        // These flips happen from a layout-change listener, i.e. DURING a layout pass, where
        // requestLayout() is swallowed — the chevrons went VISIBLE but were never measured, so they
        // sat at 0x0 and looked like they had never appeared. Re-request outside the pass.
        final View row = (View) right.getParent();
        if (row != null) row.post(row::requestLayout);
    }

    // ===== StateNFT mint engine driver =====

    private String mintStatus = "";

    /** Last engine progress message (shown on the Mint progress screen). */
    public String mintStatus() { return mintStatus; }

    /**
     * Advance any active collection mint (CREATE/MOVE/SPLIT/STAMP). Called on every reload —
     * i.e. per new block, which is exactly the cadence the phase machine wants. Re-entrancy
     * guarded: a tick chains many node commands and must not overlap itself.
     */
    public MintDriver.Result mintEngineTick() {
        MintDriver.Result r = MintDriver.tick(this, node, message -> {
            mintStatus = message;
            ((MintView) views[TAB_MINT]).onEngineTick();
        });
        // No background service here: a mint advances while the wallet is open (one txn per block).
        return r;
    }

    /**
     * True while this Activity is visible. The service stands down when it is, because both hosts
     * share the same LocalStore and two ticks racing would spend the same coin twice.
     */
    public static volatile boolean FOREGROUND = false;

    /** Max decimal places a token allows: -1 for Minima or an unknown token (no clamp). */
    public int tokenDecimals(String tokenid) {
        if (Util.isMinima(tokenid)) return -1;
        for (TokenBalance b : balances) {
            if (b.tokenid.equals(tokenid)) {
                try { return Integer.parseInt(b.meta.decimals.trim()); }
                catch (Exception e) { return -1; }
            }
        }
        return -1;
    }
}
