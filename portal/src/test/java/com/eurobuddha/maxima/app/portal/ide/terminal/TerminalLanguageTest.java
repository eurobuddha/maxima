package com.eurobuddha.maxima.app.portal.ide.terminal;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import org.junit.Test;
import com.eurobuddha.maxima.app.i18n.Language;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class TerminalLanguageTest {
    @Test public void actualRiskWarningsHaveTranslationsWithoutChangingCommandChecks() throws Exception {
        assertEquals(R.string.l10n_terminal_warn_coins, resource(CommandRegistry.dangerWarning("coins")));
        assertEquals(R.string.l10n_terminal_warn_history, resource(CommandRegistry.dangerWarning("status; history max:100")));
        assertEquals(R.string.l10n_terminal_warn_tree, resource(CommandRegistry.dangerWarning("printtree")));
        for(String command:new String[]{"coins relevant:true","coins depth:4","history max:8 offset:8","printtree depth:10"})
            assertNull(CommandRegistry.dangerWarning(command));
        assertNotNull(CommandRegistry.dangerWarning("history max:999999999999999999999999999"));
    }
    private static int resource(String raw) throws Exception {
        CharSequence value=SystemMessages.message(raw);
        assertTrue(value instanceof Language.Message);
        Field field=Language.Message.class.getDeclaredField("resource");field.setAccessible(true);
        return field.getInt(value);
    }
    @Test public void commandsParametersAndUnknownDiagnosticsStayLiteral() {
        String usage=CommandRegistry.get("txnoutput").usage();
        assertEquals("txnoutput  id:  amount:  address:  tokenid:  storestate:true|false",usage);
        String raw="Node 中文 Русский {count} $1";
        assertEquals(raw,SystemMessages.message(raw));
    }
    @Test public void allBundledSummariesTranslateOnlyTheirDescription() throws Exception {
        org.json.JSONObject help = help();
        Field arguments = Language.Message.class.getDeclaredField("arguments"); arguments.setAccessible(true);
        for (java.util.Iterator<String> names=help.keys(); names.hasNext();) {
            String command=names.next();
            String brief = help.getJSONObject(command).getString("help");
            String description = ParamDocs.stripParams(brief);
            assertTrue(command, SystemMessages.message(description) instanceof Language.Message);
            Object[] parts = (Object[])arguments.get(HelpStore.displayBrief(brief));
            assertEquals(command, brief, parts[0] + description);
            assertTrue(command, parts[1] instanceof Language.Message);
        }
        assertEquals("", HelpStore.displayBrief(null));
    }
    @Test public void translatedPagesKeepExamplesAndMatchOriginalAsset() throws Exception {
        org.json.JSONObject help=help();
        org.json.JSONObject catalog=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../localization/catalog.json")),java.nio.charset.StandardCharsets.UTF_8));
        Field asset=HelpStore.class.getDeclaredField("HELP"); asset.setAccessible(true);
        Object previous=asset.get(null); asset.set(null,help);
        try {
        int translatedPages=0;
        for(java.util.Iterator<String> names=help.keys();names.hasNext();) {
            String command=names.next();
            assertTrue("Missing translated page: "+command,catalog.has("commandHelp."+command+"Page"));
            translatedPages++;
            String english=help.getJSONObject(command).getString("fullhelp").trim();
            org.json.JSONObject entry=catalog.getJSONObject("commandHelp."+command+"Page");
            assertEquals(command,command,english.split("\n")[0].trim());
            assertEquals(command,english,entry.getString("en"));
            for(ParamDocs.Doc doc:ParamDocs.forCommand(null,command).values())
                if(!doc.desc.isEmpty())assertTrue(command+"/"+doc.desc,SystemMessages.message(doc.desc) instanceof Language.Message);
            assertTrue(command,SystemMessages.message(english) instanceof Language.Message);
            assertTrue(command,english.contains("Examples:"));
            java.util.Set<String> accepted=new java.util.HashSet<>();
            for(CommandRegistry.Param parameter:CommandRegistry.get(command).params)accepted.add(parameter.name);
            for(String line:english.substring(0,english.indexOf("Examples:")).split("\n")) {
                java.util.regex.Matcher header=java.util.regex.Pattern.compile("^(\\w+):(?:\\s*\\([^)]*\\).*)?\\s*$").matcher(line.trim());
                if(header.matches())assertTrue(command+" documents rejected parameter "+header.group(1),accepted.contains(header.group(1)));
            }
            String examples=english.substring(english.indexOf("Examples:")+"Examples:".length());
            for(String code:new String[]{"zh-Hans","ru"}) {
                String translated=entry.getString(code), marker=code.equals("ru")?"Примеры:":"示例：";
                assertEquals(command,command,translated.split("\n")[0].trim());
                assertEquals(command,examples,translated.substring(translated.indexOf(marker)+marker.length()));
                for(CommandRegistry.Param parameter:CommandRegistry.get(command).params)
                    assertTrue(command+"/"+parameter.name,translated.contains("\n"+parameter.name+":"));
            }
        }
        assertEquals("Every bundled reference page must be translated",help.length(),translatedPages);
        assertEquals(115,translatedPages);
        } finally { asset.set(null,previous); }
    }
    @Test public void completionStillUsesRawRequiredMarkersAndProtocolValues() throws Exception {
        Field help=HelpStore.class.getDeclaredField("HELP"); help.setAccessible(true);
        Object previous=help.get(null); help.set(null,help());
        try {
            java.util.Map<String,ParamDocs.Doc> docs=ParamDocs.forCommand(null,"txnoutput");
            for(String name:new String[]{"id","amount","address"})assertTrue(docs.get(name).required);
            for(String name:new String[]{"tokenid","storestate"})assertFalse(docs.get(name).required);
            assertEquals("The amount for the output. To send to the specified address.",docs.get("amount").desc);
            String input="txnoutput id:demo ";
            Suggest.Item amount=Suggest.suggest(null,input,input.length()).items.get(0);
            assertEquals("amount:",amount.label);
            assertEquals("txnoutput id:demo amount:",Suggest.apply(input,input.length(),amount));
            input="txnoutput storestate:f";
            Suggest.Item value=Suggest.suggest(null,input,input.length()).items.get(0);
            assertEquals("false",value.label);
            assertEquals("txnoutput storestate:false ",Suggest.apply(input,input.length(),value));
        } finally { help.set(null,previous); }
    }
    private static org.json.JSONObject help() throws Exception {
        return new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/help.json")),java.nio.charset.StandardCharsets.UTF_8));
    }
}
