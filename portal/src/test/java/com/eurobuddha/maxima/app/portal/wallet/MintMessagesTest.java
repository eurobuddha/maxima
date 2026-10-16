package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MintMessagesTest {
    @Test public void unknownDiagnosticsAndPartialMatchesRemainLiteral() {
        for (String raw : new String[]{"Node 中文 Русский {total} $1", "Stamped item #7 extra", "prefix Stamped item #7",
                "unbalanced txn unsafe;command", "Waiting for tokencreate confirmation (x/20 blocks)"})
            assertEquals(raw, MintMessages.status(raw));
        assertEquals("", MintMessages.status(null));
        assertEquals("NEW_PHASE", MintMessages.phase("NEW_PHASE"));
    }
    @Test public void namesNeverPassThroughSystemLookupOrTemplateExpansion() throws Exception {
        for (String name : new String[]{"Collection", "Done", "中文 Русский {phase} $1\n -> CREATE"}) {
            JSONObject row = new JSONObject().put("name", name).put("phase", "STAMP");
            String before = row.toString();
            assertEquals(name, MintMessages.name(row));
            CharSequence message = MintMessages.status("Collection " + name + " -> STAMP");
            assertEquals(R.string.l10n_node_collection_phase_changed, resource(message));
            assertEquals(name, arguments(message)[0]);
            assertEquals(R.string.l10n_node_collection_phase_stamp, resource(arguments(message)[1]));
            assertEquals(before, row.toString());
        }
        assertTrue(MintMessages.name(new JSONObject()) instanceof Language.Message);
    }
    @Test public void numericDiagnosticsKeepExactValuesAndLeadingZeroes() throws Exception {
        assertArrayEquals(new Object[]{"007"}, arguments(MintMessages.status("Stamped item #007")));
        assertArrayEquals(new Object[]{"2", "20"}, arguments(MintMessages.status("Waiting for tokencreate confirmation (2/20 blocks)")));
        assertArrayEquals(new Object[]{"22"}, arguments(MintMessages.status("22 embedded images still missing")));
        assertArrayEquals(new Object[]{"st7x2"}, arguments(MintMessages.status("unbalanced txn st7x2")));
        assertEquals(R.string.l10n_node_collection_create_unconfirmed, resource(MintMessages.status(
                "The create transaction hasn't confirmed after 20 blocks. It may still land — re-post only if you're sure it was lost.")));
    }
    @Test public void actualJointGateDiagnosticsTranslateWithoutChangingGate() throws Exception {
        String record = StateNft.jointGate(StateNft.META_MAX + 1, 0);
        assertEquals(R.string.l10n_node_collection_record_gate, resource(MintMessages.status(record)));
        assertArrayEquals(new Object[]{"17301", "8368", "8367"}, arguments(MintMessages.status(record)));
        int meta = 500, room = StateNft.imageBudget(meta);
        String image = StateNft.jointGate(meta, room + 1);
        assertEquals(R.string.l10n_node_collection_image_gate, resource(MintMessages.status(image)));
        assertArrayEquals(new Object[]{String.valueOf(room + 1), String.valueOf(room)}, arguments(MintMessages.status(image)));
        assertEquals("sign", StateNft.jointGate(meta, room));
    }
    @Test public void driverResultsAndFixedErrorsRemainDisplayOnly() throws Exception {
        for (MintDriver.Result result : MintDriver.Result.values()) {
            String raw = MintDriver.explain(result);
            assertTrue(MintMessages.status(raw) instanceof Language.Message);
            assertEquals(raw, MintDriver.explain(result));
        }
        CharSequence failed = MintMessages.status("Mint tick failed: Node 中文 Русский {error} $1");
        assertEquals("Node 中文 Русский {error} $1", arguments(failed)[0]);
    }
    private static Object[] arguments(Object value) throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f = Language.Message.class.getDeclaredField("arguments"); f.setAccessible(true); return (Object[]) f.get(value);
    }
    private static int resource(Object value) throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f = Language.Message.class.getDeclaredField("resource"); f.setAccessible(true); return f.getInt(value);
    }
}
