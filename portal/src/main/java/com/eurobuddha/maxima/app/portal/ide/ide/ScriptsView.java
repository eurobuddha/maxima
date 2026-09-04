package com.eurobuddha.maxima.app.portal.ide.ide;

import android.app.Activity;
import android.content.Intent;
import android.text.format.DateUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

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
        List<String> labels = new ArrayList<>();
        for (ScriptDB.Script s : mScripts) {
            String when = DateUtils.getRelativeTimeSpanString(s.modified).toString();
            String deployed = s.address.isEmpty() ? "" : "  ⛓ " + shorten(s.address);
            labels.add(s.name + "\n" + s.source.trim().length() + " chars · " + when + deployed);
        }
        mList.setAdapter(new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1, labels));
        mEmpty.setVisibility(mScripts.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private static String shorten(String addr) {
        return addr.length() > 14 ? addr.substring(0, 10) + "…" + addr.substring(addr.length() - 4) : addr;
    }

    private void newScriptDialog() {
        new AlertDialog.Builder(mActivity)
                .setTitle("New script from…")
                .setItems(Templates.names(), (d, which) -> nameDialog(which))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void nameDialog(int templateIdx) {
        EditText name = new EditText(mActivity);
        name.setHint("Script name");
        new AlertDialog.Builder(mActivity)
                .setTitle("Name the script")
                .setView(name)
                .setPositiveButton("Create", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = Templates.ALL[templateIdx][0].equals("Blank")
                            ? "untitled" : Templates.ALL[templateIdx][0];
                    long id = mDB.insert(n, Templates.ALL[templateIdx][1]);
                    refreshView();
                    openEditor(id);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void scriptActionsDialog(ScriptDB.Script s) {
        new AlertDialog.Builder(mActivity)
                .setTitle(s.name)
                .setItems(new String[]{"Open", "Duplicate", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        openEditor(s.id);
                    } else if (which == 1) {
                        mDB.insert(s.name + " copy", s.source);
                        refreshView();
                    } else {
                        new AlertDialog.Builder(mActivity)
                                .setTitle("Delete '" + s.name + "'?")
                                .setMessage("This only deletes the local copy. A deployed script "
                                        + "stays on the node (use removescript to untrack it).")
                                .setPositiveButton("Delete", (dd, ww) -> {
                                    mDB.delete(s.id);
                                    refreshView();
                                    Toast.makeText(mActivity, "Deleted", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Close", null)
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
        Toast.makeText(mActivity, "Loading node scripts…", Toast.LENGTH_SHORT).show();
        mNode.cmd("scripts", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) {
                    Toast.makeText(mActivity, "No scripts on the node", Toast.LENGTH_SHORT).show();
                    return;
                }
                int n = arr.length();
                String[] labels = new String[n];
                String[] scripts = new String[n];
                String[] addresses = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) {   // malformed element — don't NPE
                        scripts[i] = "";
                        addresses[i] = "";
                        labels[i] = "(unreadable script entry)";
                        continue;
                    }
                    scripts[i] = s.optString("script", "");
                    addresses[i] = s.optString("address", "");
                    boolean def = s.optBoolean("default", false);
                    String snippet = scripts[i].length() > 60
                            ? scripts[i].substring(0, 60) + "…" : scripts[i];
                    labels[i] = (def ? "[wallet] " : "") + shorten(addresses[i]) + "\n" + snippet;
                }
                new AlertDialog.Builder(mActivity)
                        .setTitle("Scripts on node (" + n + ")")
                        .setItems(labels, (d, which) -> nodeScriptActions(
                                addresses[which], scripts[which]))
                        .setNegativeButton("Close", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(mActivity, "Node error: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void nodeScriptActions(String address, String script) {
        new AlertDialog.Builder(mActivity)
                .setTitle(shorten(address))
                .setMessage(script)
                .setPositiveButton("Import to library", (d, w) -> {
                    long id = mDB.insert("from node " + shorten(address), script);
                    mDB.setAddress(id, address);
                    refreshView();
                    openEditor(id);
                })
                .setNeutralButton("Copy address", (d, w) -> {
                    android.content.ClipboardManager cb = (android.content.ClipboardManager)
                            mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("address", address));
                    Toast.makeText(mActivity, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
