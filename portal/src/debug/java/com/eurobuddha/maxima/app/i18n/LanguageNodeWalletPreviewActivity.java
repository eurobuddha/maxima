package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.portal.wallet.*;
import com.eurobuddha.maxima.app.portal.CloudSession;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only hosted node wallet with synthetic coins and a fake node. No network or signing. API 29+. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageNodeWalletPreviewActivity extends AppCompatActivity {
    private static final String DRAFT = "Draft 中文 Привет {count} $1";
    private String screen, mode;
    private Activity first;
    private WalletActivity wallet;
    private BaseView[] pages;
    private SendView send;
    private NodeApi fake;
    private NodeApi.Cb heldReply, expectedHeldReply;
    private TxnBuilder progressTask;
    private Coin coin;
    private TokenBalance balance;
    private HistoryDb historyDb;
    private final List<NodeTx> transactions = new ArrayList<>();
    private EditText balanceSearch;
    private View contractSource;
    private static final String NFT = "0x"+"dcba4321".repeat(8);
    private static final String COLLECTION = "0x"+"abcd9876".repeat(8);
    private GalleryView gallery;
    private EditText gallerySearch;
    private androidx.recyclerview.widget.RecyclerView galleryGrid;
    private Object galleryAdapter;
    private List<?> galleryItems;
    private java.util.Set<String> galleryFavourites;
    private String galleryOpen;
    private boolean galleryFav;
    private final java.util.Map<android.widget.ImageView,android.graphics.Bitmap> galleryImages = new java.util.HashMap<>();
    private final List<EditText> nftInputs = new ArrayList<>();
    private final List<String> nftCommands = new ArrayList<>();
    private JSONObject nftCoin;
    private final List<EditText> toolInputs = new ArrayList<>();
    private final List<String> toolDrafts = new ArrayList<>();
    private List<Coin> expectedSelection;
    private String expectedTotal;
    private DistributeManager distribution;
    private DistributeJob distributionJob;
    private String distributionJson, distributionSaved;
    private boolean distributionStarting, distributionInFlight;
    private MintView mint;
    private final List<EditText> mintInputs = new ArrayList<>();
    private final List<View> mintViews = new ArrayList<>();
    private final List<String> mintDrafts = new ArrayList<>();
    private final List<String> mintCommands = new ArrayList<>();
    private String mintSaved, mintImage;
    private boolean mintEmbed;
    private MintView collectionView;
    private final List<EditText> collectionInputs = new ArrayList<>();
    private final List<View> collectionViews = new ArrayList<>();
    private final List<String> collectionDrafts = new ArrayList<>();
    private String collectionSaved, collectionImages, collectionIcon, collectionStatus;
    private boolean collectionEmbed, collectionIconEmbed;
    private boolean collectionMode() { return mode.startsWith("collection-"); }
    private boolean mintMode() { return mode.startsWith("mint-"); }
    private boolean mintNft() { return mode.contains("nft"); }
    private boolean toolsMode() { return mode.startsWith("tools-"); }
    private boolean nftMode() { return mode.startsWith("nft-"); }
    private boolean nftProgress() { return nftMode() && mode.endsWith("-progress"); }
    private boolean galleryMode() { return mode.startsWith("gallery-"); }
    private boolean sendMode() { return mode.startsWith("send-"); }
    private boolean expectedQuick;
    private boolean financialMode() { return galleryMode() || sendMode() || mode.startsWith("balance") || mode.startsWith("history") || "export".equals(mode) || "link".equals(mode); }
    private boolean historyMode() { return mode.startsWith("history") || "export".equals(mode); }
    private int commands, expectedCommands;
    private android.graphics.Bitmap qr;
    private final List<View> originalViews = new ArrayList<>();
    private static final String ADDRESS = "0x" + "abcd1234".repeat(8);
    private View dialogRoot;
    private final List<EditText> inputs = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private int created, destroyed;
    private Application.ActivityLifecycleCallbacks callbacks;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Window inspection fixture requires Android 10 or newer");
        if (!"ranchu".equals(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        if (!com.eurobuddha.maxima.app.portal.CloudSession.account(this).isEmpty()) throw new IllegalStateException("Empty fixture account required");
        if (CloudSession.isPaired(this) || CloudSession.remoteOrNull() != null) throw new IllegalStateException("Unpaired fixture required");
        java.net.URL.setURLStreamHandlerFactory(protocol -> ("http".equals(protocol) || "https".equals(protocol)) ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url) throws java.io.IOException {
                throw new java.io.IOException("Network disabled in node wallet fixture");
            }
        } : null);
        Language.select(this, "en");
        screen = "com.eurobuddha.maxima.app.portal.wallet.WalletActivity";
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "coins";
        if(toolsMode()) DistributeJob.clear(this);
        if(mintMode() || collectionMode()) getSharedPreferences("nftwallet_statenft",MODE_PRIVATE).edit().clear().commit();
        callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {
                if (!a.getClass().getName().equals(screen)) return;
                created++;
            }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPostResumed(Activity a) {
                if (!a.getClass().getName().equals(screen) || first != null) return;
                first = a;
                try {
                    Handler handler = (Handler) field(a, "ui");
                    handler.removeCallbacks((Runnable) field(a, "reloadTask"));
                    handler.removeCallbacks((Runnable) field(a, "blockPoll"));
                    ((NodeApi)field(a, "node")).onDestroy();
                } catch (Exception e) { throw new IllegalStateException(e); }
                a.getWindow().getDecorView().post(() -> install(a));
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { if (a.getClass().getName().equals(screen)) destroyed++; }
        };
        getApplication().registerActivityLifecycleCallbacks(callbacks);
        receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (first == null || dialogRoot == null) return;
                String code = intent.getStringExtra("code");
                if (!Language.supported(code)) return;
                Language.select(first, code);
                first.getWindow().getDecorView().postDelayed(() -> verify(), 1000);
            }
        };
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(getPackageName()+".LANGUAGE_NODE_WALLET_FIXTURE"), ContextCompat.RECEIVER_EXPORTED);
        startActivity(new Intent().setClassName(this, screen));
    }

    private void install(Activity activity) {
        try {
            wallet = (WalletActivity) activity;
            fake = new NodeApi(activity, null) {
                @Override public void cmd(String command, Cb cb) { cmd(command, 0, cb); }
                @Override public void cmd(String command, long timeout, Cb cb) {
                    commands++;
                    try {
                        Object response;
                        if(collectionMode()) {
                            if("collection-starting".equals(mode) && "getaddress".equals(command)) { heldReply=cb;return; }
                            throw new IllegalStateException("Unexpected collection fixture command: "+command);
                        }
                        if (mintMode()) {
                            mintCommands.add(command);
                            if("maxima action:info".equals(command)) {
                                cb.onResult(new JSONObject().put("response",new JSONObject().put("name","Creator 中文 Русский")));return;
                            }
                            if("mint-progress".equals(mode) && "getaddress".equals(command)) {
                                cb.onResult(new JSONObject().put("response",new JSONObject().put("publickey",ADDRESS)));return;
                            }
                            if("mint-progress".equals(mode) && command.startsWith("tokencreate name:")) { heldReply=cb;return; }
                            throw new IllegalStateException("Unexpected mint fixture command: "+command);
                        }
                        if ("tools-starting".equals(mode) && "getaddress".equals(command)) { heldReply=cb;return; }
                        if (nftProgress()) {
                            boolean bury="nft-bury-progress".equals(mode);
                            List<String> allowed=bury ? StateNft.buryCommands("by9876abcd98",COLLECTION,"",nftCoin,true)
                                    : StateNft.transferCommands("tr9876abcd98",COLLECTION,nftCoin,ADDRESS);
                            if (nftCommands.size()>=allowed.size() || !command.equals(allowed.get(nftCommands.size())))
                                throw new IllegalStateException("Unexpected NFT fixture command: "+command);
                            nftCommands.add(command);
                            if (command.startsWith("txnsign ")) { heldReply=cb;return; }
                            cb.onResult(new JSONObject().put("status",true).put("response",new JSONObject()
                                    .put("coins",new JSONArray().put(new JSONObject().put("difference","0")))));
                            return;
                        }
                        if ("send-progress".equals(mode) && command.matches("txn(create|input|output|sign) id:uw_[0-9]+_[0-9a-f]+.*")) {
                            if (command.startsWith("txnsign ")) { heldReply = cb; return; }
                            cb.onResult(new JSONObject().put("status",true).put("response",new JSONObject()));
                            return;
                        }
                        if (command.startsWith("checkaddress address:")) response = new JSONObject().put("relevant", true).put("simple", true);
                        else if (command.equals("tokens tokenid:0x01")) response = new JSONObject().put("script", "RETURN FALSE");
                        else if (command.equals("coins relevant:true tokenid:0x01")) response = new JSONArray().put(new JSONObject()
                                .put("coinid", "0x"+"5678abcd".repeat(8)).put("address",ADDRESS).put("tokenid","0x01")
                                .put("amount","1.234567890123456789").put("tokenamount","1.234567890123456789")
                                .put("created","900").put("token","Token"));
                        else throw new IllegalStateException("Unexpected fixture command: " + command);
                        cb.onResult(new JSONObject().put("response",response));
                    } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
                }
            };
            set(wallet, "node", fake);
            pages = (BaseView[]) field(wallet, "views");
            send = (SendView)pages[WalletActivity.TAB_SEND];
            coin = Coin.from(new JSONObject().put("coinid", "0x" + "1234abcd".repeat(8))
                    .put("address", ADDRESS).put("miniaddress", ADDRESS).put("tokenid", "0x00")
                    .put("amount", "1.234567890123456789").put("created", "900").put("mmrentry", "77")
                    .put("storestate", true).put("state", new JSONArray().put(new JSONObject().put("port", 7).put("data", DRAFT))));
            coin.tokenName = "Wallet 中文 Русский"; coin.sendable = true;
            if (!coin.isWellFormed()) throw new IllegalStateException("Malformed fixture coin");
            wallet.coins().add(coin);
            wallet.myAddresses().add(new String[]{ADDRESS, ADDRESS});
            set(wallet,"chainBlock",1000); set(wallet,"defaultMiniAddress",ADDRESS);
            ((java.util.Set<String>) field(wallet,"selectedCoinIds")).add(coin.coinid);
            set(wallet,"selectedTokenid",coin.tokenid);
            // Preserve an in-flight send guard and drafts in the actual offscreen SendView.
            // No send/preview/sign action is invoked.
            set(send,"sending",true);
            collectInputs(send.getRoot(), inputs);
            for (EditText input : inputs) {
                boolean number=(input.getInputType() & android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_NUMBER;
                String value = input.getId() == R.id.sendSplit ? "00012" : number ? "1.234567890123456789" : DRAFT;
                input.setText(value); input.setSelection(2,5); values.add(value);
            }
            if (financialMode()) installFinancialData();
            if (sendMode()) {
                java.lang.reflect.Field precision = Format.class.getDeclaredField("decimals");
                precision.setAccessible(true); precision.setInt(null, Format.FULL);
                expectedQuick = !"send-coins".equals(mode) && !"send-confirm-coins".equals(mode);
                set(send, "quickTokenid", "0x01");
                invoke(send, "setMode", new Class<?>[]{boolean.class}, expectedQuick);
            }
            if (galleryMode()) installGalleryData();
            int tab = mintMode() || collectionMode() ? WalletActivity.TAB_MINT : galleryMode() ? WalletActivity.TAB_GALLERY : sendMode() ? WalletActivity.TAB_SEND : "receive".equals(mode) ? WalletActivity.TAB_RECEIVE
                    : historyMode() ? WalletActivity.TAB_HISTORY
                    : financialMode() ? WalletActivity.TAB_BALANCES : WalletActivity.TAB_COINS;
            ((androidx.viewpager.widget.ViewPager)field(wallet,"viewPager")).setCurrentItem(tab, false);
            if (tab == WalletActivity.TAB_BALANCES) pages[tab].refresh();
            if(toolsMode()) prepareToolSelection();
            expectedSelection=new ArrayList<>(wallet.selectedCoins());expectedTotal=wallet.selectedTotalString();
            List<View> before = WindowInspector.getGlobalWindowViews();
            if(collectionMode()) installCollection();
            else if(mintMode()) installMint();
            else if(toolsMode()) installToolsDialog();
            else if (nftMode()) installNftDialog();
            else switch(mode) {
                case "settings": SettingsDialog.show(wallet); break;
                case "coin": CoinDetailDialog.show(wallet, coin); break;
                case "progress": Sheet.progress(wallet, Language.msg(R.string.l10n_nav_wallet),
                        Language.msg(R.string.l10n_node_wallet_fetching_address)).finish(); break;
                case "balance-detail": invoke(pages[WalletActivity.TAB_BALANCES],"showTokenDetail",new Class<?>[]{TokenBalance.class},balance); break;
                case "history-detail": invoke(pages[WalletActivity.TAB_HISTORY],"showDetail",new Class<?>[]{NodeTx.class},transactions.get(0)); break;
                case "export": invoke(pages[WalletActivity.TAB_HISTORY],"exportDialog",new Class<?>[0]); break;
                case "link": Util.openWebUrl(wallet,"https://example.invalid/中文?name=Русский&literal={count}"); break;
                case "gallery-grid": case "gallery-collection": case "gallery-favourites": case "gallery-empty": break;
                case "gallery-detail": case "gallery-stamped": case "gallery-unstamped": {
                    Object item = galleryItem("gallery-detail".equals(mode) ? NFT : COLLECTION,
                            "gallery-stamped".equals(mode) ? 7 : -1);
                    invoke(gallery,"showDetail",new Class<?>[]{item.getClass()},item); break;
                }
                case "gallery-actions": invoke(gallery,"showCollectionActions",new Class<?>[0]); break;
                case "send-picker": invoke(send,"showTokenPicker",new Class<?>[0]); break;
                case "send-confirm-quick": invoke(send,"showQuickConfirm",
                        new Class<?>[]{String.class,String.class,String.class,int.class,CharSequence.class},
                        ADDRESS,"1.234567890123456789","0.000000000000000001",12,Util.displayName(balance)); break;
                case "send-confirm-coins": invoke(send,"showConfirm",
                        new Class<?>[]{List.class,String.class,String.class,String.class,String.class,String.class,String.class,String.class,java.math.BigDecimal.class},
                        wallet.selectedCoins(),ADDRESS,"1.034567890123456788","0x"+"abcdef12".repeat(8),"0.2",
                        "0.000000000000000001",coin.tokenid,coin.tokenName,new java.math.BigDecimal(coin.amount)); break;
                case "send-validation": invoke(send,"onPreview",new Class<?>[0]); break;
                case "send-progress":
                    progressTask = new TxnBuilder(wallet,wallet.selectedCoins(),
                            java.util.Collections.singletonList(new TxnBuilder.Out(ADDRESS,"1.234567890123456789")),
                            coin.tokenid,new TxnBuilder.Done() {
                        public void onPosted(String txpowid,List<TxnBuilder.OutCoin> outputs) { throw new IllegalStateException("Fixture must not complete signing"); }
                        public void onFailed(String message) { throw new IllegalStateException("Unexpected fixture transaction failure"); }
                    }).onProgress(label -> {
                        try { invoke(send,"status",new Class<?>[]{CharSequence.class,boolean.class},label,true); }
                        catch (Exception e) { throw new IllegalStateException(e); }
                    });
                    progressTask.run(); expectedHeldReply=heldReply;
                    if (heldReply==null) throw new IllegalStateException("Fixture must hold the signing reply");
                    break;
                case "coins": case "receive": case "balances": case "history": case "send-quick": case "send-coins": break;
                default: throw new IllegalArgumentException(mode);
            }
            for (View root : WindowInspector.getGlobalWindowViews()) if (!before.contains(root)) dialogRoot=root;
            if (dialogRoot==null) dialogRoot=pages[wallet.currentTab()].getRoot();
            if ("balance-detail".equals(mode)) {
                TextView toggle=findLabel(dialogRoot,Language.msg(R.string.l10n_node_balance_view_source).toString());
                if (toggle==null) throw new IllegalStateException("Contract source toggle missing");
                toggle.performClick();
                contractSource=findLabel(dialogRoot,"RETURN FALSE");
            }
            if ("gallery-detail".equals(mode)) {
                TextView tokenLabel=findLabel(dialogRoot,Language.msg(R.string.l10n_wallet_token_id).toString());
                if (tokenLabel==null || !((View)tokenLabel.getParent()).performClick())
                    throw new IllegalStateException("Token copy row missing");
            }
            if (nftMode()) {
                collectInputs(dialogRoot,nftInputs);
                for(EditText input:nftInputs) { input.setText(DRAFT);input.setSelection(2,5); }
                expectedHeldReply=heldReply;
                if(nftProgress() && heldReply==null) throw new IllegalStateException("NFT fixture must hold signing reply");
            }
            if(toolsMode()) {
                collectInputs(dialogRoot,toolInputs);
                for(EditText input:toolInputs) {
                    String value=(input.getInputType() & android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)!=0
                            ? "0.012345678901234567" : "00004";
                    input.setText(value);input.setSelection(2,5);toolDrafts.add(value);
                }
                distribution=wallet.distribute();distributionJob=(DistributeJob)field(distribution,"job");
                distributionJson=distributionJob==null?"":distributionJob.toJson().toString();
                distributionSaved=getSharedPreferences("nftwallet_distribute",MODE_PRIVATE).getString("job","");
                distributionStarting=(Boolean)field(distribution,"starting");distributionInFlight=(Boolean)field(distribution,"inFlight");
                expectedHeldReply=heldReply;
            }
            if(mintMode()) {
                collectInputs(mint.getRoot(),mintInputs);collectViews(mint.getRoot(),mintViews);
                for(EditText input:mintInputs) { mintDrafts.add(input.getText().toString());input.setSelection(Math.min(2,input.length()),Math.min(5,input.length())); }
                mintSaved=LocalStore.load(wallet).toString();mintImage=(String)field(mint,"nftImageB64");
                mintEmbed=(Boolean)field(mint,"nftEmbedMode");expectedHeldReply=heldReply;
                if("mint-progress".equals(mode) && heldReply==null) throw new IllegalStateException("Missing held mint callback");
            }
            if(collectionMode()) {
                collectInputs(collectionView.getRoot(),collectionInputs);collectViews(collectionView.getRoot(),collectionViews);
                for(EditText input:collectionInputs) { collectionDrafts.add(input.getText().toString());input.setSelection(Math.min(2,input.length()),Math.min(5,input.length())); }
                collectionSaved=LocalStore.load(wallet).toString();
                collectionImages=collectionImageSnapshot();
                collectionIcon=(String)field(collectionView,"colIconB64");
                collectionStatus=wallet.mintStatus();
                collectionEmbed=(Boolean)field(collectionView,"colEmbedMode");collectionIconEmbed=(Boolean)field(collectionView,"colIconEmbed");
                expectedHeldReply=heldReply;
                if("collection-starting".equals(mode) && heldReply==null)throw new IllegalStateException("Missing held creator-key callback");
            }
            // Check every child instance, not just the root page.
            collectViews(dialogRoot, originalViews);
            expectedCommands = commands;
            if ("send-progress".equals(mode)) dialogRoot.post(() ->
                    ((android.widget.ScrollView)send.getRoot()).fullScroll(View.FOCUS_DOWN));
            dialogRoot.postDelayed(this::captureQr, 700);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void installCollection() throws Exception {
        collectionView=(MintView)pages[WalletActivity.TAB_MINT];
        String phase="collection-buried".equals(mode)?"BURIED":"collection-done".equals(mode)?"DONE"
                :"collection-images".equals(mode)?"NEEDIMAGES":"collection-repost".equals(mode)?"CREATE":"STAMP";
        JSONObject row=new JSONObject().put("id",7).put("name",DRAFT).put("size",2).put("phase",phase)
                .put("tokenid","collection-repost".equals(mode)?"":COLLECTION).put("creatorpk",ADDRESS).put("creatoraddr",ADDRESS)
                .put("mode","embed").put("posted",1).put("postedat",980)
                .put("items",new JSONArray().put(new JSONObject().put("idx",1).put("coinid",NFT).put("image","QUJDRA=="))
                        .put(new JSONObject().put("idx",2).put("coinid","collection-done".equals(mode)?ADDRESS:"")
                                .put("image","collection-images".equals(mode)?"":"REVGSA==")));
        if("collection-stuck".equals(mode))row.put("stuck",1).put("error",StateNft.jointGate(StateNft.META_MAX+1,0));
        if("collection-repost".equals(mode))row.put("createstuck",1).put("error",
                "The create transaction hasn't confirmed after 20 blocks. It may still land — re-post only if you're sure it was lost.");
        LocalStore.save(wallet,new JSONArray().put(row));
        set(wallet,"mintStatus","Waiting for tokencreate confirmation (2/20 blocks)");
        boolean form=mode.equals("collection-form")||mode.equals("collection-confirm")||mode.equals("collection-starting");
        Class<?> screenClass=Class.forName(MintView.class.getName()+"$Screen");
        set(collectionView,"screen",Enum.valueOf((Class)screenClass,form?"COLLECTION":"collection-hub".equals(mode)?"HUB":"PROGRESS"));
        set(collectionView,"progressRowId",7L);collectionView.refresh();
        if(form) {
            String[] names={"cName","cSize","cDesc","cBase","cExt","cIconUrl","cExtUrl","cWebv"};
            String[] values={"Collection","00002","Not set","https://example.invalid/nft/",".png","","https://example.invalid/about",""};
            for(int i=0;i<names.length;i++)((EditText)field(collectionView,names[i])).setText(values[i]);
            ((java.util.Map<Integer,String>)field(collectionView,"colImages")).put(1,"QUJDRA==");
            set(collectionView,"colIconB64","REVGSA==");
            invoke(collectionView,"setColMode",new Class<?>[]{boolean.class},false);
            invoke(collectionView,"setIconMode",new Class<?>[]{boolean.class},false);
            if("collection-confirm".equals(mode))invoke(collectionView,"onReviewCollection",new Class<?>[0]);
            if("collection-starting".equals(mode))invoke(collectionView,"startCollection",
                    new Class<?>[]{String.class,int.class,String.class,String.class,String.class,String.class,String.class,String.class},
                    "Collection",2,"Not set","https://example.invalid/nft/",".png","","https://example.invalid/about","");
        }
        if("collection-repost".equals(mode)) {
            TextView repost=findLabel(collectionView.getRoot(),Language.msg(R.string.l10n_node_collection_repost_link).toString());
            if(repost==null || !repost.performClick())throw new IllegalStateException("Missing re-post review action");
        }
    }

    private void installMint() throws Exception {
        mint=(MintView)pages[WalletActivity.TAB_MINT];
        Class<?> screenClass=Class.forName(MintView.class.getName()+"$Screen");
        set(mint,"screen",Enum.valueOf((Class)screenClass,mintNft()?"NFT":"TOKEN"));
        mint.refresh();
        if(mintNft()) {
            String[] names={"nName","nEditions","nDesc","nExtUrl","nUrl","nCreator","nWebv","nBurn"};
            String[] values={"NFT 中文 Русский","00022","Not set","https://example.invalid/art","ipfs://fixture-image","Creator 中文 Русский","","0.000000000000000001"};
            for(int i=0;i<names.length;i++)((EditText)field(mint,names[i])).setText(values[i]);
            set(mint,"nftImageB64","QUJDRA==");
            invoke(mint,"setNftMode",new Class<?>[]{boolean.class},"mint-nft".equals(mode));
        } else {
            String[] names={"tName","tAmount","tDecimals","tTicker","tDesc","tUrl","tWebv","tBurn"};
            String[] values={"Token","1000000000000","16","TOK","Not set","https://example.invalid/icon","","0.000000000000000001"};
            for(int i=0;i<names.length;i++)((EditText)field(mint,names[i])).setText(values[i]);
            invoke(mint,"addMetaRow",new Class<?>[0]);
            android.widget.LinearLayout row=(android.widget.LinearLayout)((android.widget.LinearLayout)field(mint,"tMetaRows")).getChildAt(0);
            ((EditText)row.getChildAt(0)).setText("label");((EditText)row.getChildAt(1)).setText("Metadata 中文 Русский $1");
        }
        if(mode.contains("confirm") || "mint-progress".equals(mode) || "mint-validation".equals(mode)) {
            if("mint-validation".equals(mode))((EditText)field(mint,"tName")).setText("Name; injection");
            invoke(mint,mintNft()?"onReviewNft":"onReviewToken",new Class<?>[0]);
        }
        if("mint-progress".equals(mode)) {
            TextView action=null;
            for(View window:WindowInspector.getGlobalWindowViews()) {
                TextView candidate=findLabel(window,Language.msg(R.string.l10n_node_mint_mint_action).toString());
                if(candidate!=null)action=candidate;
            }
            if(action==null || !action.performClick())throw new IllegalStateException("Missing mint confirmation action");
        }
        if("mint-result".equals(mode))invoke(mint,"resultDialog",new Class<?>[]{CharSequence.class,CharSequence.class},
                Language.msg(R.string.l10n_node_mint_token_minted),Language.msg(R.string.l10n_node_mint_token_minted_help));
    }

    private void prepareToolSelection() throws Exception {
        if("tools-consolidate".equals(mode)) wallet.clearSelection();
        if("tools-merge".equals(mode)) {
            Coin second=Coin.from(new JSONObject(coin.raw.toString()).put("coinid","0x"+"2345abcd".repeat(8))
                    .put("amount","2.000000000000000001"));
            second.sendable=true;second.tokenName=coin.tokenName;wallet.coins().add(second);wallet.toggleCoin(second);
        }
    }
    private void installToolsDialog() throws Exception {
        WalletTools tools=(WalletTools)field(pages[WalletActivity.TAB_COINS],"tools");
        switch(mode) {
            case "tools-split":tools.showSplit();break;
            case "tools-distribute":tools.showDistribute();break;
            case "tools-untrack":tools.untrackSelected();break;
            case "tools-consolidate":case "tools-merge":case "tools-validation":tools.showConsolidate();break;
            case "tools-starting":
                invoke(tools,"doDistribute",new Class<?>[]{List.class,String.class,String.class},wallet.selectedCoins(),"22","0.01");
                if(heldReply==null || !wallet.distribute().isBusy()) throw new IllegalStateException("Pending address reservation missing");
                break;
            case "tools-waiting": {
                DistributeJob job=new DistributeJob();job.tokenid=COLLECTION;job.tokenName=DRAFT;
                job.per="0.012345678901234567";job.batchesDone=2;job.retryCount=2;job.waiting=true;
                job.expectedChangeCoinId=NFT;job.nextChangeAddr=ADDRESS;job.expectedChangeAmt="0.000000000000000001";
                job.atBlock=999;job.currentInternalId="fixture 中文 Русский";
                for(int i=0;i<22;i++)job.remaining.add(ADDRESS);
                set(wallet.distribute(),"job",job);job.save(wallet);wallet.refreshTools();break;
            }
            default:throw new IllegalArgumentException(mode);
        }
    }

    private void installNftDialog() throws Exception {
        nftCoin=new JSONObject().put("coinid","0x"+"9876abcd".repeat(8)).put("tokenamount","1.234567890123456789")
                .put("state",new JSONArray().put(new JSONObject().put("port",0).put("data","007"))
                        .put(new JSONObject().put("port",1).put("data","[QUJDRA==]")));
        String item=DRAFT+" #007";
        switch(mode) {
            case "nft-transfer": StateNftActions.transferDialog(wallet,COLLECTION,item,nftCoin);break;
            case "nft-bury": StateNftActions.buryDialog(wallet,COLLECTION,DRAFT,item,nftCoin);break;
            case "nft-transfer-collection": StateNftActions.transferCollectionDialog(wallet,COLLECTION,DRAFT,null);break;
            case "nft-bury-collection": StateNftActions.buryCollectionDialog(wallet,COLLECTION,DRAFT,"",null);break;
            case "nft-unstamped":
                nftCoin.getJSONArray("state").getJSONObject(0).put("data","0");
                StateNftActions.transferDialog(wallet,COLLECTION,item,nftCoin);break;
            case "nft-unsafe":
                nftCoin.getJSONArray("state").getJSONObject(1).put("data","[abc] txnsign id:x publickey:auto");
                StateNftActions.buryDialog(wallet,COLLECTION,DRAFT,item,nftCoin);break;
            case "nft-id": StateNftActions.transferDialog(wallet,"0xBAD ID",item,nftCoin);break;
            case "nft-collection-id": StateNftActions.buryCollectionDialog(wallet,"0xBAD ID",DRAFT,"",null);break;
            case "nft-transfer-progress": {
                java.lang.reflect.Method m=StateNftActions.class.getDeclaredMethod("runTransfer",WalletActivity.class,String.class,String.class,JSONObject.class,String.class);
                m.setAccessible(true);m.invoke(null,wallet,COLLECTION,item,nftCoin,ADDRESS);break;
            }
            case "nft-bury-progress": {
                java.lang.reflect.Method m=StateNftActions.class.getDeclaredMethod("runBury",WalletActivity.class,String.class,String.class,JSONObject.class);
                m.setAccessible(true);m.invoke(null,wallet,COLLECTION,item,nftCoin);break;
            }
            default:throw new IllegalArgumentException(mode);
        }
    }

    private void installGalleryData() throws Exception {
        gallery=(GalleryView)pages[WalletActivity.TAB_GALLERY];
        gallerySearch=(EditText)field(gallery,"search");
        galleryGrid=(androidx.recyclerview.widget.RecyclerView)field(gallery,"grid");
        galleryAdapter=field(gallery,"adapter");
        java.util.Map<String,JSONObject> records=(java.util.Map<String,JSONObject>)field(gallery,"tokenRecords");
        records.put("0x02",new JSONObject().put("script","RETURN TRUE"));
        JSONObject meta=new JSONObject().put("name","Collection").put("mode","url").put("size",30)
                .put("description",DRAFT).put("owner","Creator 中文 Русский")
                .put("external_url","https://example.invalid/中文?literal={count}");
        records.put(COLLECTION,new JSONObject().put("token",new JSONObject().put("name",meta).put("totalamount","30"))
                .put("script",StateNft.script("0x"+"11".repeat(32),"url")));
        wallet.balances().add(TokenBalance.from(new JSONObject().put("tokenid",COLLECTION)
                .put("token",new JSONObject().put("name",meta).put("decimals","0"))
                .put("sendable","0").put("total","22").put("confirmed","22").put("coins",22)));
        for(int index=0;index<22;index++) {
            Coin item=Coin.from(new JSONObject().put("coinid","0x"+String.format(java.util.Locale.ROOT,"%064x",index+100))
                    .put("address",ADDRESS).put("tokenid",COLLECTION).put("amount","1").put("tokenamount","1")
                    .put("storestate",true).put("state",new JSONArray().put(new JSONObject().put("port",0).put("data",String.valueOf(index)))));
            wallet.coins().add(item);
        }
        JSONObject regular=new JSONObject().put("name","Token").put("decimals","0").put("owner","Creator 中文 Русский")
                .put("description",DRAFT).put("external_url","https://example.invalid/中文?literal={count}")
                .put("webvalidate","https://example.invalid/verify/Русский");
        wallet.balances().add(TokenBalance.from(new JSONObject().put("tokenid",NFT)
                .put("token",new JSONObject().put("name",regular).put("decimals","0"))
                .put("sendable","1").put("total","1").put("confirmed","1").put("coins",1)));
        records.put(NFT,new JSONObject().put("script","RETURN TRUE"));
        Field cache=WebValidate.class.getDeclaredField("CACHE");cache.setAccessible(true);
        ((java.util.Map<String,Boolean>)cache.get(null)).put(NFT.substring(2),true);
        galleryFavourites=new java.util.HashSet<>();
        if ("gallery-favourites".equals(mode) || "gallery-detail".equals(mode)) {
            galleryFavourites.add(NFT); galleryFavourites.add(COLLECTION+"#7");
        }
        getSharedPreferences("nftwallet_gallery",MODE_PRIVATE).edit().putStringSet("favs",galleryFavourites).commit();
        galleryFav="gallery-favourites".equals(mode) || "gallery-empty".equals(mode);
        set(gallery,"showFavs",galleryFav);
        gallerySearch.setText("0x"); gallerySearch.setSelection(0,2);
        gallery.refresh();
        if ("gallery-collection".equals(mode) || "gallery-stamped".equals(mode)
                || "gallery-unstamped".equals(mode) || "gallery-actions".equals(mode)) {
            Object card=galleryItem(COLLECTION,-1);
            invoke(gallery,"openCollection",new Class<?>[]{card.getClass()},card);
        }
        galleryItems=new ArrayList<>((List<?>)field(gallery,"items"));
        galleryOpen=(String)field(gallery,"openCollection");
    }
    private Object galleryItem(String token,int index)throws Exception {
        for (Object item:(List<?>)field(gallery,"items")) if (token.equals(field(item,"tokenid")) && Integer.valueOf(index).equals(field(item,"idx"))) return item;
        throw new IllegalStateException("Missing fixture gallery item "+token+"/"+index);
    }

    private void installFinancialData() throws Exception {
        balance=TokenBalance.from(new JSONObject().put("tokenid","0x01")
                .put("token",new JSONObject().put("name","Token").put("ticker","TEST").put("description",DRAFT).put("decimals","18"))
                .put("sendable","1.234567890123456789").put("confirmed","2.234567890123456790")
                .put("unconfirmed","0.000000000000000001").put("total","999999.123456789").put("coins",21));
        balance.meta.rawUrl="https://example.invalid/中文?name=Русский&literal={count}";
        wallet.balances().add(balance);
        wallet.balances().add(TokenBalance.from(new JSONObject().put("tokenid","0x02").put("confirmed","3").put("coins",2)));
        if (!balance.meta.nameProvided || wallet.balances().get(1).meta.nameProvided)
            throw new IllegalStateException("Missing and literal Token names must be distinct");
        balanceSearch=pages[WalletActivity.TAB_BALANCES].getRoot().findViewById(R.id.balSearch);
        balanceSearch.setText("Token"); balanceSearch.setSelection(2,5);
        ((HistoryDb)field(wallet,"historyDb")).close();
        for (String direction : new String[]{"sent","received","self"}) {
            NodeTx tx=new NodeTx(); tx.txpowid="0x"+"cdef1234".repeat(8); tx.block=999;
            tx.timemilli=1700000000000L; tx.direction=direction; tx.incoming="received".equals(direction);
            tx.amount="1.234567890123456789"; tx.tokenid="0x01"; tx.tokenName="Wallet 中文 Русский";
            tx.deltas=new JSONObject().put("0x01","-1.234567890123456789").toString(); tx.counterparty=ADDRESS;
            tx.inputs=new JSONArray().put(new JSONObject().put("addr",ADDRESS).put("amount",tx.amount).put("tokenid","0x01")).toString();
            tx.outputs=tx.inputs;
            if ("self".equals(direction)) tx.outputs=new JSONArray(tx.inputs).put(new JSONObject().put("addr",ADDRESS).put("amount","0.1").put("tokenid","0x01")).toString();
            transactions.add(tx);
        }
        historyDb=new HistoryDb(wallet) {
            @Override public List<NodeTx> loadNodeTx(int limit) { return new ArrayList<>(transactions); }
            @Override public int nodeTxCount() { return transactions.size(); }
            @Override public boolean upsertNodeTx(NodeTx tx) { throw new IllegalStateException("Fixture history is read-only"); }
        };
        set(wallet,"historyDb",historyDb);
        set(pages[WalletActivity.TAB_HISTORY],"fetching",true);
        set(pages[WalletActivity.TAB_HISTORY],"filter", "history".equals(mode) ? "self" : "sent");
    }
    private static void invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception {
        java.lang.reflect.Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,args);
    }
    private static TextView findLabel(View view,String label) {
        if (view instanceof TextView && label.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            TextView found=findLabel(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;
        }
        return null;
    }
    private void captureQr() {
        if (galleryMode()) {
            try { galleryItems=new ArrayList<>((List<?>)field(gallery,"items")); }
            catch(Exception e) { throw new IllegalStateException(e); }
            originalViews.clear(); collectViews(dialogRoot, originalViews);
            for (View view : originalViews) if (view instanceof android.widget.ImageView) {
                android.graphics.drawable.Drawable drawable=((android.widget.ImageView)view).getDrawable();
                if (drawable instanceof android.graphics.drawable.BitmapDrawable)
                    galleryImages.put((android.widget.ImageView)view,((android.graphics.drawable.BitmapDrawable)drawable).getBitmap());
            }
        }
        qr = qrBitmap();
        if ("receive".equals(mode) && qr == null) {
            try (java.io.FileOutputStream out = openFileOutput("language-node-wallet-qr-pending.json", MODE_PRIVATE)) {
                android.widget.ImageView image = pages[WalletActivity.TAB_RECEIVE].getRoot().findViewById(R.id.rcvQr);
                out.write(new JSONObject().put("drawable",String.valueOf(image.getDrawable()))
                        .put("tag",String.valueOf(image.getTag())).toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new IllegalStateException(e); }
            dialogRoot.postDelayed(this::captureQr, 100);
            return;
        }
        verify();
    }
    private android.graphics.Bitmap qrBitmap() {
        android.graphics.drawable.Drawable drawable = ((android.widget.ImageView)pages[WalletActivity.TAB_RECEIVE]
                .getRoot().findViewById(R.id.rcvQr)).getDrawable();
        return drawable instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable)drawable).getBitmap() : null;
    }
    private boolean sameQr() {
        android.graphics.Bitmap current = qrBitmap();
        // Android may replace the Drawable wrapper during a resource configuration change.
        // The view, address and QR pixels must remain unchanged.
        return qr == null ? current == null : current != null && qr.sameAs(current)
                && ADDRESS.equals(pages[WalletActivity.TAB_RECEIVE].getRoot().findViewById(R.id.rcvQr).getTag());
    }
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }

    private void verify() {
        try {
            boolean same = created==1 && destroyed==0 && !first.isDestroyed()
                    && pages == field(wallet,"views") && wallet.node()==fake
                    && Boolean.TRUE.equals(field(send,"sending"))
                    && expectedSelection.equals(wallet.selectedCoins())
                    && expectedTotal.equals(wallet.selectedTotalString())
                    && dialogRoot.isAttachedToWindow() && commands==expectedCommands
                    && !CloudSession.isPaired(this) && CloudSession.remoteOrNull()==null
                    && sameQr();
            if (financialMode()) {
                same &= wallet.balances().get(0)==balance && wallet.history()==historyDb
                        && "Token".contentEquals(balanceSearch.getText()) && balanceSearch.getSelectionStart()==2 && balanceSearch.getSelectionEnd()==5
                        && Boolean.TRUE.equals(field(pages[WalletActivity.TAB_HISTORY],"fetching"))
                        && ("history".equals(mode) ? "self" : "sent").equals(field(pages[WalletActivity.TAB_HISTORY],"filter"));
                if (contractSource!=null) same &= contractSource.getVisibility()==View.VISIBLE;
            }
            if (sendMode()) same &= Boolean.valueOf(expectedQuick).equals(field(send,"quickMode"))
                    && "0x01".equals(field(send,"quickTokenid")) && Boolean.TRUE.equals(field(send,"changeTouched"))
                    && !pages[WalletActivity.TAB_SEND].getRoot().findViewById(R.id.previewBtn).isEnabled();
            if ("send-progress".equals(mode)) same &= progressTask!=null && heldReply!=null && heldReply==expectedHeldReply;
            List<View> currentViews=new ArrayList<>();collectViews(dialogRoot,currentViews);
            boolean sameChildren = galleryMode() && dialogRoot==gallery.getRoot()
                    ? galleryGrid==field(gallery,"grid") && galleryAdapter==field(gallery,"adapter") && gallerySearch==field(gallery,"search")
                    : originalViews.equals(currentViews);
            same &= sameChildren;
            if (galleryMode()) {
                same &= galleryItems.equals(field(gallery,"items"))
                        && galleryFavourites.equals(getSharedPreferences("nftwallet_gallery",MODE_PRIVATE).getStringSet("favs",java.util.Collections.emptySet()))
                        && java.util.Objects.equals(galleryOpen,field(gallery,"openCollection"))
                        && Boolean.valueOf(galleryFav).equals(field(gallery,"showFavs"))
                        && "0x".contentEquals(gallerySearch.getText())
                        && gallerySearch.getSelectionStart()==0 && gallerySearch.getSelectionEnd()==2;
                for (java.util.Map.Entry<android.widget.ImageView,android.graphics.Bitmap> entry : galleryImages.entrySet()) {
                    if (!entry.getKey().isAttachedToWindow()) continue;
                    android.graphics.drawable.Drawable drawable=entry.getKey().getDrawable();
                    same &= drawable instanceof android.graphics.drawable.BitmapDrawable
                            && entry.getValue()==((android.graphics.drawable.BitmapDrawable)drawable).getBitmap();
                }
                if ("gallery-detail".equals(mode)) same &= NFT.contentEquals(((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                        .getPrimaryClip().getItemAt(0).getText());
            }
            if(collectionMode()) {
                List<EditText> currentCollection=new ArrayList<>();collectInputs(collectionView.getRoot(),currentCollection);
                List<View> currentCollectionViews=new ArrayList<>();collectViews(collectionView.getRoot(),currentCollectionViews);
                same &= currentCollection.equals(collectionInputs) && currentCollectionViews.equals(collectionViews)
                        && collectionSaved.equals(LocalStore.load(wallet).toString())
                        && collectionImages.equals(collectionImageSnapshot())
                        && collectionIcon.equals(field(collectionView,"colIconB64")) && collectionStatus.equals(wallet.mintStatus())
                        && Boolean.valueOf(collectionEmbed).equals(field(collectionView,"colEmbedMode"))
                        && Boolean.valueOf(collectionIconEmbed).equals(field(collectionView,"colIconEmbed")) && !MintDriver.isRunning();
                for(int i=0;i<collectionInputs.size();i++) {
                    EditText input=collectionInputs.get(i);same &= collectionDrafts.get(i).contentEquals(input.getText())
                            && input.getSelectionStart()==Math.min(2,input.length()) && input.getSelectionEnd()==Math.min(5,input.length());
                }
                if("collection-starting".equals(mode))same &= heldReply!=null && heldReply==expectedHeldReply;
            }
            if(mintMode()) {
                List<EditText> currentMint=new ArrayList<>();collectInputs(mint.getRoot(),currentMint);
                List<View> currentMintViews=new ArrayList<>();collectViews(mint.getRoot(),currentMintViews);
                same &= currentMint.equals(mintInputs) && currentMintViews.equals(mintViews)
                        && mintSaved.equals(LocalStore.load(wallet).toString()) && mintImage.equals(field(mint,"nftImageB64"))
                        && Boolean.valueOf(mintEmbed).equals(field(mint,"nftEmbedMode"));
                for(int i=0;i<mintInputs.size();i++) {
                    EditText input=mintInputs.get(i);same &= mintDrafts.get(i).contentEquals(input.getText())
                            && input.getSelectionStart()==Math.min(2,input.length()) && input.getSelectionEnd()==Math.min(5,input.length());
                }
                if("mint-progress".equals(mode))same &= heldReply!=null && heldReply==expectedHeldReply;
            }
            if(toolsMode()) {
                List<EditText> currentTools=new ArrayList<>();collectInputs(dialogRoot,currentTools);
                same &= currentTools.equals(toolInputs) && distribution==wallet.distribute()
                        && distributionJob==field(distribution,"job")
                        && distributionJson.equals(distributionJob==null?"":distributionJob.toJson().toString())
                        && distributionSaved.equals(getSharedPreferences("nftwallet_distribute",MODE_PRIVATE).getString("job",""))
                        && Boolean.valueOf(distributionStarting).equals(field(distribution,"starting"))
                        && Boolean.valueOf(distributionInFlight).equals(field(distribution,"inFlight"));
                for(int i=0;i<toolInputs.size();i++) same &= toolDrafts.get(i).contentEquals(toolInputs.get(i).getText())
                        && toolInputs.get(i).getSelectionStart()==2 && toolInputs.get(i).getSelectionEnd()==5;
                if("tools-starting".equals(mode)) same &= heldReply!=null && heldReply==expectedHeldReply;
            }
            if(nftMode()) {
                List<EditText> currentNft=new ArrayList<>();collectInputs(dialogRoot,currentNft);
                same &= nftInputs.equals(currentNft);
                for(EditText input:nftInputs) same &= DRAFT.contentEquals(input.getText())
                        && input.getSelectionStart()==2 && input.getSelectionEnd()==5;
                if(nftProgress()) same &= heldReply!=null && heldReply==expectedHeldReply;
            }
            List<EditText> current = new ArrayList<>(); collectInputs(send.getRoot(), current);
            same &= current.equals(inputs);
            for (int i = 0; i < inputs.size(); i++) same &= values.get(i).contentEquals(inputs.get(i).getText())
                    && inputs.get(i).getSelectionStart() == 2 && inputs.get(i).getSelectionEnd() == 5;
            JSONObject checks = new JSONObject()
                    .put("same_pages", pages == field(wallet,"views"))
                    .put("same_node", wallet.node()==fake).put("sending", field(send,"sending"))
                    .put("selected", wallet.selectedCoins().size()).put("total", wallet.selectedTotalString())
                    .put("attached", dialogRoot.isAttachedToWindow()).put("commands", commands==expectedCommands)
                    .put("same_qr", sameQr())
                    .put("qr_baseline", qr == null ? "null" : qr.getWidth()+"x"+qr.getHeight())
                    .put("qr_current", qrBitmap() == null ? "null" : qrBitmap().getWidth()+"x"+qrBitmap().getHeight())
                    .put("qr_pixels", qr != null && qrBitmap()!=null && qr.sameAs(qrBitmap()))
                    .put("qr_identity", qr == qrBitmap())
                    .put("qr_tag", String.valueOf(pages[WalletActivity.TAB_RECEIVE].getRoot().findViewById(R.id.rcvQr).getTag()))
                    .put("same_children", sameChildren).put("same_inputs",current.equals(inputs));
            JSONArray drafts = new JSONArray();
            for (EditText input : inputs) drafts.put(new JSONObject().put("id",getResources().getResourceEntryName(input.getId()))
                    .put("value",input.getText().toString()).put("start",input.getSelectionStart()).put("end",input.getSelectionEnd()));
            checks.put("drafts",drafts);
            if ("send-progress".equals(mode)) checks.put("same_pending_sign_reply",heldReply==expectedHeldReply && heldReply!=null);
            if (sendMode()) checks.put("quick_mode",field(send,"quickMode")).put("quick_token",field(send,"quickTokenid"))
                    .put("change_touched",field(send,"changeTouched"))
                    .put("preview_enabled",pages[WalletActivity.TAB_SEND].getRoot().findViewById(R.id.previewBtn).isEnabled());
            if (galleryMode()) checks.put("gallery_items",galleryItems.size()).put("gallery_collection",galleryOpen)
                    .put("gallery_favourites",new JSONArray(galleryFavourites)).put("gallery_search",gallerySearch.getText().toString())
                    .put("gallery_copied_token", "gallery-detail".equals(mode) ? ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText() : "");
            if(nftMode()) checks.put("nft_inputs",nftInputs.size()).put("nft_commands",new JSONArray(nftCommands))
                    .put("nft_pending_reply",nftProgress() && heldReply!=null && heldReply==expectedHeldReply);
            if(collectionMode())checks.put("collection_saved",collectionSaved).put("collection_images",collectionImages)
                    .put("collection_icon",collectionIcon).put("collection_drafts",new JSONArray(collectionDrafts))
                    .put("collection_status",collectionStatus).put("collection_embed",collectionEmbed).put("collection_icon_embed",collectionIconEmbed)
                    .put("collection_pending_reply",heldReply!=null && heldReply==expectedHeldReply);
            if(mintMode()) checks.put("mint_drafts",new JSONArray(mintDrafts)).put("mint_commands",new JSONArray(mintCommands))
                    .put("mint_pending_reply",heldReply!=null && heldReply==expectedHeldReply).put("mint_image",mintImage)
                    .put("mint_saved",mintSaved).put("mint_embed",mintEmbed);
            if(toolsMode()) checks.put("tool_inputs",toolInputs.size()).put("tool_drafts",new JSONArray(toolDrafts))
                    .put("distribution_job",distributionJson).put("distribution_saved",distributionSaved)
                    .put("distribution_starting",distributionStarting).put("distribution_in_flight",distributionInFlight)
                    .put("tool_pending_reply",heldReply!=null && heldReply==expectedHeldReply);
            JSONArray labels = new JSONArray(); collectLabels(dialogRoot, labels);
            JSONObject result = new JSONObject().put("same_views_and_drafts", same)
                    .put("locale", Language.code()).put("mode", mode).put("labels", labels)
                    .put("checks",checks).put("commands", commands).put("created", created).put("destroyed", destroyed).put("input_count", inputs.size());
            try (java.io.FileOutputStream out = openFileOutput("language-node-wallet-proof.json", MODE_PRIVATE)) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String collectionImageSnapshot() throws Exception {
        JSONObject result = new JSONObject();
        java.util.Map<Integer, String> images = (java.util.Map<Integer, String>) field(collectionView, "colImages");
        for (Integer index : new java.util.TreeSet<>(images.keySet()))
            result.put(Integer.toString(index), images.get(index));
        return result.toString();
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void collectViews(View view, List<View> out) {
        out.add(view);
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) collectViews(((ViewGroup)view).getChildAt(i),out);
    }
    private static void collectInputs(View view, List<EditText> out) {
        if (view instanceof EditText) out.add((EditText)view);
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) collectInputs(((ViewGroup)view).getChildAt(i),out);
    }
    private static void collectLabels(View view, JSONArray out) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            if (text.getText().length()>0) out.put(text.getText().toString());
            if (text.getHint()!=null) out.put(text.getHint().toString());
        }
        if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) collectLabels(((ViewGroup)view).getChildAt(i),out);
    }
    @Override protected void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        if (callbacks != null) getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        super.onDestroy();
    }
}
