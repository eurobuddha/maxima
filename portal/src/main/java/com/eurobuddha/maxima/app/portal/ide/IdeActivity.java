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
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import static com.eurobuddha.maxima.app.i18n.Language.msg;
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
    private CharSequence mNodeStatus = "";

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
        mToolbar.setTitle(msg(R.string.l10n_node_ide_title));
        mToolbar.setNavigationIcon(R.drawable.ic_back);
        mToolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(mToolbar);

        mBanner = findViewById(R.id.pair_banner);
        if (!CloudSession.isPaired(this)) {
            Language.setText(mBanner, msg(R.string.l10n_node_ide_unpaired));
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
                Toast.makeText(this, msg(R.string.l10n_node_ide_saved), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, msg(R.string.l10n_node_ide_save_failed, SystemMessages.message(e.getMessage())), Toast.LENGTH_LONG).show();
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
        updateTabLabels();
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
        setNodeStatus(msg(R.string.l10n_node_ide_asking_block));
        mNode.cmd("block", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                if (resp != null) setBlock(resp.optString("block", ""));
            }
            @Override
            public void onError(String message) {
                setNodeStatus(msg(R.string.l10n_node_ide_unreachable));
                Language.setText(mBanner, SystemMessages.message(message));
                mBanner.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setBlock(String block) {
        if (!block.isEmpty()) {
            setNodeStatus(msg(R.string.l10n_node_ide_block, block));
            mBanner.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SELECT, 0, msg(R.string.l10n_node_ide_select_output));
        menu.add(0, MENU_COPYALL, 1, msg(R.string.l10n_node_ide_copy_output));
        menu.add(0, MENU_EXPORT, 2, msg(R.string.l10n_node_ide_export));
        menu.add(0, MENU_CLEAR, 3, msg(R.string.l10n_node_ide_clear));
        menu.add(0, MENU_DOCS, 4, msg(R.string.l10n_node_ide_docs));
        menu.add(0, MENU_ABOUT, 5, msg(R.string.l10n_native_settings_about));
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateMenuLabels(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    private void setNodeStatus(CharSequence status) {
        mNodeStatus = status;
        mToolbar.setSubtitle(status.toString());
    }

    private void updateTabLabels() {
        TabLayout tabs = findViewById(R.id.tabs);
        int[] titles = {R.string.l10n_node_ide_terminal, R.string.l10n_node_ide_scripts,
                R.string.l10n_node_ide_transactions, R.string.l10n_node_ide_logs};
        for (int i = 0; i < titles.length; i++) {
            TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab != null) tab.setText(msg(titles[i]).toString());
        }
    }

    private void updateMenuLabels(Menu menu) {
        int[] ids = {MENU_SELECT, MENU_COPYALL, MENU_EXPORT, MENU_CLEAR, MENU_DOCS, MENU_ABOUT};
        int[] labels = {mAdapter != null && mAdapter.getTerminalView().isSelectionMode()
                ? R.string.l10n_node_ide_exit_select : R.string.l10n_node_ide_select_output,
                R.string.l10n_node_ide_copy_output, R.string.l10n_node_ide_export,
                R.string.l10n_node_ide_clear, R.string.l10n_node_ide_docs, R.string.l10n_native_settings_about};
        for (int i = 0; i < ids.length; i++) {
            MenuItem item = menu.findItem(ids[i]);
            if (item != null) item.setTitle(msg(labels[i]).toString());
        }
    }

    /** Copy / save / share the whole terminal session. */
    private void showExportDialog() {
        final String text = mAdapter.getTerminalView().exportText();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, msg(R.string.l10n_node_ide_empty_export), Toast.LENGTH_SHORT).show();
            return;
        }
        final String filename = SessionExport.timestampedName("parlons-node-terminal");
        CharSequence[] actions = {msg(R.string.l10n_node_ide_save_file), msg(R.string.l10n_native_contacts_share), msg(R.string.l10n_node_ide_copy_clipboard)};
        new LanguageDialog(this)
                .setTitle(msg(R.string.l10n_node_ide_export_size, SessionExport.utf8Len(text) / 1024))
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        mPendingExport = text;
                        try {
                            mSaveLauncher.launch(SessionExport.createDocumentIntent(filename));
                        } catch (ActivityNotFoundException e) {
                            mPendingExport = null;
                            Toast.makeText(this, msg(R.string.l10n_node_ide_no_picker),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 1) {
                        SessionExport.share(this, text, filename, msg(R.string.l10n_node_ide_share_session));
                    } else {
                        SessionExport.copy(this, text, msg(R.string.l10n_node_ide_session_label).toString());
                    }
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
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
            new LanguageDialog(this)
                    .setTitle(msg(R.string.l10n_node_ide_title))
                    .setMessage(msg(R.string.l10n_node_ide_about))
                    .setPositiveButton(msg(R.string.l10n_common_ok), null)
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
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        if (mAdapter != null) mAdapter.languageChanged();
        if (mToolbar != null) {
            mToolbar.setTitle(msg(R.string.l10n_node_ide_title).toString());
            mToolbar.setSubtitle(mNodeStatus.toString());
            updateMenuLabels(mToolbar.getMenu());
        }
        updateTabLabels();
        TabLayout tabs = findViewById(R.id.tabs);
        if (tabs != null) tabs.post(() -> tabs.setScrollPosition(tabs.getSelectedTabPosition(), 0f, true));
    }

}
