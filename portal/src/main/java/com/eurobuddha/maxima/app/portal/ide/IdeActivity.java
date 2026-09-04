package com.eurobuddha.maxima.app.portal.ide;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager.widget.ViewPager;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.portal.CloudSession;
import com.eurobuddha.maxima.app.portal.ide.terminal.SessionExport;
import com.eurobuddha.maxima.app.portal.ide.terminal.TerminalView;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;

/**
 * The Terminal IDE, inside Parlons Cloud: the Terminal IDE companion app (Terminal / Scripts /
 * Txn / Logs) ported whole, with every command running on the ACCOUNT's Parlons Node over the
 * paired, end-to-end encrypted control channel instead of local broadcast IPC. Nothing here
 * needs Minima Core on the phone — the node is the always-on VPS one.
 */
public class IdeActivity extends AppCompatActivity {

    private static final int MENU_CLEAR   = 1;
    private static final int MENU_ABOUT   = 2;
    private static final int MENU_EXPORT  = 3;
    private static final int MENU_DOCS    = 4;
    private static final int MENU_SELECT  = 5;
    private static final int MENU_COPYALL = 6;

    ViewPager mMainPager;
    TerminalAdapter mAdapter;
    NodeApi mNode;

    Toolbar mToolbar;
    TextView mBanner;

    /** Snapshot handed to the file picker; consumed by the launcher callback. */
    private String mPendingExport;
    private ActivityResultLauncher<Intent> mSaveLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ide);

        // Pads for bars + IME so the command line rises above the keyboard (adjustResize).
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);

        mToolbar = findViewById(R.id.toolbar);
        mToolbar.setTitle("Terminal IDE");
        mToolbar.setNavigationIcon(R.drawable.ic_back);
        mToolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(mToolbar);

        mBanner = findViewById(R.id.pair_banner);
        if (!CloudSession.isPaired(this)) {
            mBanner.setText("This device is not paired to an account yet - pair it from the "
                    + "Node tab first.");
            mBanner.setVisibility(View.VISIBLE);
        }

        // Save-to-file goes through the Storage Access Framework: no permissions, no size limit.
        mSaveLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
            String text = mPendingExport;
            mPendingExport = null;
            Uri uri = result.getData() == null ? null : result.getData().getData();
            if (result.getResultCode() != RESULT_OK || uri == null || text == null) return;
            try {
                SessionExport.writeTo(this, uri, text);
                Toast.makeText(this, "Session saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        mAdapter = new TerminalAdapter(this);
        mMainPager = findViewById(R.id.main_pager);
        mMainPager.setOffscreenPageLimit(3);
        mMainPager.setAdapter(mAdapter);

        TabLayout tabs = findViewById(R.id.tabs);
        tabs.setBackgroundColor(getColor(R.color.ide_bg_light));
        tabs.setTabTextColors(getColor(R.color.ide_dim), getColor(R.color.ide_text));
        tabs.setSelectedTabIndicatorColor(getColor(R.color.ide_accent));
        tabs.setupWithViewPager(mMainPager);
        String[] titles = {"Terminal", "Scripts", "Txn", "Logs"};
        for (int i = 0; i < titles.length; i++) {
            TabLayout.Tab t = tabs.getTabAt(i);
            if (t != null) t.setText(titles[i]);
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                // Select mode locks out pager swipes — never leave it armed off-tab.
                if (tab.getPosition() != 0 && mAdapter.getTerminalView().isSelectionMode()) {
                    mAdapter.getTerminalView().setSelectionMode(false);
                    invalidateOptionsMenu();
                }
                mAdapter.refreshPagerView(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) { mAdapter.refreshPagerView(tab.getPosition()); }
        });

        // One NodeApi for the whole activity: every command goes to the account's node.
        mNode = new NodeApi(this);
        mAdapter.getTerminalView().setNodeApi(mNode);
        mAdapter.getTerminalView().setExportAction(this::showExportDialog);
        mAdapter.getTxnView().setNodeApi(mNode);
        mAdapter.getScriptsView().setNodeApi(mNode);
        mAdapter.getLogsView().setNodeApi(mNode);

        fetchBlock();
    }

    private void fetchBlock() {
        mToolbar.setSubtitle("your node - asking for the block…");
        mNode.cmd("block", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                if (resp != null) setBlock(resp.optString("block", ""));
            }
            @Override
            public void onError(String message) {
                mToolbar.setSubtitle("node unreachable");
                mBanner.setText(message);
                mBanner.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setBlock(String block) {
        if (!block.isEmpty()) {
            mToolbar.setSubtitle("your node - block " + block);
            mBanner.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SELECT, 0, "Select & copy output");
        menu.add(0, MENU_COPYALL, 1, "Copy all output");
        menu.add(0, MENU_EXPORT, 2, "Export session…");
        menu.add(0, MENU_CLEAR, 3, "Clear terminal");
        menu.add(0, MENU_DOCS, 4, "Minima docs");
        menu.add(0, MENU_ABOUT, 5, "About");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem select = menu.findItem(MENU_SELECT);
        if (select != null && mAdapter != null) {
            select.setTitle(mAdapter.getTerminalView().isSelectionMode()
                    ? "Exit select mode" : "Select & copy output");
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /** Copy / save / share the whole terminal session. */
    private void showExportDialog() {
        final String text = mAdapter.getTerminalView().exportText();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show();
            return;
        }
        final String filename = SessionExport.timestampedName("parlons-node-terminal");
        String[] actions = {"Save to file…", "Share…", "Copy to clipboard"};
        new AlertDialog.Builder(this)
                .setTitle("Export session (" + (SessionExport.utf8Len(text) / 1024) + " KB)")
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        mPendingExport = text;
                        try {
                            mSaveLauncher.launch(SessionExport.createDocumentIntent(filename));
                        } catch (ActivityNotFoundException e) {
                            mPendingExport = null;
                            Toast.makeText(this, "No file picker on this device",
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 1) {
                        SessionExport.share(this, text, filename, "Share terminal session");
                    } else {
                        SessionExport.copy(this, text, "Parlons Node terminal session");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        TerminalView terminal = mAdapter.getTerminalView();
        if (item.getItemId() == MENU_CLEAR) {
            terminal.clearTerminal();
            return true;
        }
        if (item.getItemId() == MENU_SELECT) {
            mMainPager.setCurrentItem(0);
            terminal.setSelectionMode(!terminal.isSelectionMode());
            invalidateOptionsMenu();
            return true;
        }
        if (item.getItemId() == MENU_COPYALL) {
            terminal.copyAll();
            return true;
        }
        if (item.getItemId() == MENU_EXPORT) {
            showExportDialog();
            return true;
        }
        if (item.getItemId() == MENU_DOCS) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.minima.global")));
            return true;
        }
        if (item.getItemId() == MENU_ABOUT) {
            new AlertDialog.Builder(this)
                    .setTitle("Terminal IDE")
                    .setMessage("Your Parlons Node's tooling, on your phone:\n\n"
                            + "• Terminal — the full node command line with history, autocomplete "
                            + "and colorized output\n"
                            + "• Scripts — KISS VM editor with lint, offline testing (the node's "
                            + "own VM) and deploy\n"
                            + "• Txn — guided manual-UTXO transaction workbench\n"
                            + "• Logs — the node's rolling event log\n\n"
                            + "Every command runs on the account's node over the paired, "
                            + "end-to-end encrypted channel; output comes back complete, never cut. "
                            + "Long-press any output block to copy or share it.")
                    .setPositiveButton("OK", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fetchBlock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mNode != null) mNode.onDestroy();
        if (mAdapter != null) mAdapter.onDestroy();
    }
}
