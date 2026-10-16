package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

/** Display boundary for saved/raw mint diagnostics. Never use the result in state or node commands. */
final class MintMessages {
    private MintMessages() {}
    static CharSequence name(JSONObject row) {
        return row.has("name") && !row.isNull("name") ? row.optString("name") : msg(R.string.l10n_node_gallery_collection);
    }
    static CharSequence phase(String raw) {
        if (raw == null) return "";
        switch (raw) {
            case "CREATE": return msg(R.string.l10n_node_collection_phase_create);
            case "MOVE": return msg(R.string.l10n_node_collection_phase_move);
            case "SPLIT": return msg(R.string.l10n_node_collection_phase_split);
            case "STAMP": return msg(R.string.l10n_node_collection_phase_stamp);
            case "DONE": return msg(R.string.l10n_node_collection_phase_done);
            case "NEEDIMAGES": return msg(R.string.l10n_node_collection_phase_images);
            case "BURIED": return msg(R.string.l10n_node_collection_phase_buried);
            case "BURY": return msg(R.string.l10n_node_collection_phase_bury);
            default: return raw;
        }
    }
    private static final Pattern PHASE = Pattern.compile("Collection (.*) -> (CREATE|MOVE|SPLIT|STAMP|DONE|NEEDIMAGES|BURIED|BURY)", Pattern.DOTALL);
    private static final Pattern[] DIAGNOSTICS = {
        Pattern.compile("ambiguous mint: ([0-9]+) tokens match"),
        Pattern.compile("Waiting for tokencreate confirmation \\(([0-9]+)/([0-9]+) blocks\\)"),
        Pattern.compile("The create transaction hasn't confirmed after ([0-9]+) blocks\\. It may still land — re-post only if you're sure it was lost\\."),
        Pattern.compile("missing image for item #([0-9]+)"),
        Pattern.compile("Stamped item #([0-9]+)"),
        Pattern.compile("([0-9]+) embedded images still missing"),
        Pattern.compile("unbalanced txn ([A-Za-z0-9_]+)"),
        Pattern.compile("record ([0-9]+)B signed cannot split under the 64KB cap \\(metadata ([0-9]+)B > ([0-9]+)B\\) — shorter text or a hosted icon"),
        Pattern.compile("largest image ([0-9]+)B exceeds the ([0-9]+)B image budget the signed record leaves — smaller images or a lighter record")
    };
    private static final int[] MESSAGES = { R.string.l10n_node_collection_ambiguous, R.string.l10n_node_collection_waiting_create, R.string.l10n_node_collection_create_unconfirmed, R.string.l10n_node_collection_missing_item_image, R.string.l10n_node_collection_stamped_item, R.string.l10n_node_collection_missing_images, R.string.l10n_node_collection_unbalanced, R.string.l10n_node_collection_record_gate, R.string.l10n_node_collection_image_gate };
    static CharSequence status(String raw) {
        if (raw == null) return "";
        Matcher phase = PHASE.matcher(raw);
        if (phase.matches()) return msg(R.string.l10n_node_collection_phase_changed, phase.group(1), phase(phase.group(2)));
        // A failed tick's payload is a node diagnostic, not user content.
        String prefix = "Mint tick failed: ";
        if (raw.startsWith(prefix)) return msg(R.string.l10n_node_collection_tick_failed, SystemMessages.message(raw.substring(prefix.length())));
        for (int i = 0; i < DIAGNOSTICS.length; i++) {
            Matcher matcher = DIAGNOSTICS[i].matcher(raw);
            if (!matcher.matches()) continue;
            Object[] arguments = new Object[matcher.groupCount()];
            for (int n = 0; n < arguments.length; n++) arguments[n] = matcher.group(n + 1);
            return msg(MESSAGES[i], arguments);
        }
        return SystemMessages.message(raw);
    }
}
