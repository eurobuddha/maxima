package com.eurobuddha.maxima.app.ui;

import android.app.Activity;
import android.widget.TextView;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;

import androidx.appcompat.app.AlertDialog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What every number and every setting in this app actually means.
 *
 * Maxima exposes real transport internals - hosts, routing keys, a directory,
 * a mailbox, contribution counters - and a number with no explanation is worse
 * than no number: it looks like something is wrong and gives you no way to
 * decide. Every figure on screen has an entry here, reachable by tapping the
 * "?" beside it.
 *
 * Written for someone who has never heard of Maxima. No jargon goes
 * unexplained on its first use.
 */
public final class Explain {

    private static final Map<String, CharSequence[]> ENTRIES = new LinkedHashMap<>();

    private static void put(String zKey, CharSequence zTitle, CharSequence zBody) {
        ENTRIES.put(zKey, new CharSequence[]{zTitle, zBody});
    }

    static {
        put("status", Language.msg(R.string.l10n_help_status_title), Language.msg(R.string.l10n_help_status_mobile_body));
        put("address", Language.msg(R.string.l10n_help_address_title), Language.msg(R.string.l10n_help_address_mobile_body));
        put("hosts", Language.msg(R.string.l10n_help_hosts_title), Language.msg(R.string.l10n_help_hosts_mobile_body));
        put("mls", Language.msg(R.string.l10n_help_mls_title), Language.msg(R.string.l10n_help_mls_body));
        put("contacts", Language.msg(R.string.l10n_help_contacts_title), Language.msg(R.string.l10n_help_contacts_body));
        put("mailbox", Language.msg(R.string.l10n_help_mailbox_title), Language.msg(R.string.l10n_help_mailbox_mobile_body));
        put("outbox", Language.msg(R.string.l10n_help_outbox_title), Language.msg(R.string.l10n_help_outbox_body));
        put("services", Language.msg(R.string.l10n_help_services_title), Language.msg(R.string.l10n_help_services_mobile_body));
        put("contribution", Language.msg(R.string.l10n_help_contribution_mobile_title), Language.msg(R.string.l10n_help_contribution_mobile_body));
        put("witness", Language.msg(R.string.l10n_help_witness_title), Language.msg(R.string.l10n_help_witness_body));
        put("storage", Language.msg(R.string.l10n_help_storage_title), Language.msg(R.string.l10n_help_storage_body));
        put("direct", Language.msg(R.string.l10n_help_direct_title), Language.msg(R.string.l10n_help_direct_mobile_body));
        put("gates", Language.msg(R.string.l10n_help_gates_title), Language.msg(R.string.l10n_help_gates_body));
        put("counters", Language.msg(R.string.l10n_help_counters_title), Language.msg(R.string.l10n_help_counters_body));
        put("ticks", Language.msg(R.string.l10n_help_ticks_title), Language.msg(R.string.l10n_help_ticks_mobile_body));
        put("readreceipts", Language.msg(R.string.l10n_help_readreceipts_title), Language.msg(R.string.l10n_help_readreceipts_mobile_body));
        put("groups", Language.msg(R.string.l10n_help_groups_title), Language.msg(R.string.l10n_help_groups_mobile_body));
        put("name", Language.msg(R.string.l10n_help_name_title), Language.msg(R.string.l10n_help_name_mobile_body));
        put("appearance", Language.msg(R.string.l10n_help_appearance_title), Language.msg(R.string.l10n_help_appearance_mobile_body));
        put("seed", Language.msg(R.string.l10n_help_seed_title), Language.msg(R.string.l10n_help_seed_body));
        put("relays", Language.msg(R.string.l10n_help_relays_title), Language.msg(R.string.l10n_help_relays_mobile_body));
        put("battery", Language.msg(R.string.l10n_help_battery_title), Language.msg(R.string.l10n_help_battery_body));
        put("log", Language.msg(R.string.l10n_help_log_title), Language.msg(R.string.l10n_help_log_body));
        put("ipc", Language.msg(R.string.l10n_help_ipc_title), Language.msg(R.string.l10n_help_ipc_body));
        put("nodekind", Language.msg(R.string.l10n_help_nodekind_title), Language.msg(R.string.l10n_help_nodekind_mobile_body));
    }

    private Explain() {
    }

    public static CharSequence title(String zKey) {
        CharSequence[] e = ENTRIES.get(zKey);
        return e == null ? zKey : e[0];
    }

    public static CharSequence body(String zKey) {
        CharSequence[] e = ENTRIES.get(zKey);
        return e == null ? Language.msg(R.string.l10n_help_missing, zKey) : e[1];
    }

    public static void show(Activity zActivity, String zKey) {
        TextView heading = new TextView(zActivity);
        heading.setTextAppearance(android.R.style.TextAppearance_Material_Title);
        int padding = (int) (24 * zActivity.getResources().getDisplayMetrics().density);
        heading.setPadding(padding, padding, padding, padding / 2);
        Language.setText(heading, title(zKey));
        AlertDialog dialog = new AlertDialog.Builder(zActivity)
                .setCustomTitle(heading)
                .setMessage(body(zKey))
                .setPositiveButton(Language.text(zActivity, R.string.l10n_help_got_it), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) Language.setText(message, body(zKey));
            Language.setText(dialog.getButton(AlertDialog.BUTTON_POSITIVE), Language.msg(R.string.l10n_help_got_it));
        });
        dialog.show();
    }
}
