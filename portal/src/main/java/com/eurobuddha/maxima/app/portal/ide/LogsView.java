package com.eurobuddha.maxima.app.portal.ide;

import android.app.Activity;
import android.widget.TextView;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

import org.json.JSONArray;
import org.json.JSONObject;

/** The account node's rolling event log — what the node itself has been doing. */
public class LogsView extends BaseView {

    private final TextView mMainText;
    private NodeApi mNode;

    public LogsView(Activity zActivity) {
        super(zActivity, R.layout.view_logs);
        mMainText = getMainView().findViewById(R.id.logs_maintext);
        Language.setText(mMainText, msg(R.string.l10n_node_ide_log_hint));
    }

    public void setNodeApi(NodeApi zNode) {
        mNode = zNode;
    }

    @Override
    public void refreshView() {
        if (mNode == null) return;
        mNode.nodeLog(new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray lines = json.optJSONArray("lines");
                StringBuilder sb = new StringBuilder();
                if (lines != null) {
                    for (int i = 0; i < lines.length(); i++) {
                        sb.append(lines.optString(i)).append('\n');
                    }
                }
                Language.setText(mMainText, sb.length() == 0 ? msg(R.string.l10n_node_ide_log_empty) : sb.toString());
            }
            @Override public void onError(String message) {
                Language.setText(mMainText, msg(R.string.l10n_node_ide_log_failed, SystemMessages.message(message)));
            }
        });
    }
}
