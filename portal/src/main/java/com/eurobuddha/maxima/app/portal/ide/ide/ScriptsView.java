package com.eurobuddha.maxima.app.portal.ide.ide;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

import com.eurobuddha.maxima.app.portal.ide.BaseView;
import com.eurobuddha.maxima.app.portal.ide.NodeApi;
import com.eurobuddha.maxima.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Script library tab: list, create (blank/template), open in editor, duplicate/delete,
 *  plus a browser for scripts already registered on the node (with import). */
public class ScriptsView extends BaseView {

    ScriptDB mDB;
    NodeApi mNode;
    ListView mList;
    TextView mEmpty;
    List<ScriptDB.Script> mScripts = new ArrayList<>();

    public ScriptsView(Activity zActivity) {
        super(zActivity, R.layout.view_scripts);

        mDB = new ScriptDB(zActivity);
        mList = mMainView.findViewById(R.id.scripts_list);
        mEmpty = mMainView.findViewById(R.id.scripts_empty);

        Button add = mMainView.findViewById(R.id.scripts_new);
        add.setOnClickListener(v -> newScriptDialog());

        Button node = mMainView.findViewById(R.id.scripts_node);
        node.setOnClickListener(v -> showNodeScripts());

        mList.setOnItemClickListener((parent, view, pos, id) -> openEditor(mScripts.get(pos).id));
        mList.setOnItemLongClickListener((parent, view, pos, id) -> {
            scriptActionsDialog(mScripts.get(pos));
            return true;
        });

        refreshView();
    }

    @Override
    public void refreshView() {
        mScripts = mDB.getAll();
        List<CharSequence> labels = new ArrayList<>();
        for (ScriptDB.Script s : mScripts) {
            String deployed = s.address.isEmpty() ? "" : "  ⛓ " + shorten(s.address);
            labels.add(Language.join(s.name, "\n",
                    Language.count(R.plurals.l10n_scripts_characters, s.source.trim().length()),
                    " · ", Language.since(s.modified), deployed));
        }
        mList.setAdapter(new ArrayAdapter<CharSequence>(mActivity, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View row = super.getView(position, recycled, parent);
                Language.setText((TextView)row.findViewById(android.R.id.text1), getItem(position));
                return row;
            }
        });
        mEmpty.setVisibility(mScripts.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private static String shorten(String addr) {
        return addr.length() > 14 ? addr.substring(0, 10) + "…" + addr.substring(addr.length() - 4) : addr;
    }

    /** Labels follow the existing template indices; executable source stays unchanged. */
    private static CharSequence[] templateLabels() {
        return new CharSequence[]{msg(R.string.l10n_scripts_blank), msg(R.string.l10n_scripts_signature),
                msg(R.string.l10n_scripts_timelock), msg(R.string.l10n_scripts_hashlock),
                msg(R.string.l10n_scripts_multisig), msg(R.string.l10n_scripts_random),
                msg(R.string.l10n_scripts_covenant)};
    }

    private void newScriptDialog() {
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_scripts_from_template))
                .setItems(templateLabels(), (d, which) -> nameDialog(which))
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void nameDialog(int templateIdx) {
        EditText name = new EditText(mActivity);
        Language.hint(name, msg(R.string.l10n_scripts_name));
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_scripts_name_title))
                .setView(name)
                .setPositiveButton(msg(R.string.l10n_scripts_create), (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = templateIdx == 0
                            ? msg(R.string.l10n_scripts_untitled).toString() : templateLabels()[templateIdx].toString();
                    long id = mDB.insert(n, Templates.ALL[templateIdx][1]);
                    refreshView();
                    openEditor(id);
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void scriptActionsDialog(ScriptDB.Script s) {
        new LanguageDialog(mActivity)
                .setTitle(s.name)
                .setItems(new CharSequence[]{msg(R.string.l10n_scripts_open), msg(R.string.l10n_scripts_duplicate), msg(R.string.l10n_scripts_delete)}, (d, which) -> {
                    if (which == 0) {
                        openEditor(s.id);
                    } else if (which == 1) {
                        mDB.insert(msg(R.string.l10n_scripts_copy_name, s.name).toString(), s.source);
                        refreshView();
                    } else {
                        new LanguageDialog(mActivity)
                                .setTitle(msg(R.string.l10n_scripts_delete_title, s.name))
                                .setMessage(msg(R.string.l10n_scripts_delete_body))
                                .setPositiveButton(msg(R.string.l10n_scripts_delete), (dd, ww) -> {
                                    mDB.delete(s.id);
                                    refreshView();
                                    Toast.makeText(mActivity, msg(R.string.l10n_terminal_deleted), Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                                .show();
                    }
                })
                .setNegativeButton(msg(R.string.l10n_language_close), null)
                .show();
    }

    private void openEditor(long scriptId) {
        Intent i = new Intent(mActivity, ScriptEditorActivity.class);
        i.putExtra("script_id", scriptId);
        mActivity.startActivity(i);
    }

    public void setNodeApi(NodeApi zNode) {
        mNode = zNode;
    }

    @Override
    public void onDestroy() {
        if (mDB != null) mDB.close();
    }

    /** Browse scripts registered on the node (the `scripts` command) and import them. */
    private void showNodeScripts() {
        if (mNode == null) return;
        Toast.makeText(mActivity, msg(R.string.l10n_scripts_loading), Toast.LENGTH_SHORT).show();
        mNode.cmd("scripts", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) {
                    Toast.makeText(mActivity, msg(R.string.l10n_scripts_node_empty), Toast.LENGTH_SHORT).show();
                    return;
                }
                int n = arr.length();
                CharSequence[] labels = new CharSequence[n];
                String[] scripts = new String[n];
                String[] addresses = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) {   // malformed element — don't NPE
                        scripts[i] = "";
                        addresses[i] = "";
                        labels[i] = msg(R.string.l10n_scripts_unreadable);
                        continue;
                    }
                    scripts[i] = s.optString("script", "");
                    addresses[i] = s.optString("address", "");
                    boolean def = s.optBoolean("default", false);
                    String snippet = scripts[i].length() > 60
                            ? scripts[i].substring(0, 60) + "…" : scripts[i];
                    labels[i] = def ? msg(R.string.l10n_scripts_wallet_entry, shorten(addresses[i]), snippet)
                            : shorten(addresses[i]) + "\n" + snippet;
                }
                new LanguageDialog(mActivity)
                        .setTitle(msg(R.string.l10n_scripts_node_title, n))
                        .setItems(labels, (d, which) -> nodeScriptActions(
                                addresses[which], scripts[which]))
                        .setNegativeButton(msg(R.string.l10n_language_close), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(mActivity, msg(R.string.l10n_scripts_node_error, SystemMessages.message(message)), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void nodeScriptActions(String address, String script) {
        new LanguageDialog(mActivity)
                .setTitle(shorten(address))
                .setMessage(script)
                .setPositiveButton(msg(R.string.l10n_scripts_import), (d, w) -> {
                    long id = mDB.insert(msg(R.string.l10n_scripts_from_node, shorten(address)).toString(), script);
                    mDB.setAddress(id, address);
                    refreshView();
                    openEditor(id);
                })
                .setNeutralButton(msg(R.string.l10n_contacts_copy_address), (d, w) -> {
                    android.content.ClipboardManager cb = (android.content.ClipboardManager)
                            mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(android.content.ClipData.newPlainText(msg(R.string.l10n_contacts_copy_address), address));
                    Toast.makeText(mActivity, msg(R.string.l10n_common_copied), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }
}
