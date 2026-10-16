package com.eurobuddha.maxima.desktop.ui;

import java.awt.Font;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopLanguageTest {
    /** No disk preferences or account access in this suite. */
    static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();
        MemoryPreferences() { super(null, ""); }
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String k, String v) { values.put(k, v); }
        protected String getSpi(String k) { return values.get(k); }
        protected void removeSpi(String k) { values.remove(k); }
        protected void removeNodeSpi() { values.clear(); }
        protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        protected String[] childrenNamesSpi() { return children.keySet().toArray(new String[0]); }
        protected AbstractPreferences childSpi(String name) { return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n)); }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }

    @Test public void explicitChoiceIgnoresSystemLocaleAndSurvivesReload() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Locale previousLocale = Locale.getDefault();
            MemoryPreferences saved = new MemoryPreferences();
            Preferences previous = Language.usePreferences(saved);
            try {
                Locale.setDefault(Locale.CHINA);
                assertEquals("en", Language.code());
                assertFalse(Language.hasChoice());
                Language.choose("ru");
                Language.usePreferences(saved);
                assertEquals("ru", Language.code());
                assertTrue(Language.hasChoice());
                Language.choose("zh-Hans");
                Language.usePreferences(saved);
                assertEquals("zh-Hans", Language.code());
                try { Language.choose("auto"); fail("Auto choice accepted"); }
                catch (IllegalArgumentException expected) { }
                assertEquals("zh-Hans", Language.code());
                saved.put("parlons.language", "unsupported");
                Language.usePreferences(saved);
                assertEquals("en", Language.code());
                assertFalse(Language.hasChoice());
            } finally { Locale.setDefault(previousLocale); Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void cardinalRulesCoverRussianTeensAndNegativeCounts() {
        for (int n : new int[]{1, 21, 101, -21}) assertEquals("one", Language.plural("ru", n));
        for (int n : new int[]{2, 3, 4, 22, 104, -24}) assertEquals("few", Language.plural("ru", n));
        for (int n : new int[]{0, 5, 11, 12, 14, 25, 111, 112, -14}) assertEquals("many", Language.plural("ru", n));
        assertEquals("other", Language.plural("ru", 1.5));
        assertEquals("one", Language.plural("en", -1));
        assertEquals("other", Language.plural("en", 2));
        assertEquals("other", Language.plural("zh-Hans", 1));
        assertEquals("21 узел", Language.format("ru", "nativeShell.hosts", Map.of("count", 21)));
        assertEquals("22 узла", Language.format("ru", "nativeShell.hosts", Map.of("count", 22)));
        assertEquals("11 узлов", Language.format("ru", "nativeShell.hosts", Map.of("count", 11)));
    }

    @Test public void userArgumentsAreLiteralAndMissingArgumentsFailClearly() {
        String name = "Chats {count} $1 \\ 张三";
        assertEquals("Аккаунт: " + name + " · Parlons Desktop",
                Language.format("ru", "desktop.docsAccount", Map.of("name", name)));
        try { Language.format("ru", "desktop.docsAccount", Map.of()); fail("Missing argument accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void labelRefreshPreservesRealChatDraftDocumentAndSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new MemoryPreferences());
            JPanel root = new JPanel();
            Theme theme = new Theme(Theme.Mode.LIGHT, 100);
            DKit kit = new DKit(theme);
            JLabel label = kit.title(Language.msg("nav.chats")); root.add(label);
            JLabel username = kit.title("Chats"); root.add(username);
            JTextField field = kit.field(Language.msg("desktop.displayName")); root.add(field);
            field.setText("keep 中文 черновик"); field.select(3, 8);
            JPasswordField password = new JPasswordField("synthetic secret"); root.add(password);
            Object passwordDocument = password.getDocument();
            ChatsPanel chat = new ChatsPanel(null, theme); root.add(chat);
            Runnable change = () -> Language.refresh(root);
            Language.addListener(change);
            try {
                java.lang.reflect.Field f = ChatsPanel.class.getDeclaredField("mInput"); f.setAccessible(true);
                JTextArea draft = (JTextArea) f.get(chat);
                draft.setText("unfinished 中文 сообщение"); draft.select(2, 7);
                Object document = draft.getDocument();
                Object fieldDocument = field.getDocument();
                for (String code : new String[]{"ru", "zh-Hans", "en", "ru", "zh-Hans"}) {
                    Language.choose(code);
                    assertEquals(Language.text("nav.chats"), label.getText());
                    assertEquals("Chats", username.getText());
                    assertEquals("keep 中文 черновик", field.getText());
                    assertEquals(-1, field.getFont().canDisplayUpTo(field.getText()));
                    assertSame(fieldDocument, field.getDocument());
                    assertEquals(3, field.getSelectionStart()); assertEquals(8, field.getSelectionEnd());
                    assertSame(document, draft.getDocument());
                    assertEquals("unfinished 中文 сообщение", draft.getText());
                    assertEquals(2, draft.getSelectionStart()); assertEquals(7, draft.getSelectionEnd());
                    assertSame(passwordDocument, password.getDocument());
                    assertEquals("synthetic secret", new String(password.getPassword()));
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { Language.removeListener(change); Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void replacementTextUnbindsAndTooltipSupportsPaintedComponents() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new MemoryPreferences());
            try {
                JPanel root = new JPanel();
                JLabel label = Language.bind(new JLabel(), Language.msg("nav.chats")); root.add(label);
                label.setText("A real contact name");
                JComponent painted = new JComponent() { }; root.add(painted);
                Language.tooltip(painted, Language.msg("common.search"));
                Language.choose("ru"); Language.refresh(root);
                assertEquals("A real contact name", label.getText());
                assertEquals("Поиск", painted.getToolTipText());
            } finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }


    @Test public void translatedHelpGrowsToFitNarrowColumns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new MemoryPreferences());
            try {
                Language.choose("ru");
                JLabel hint = new DKit(new Theme(Theme.Mode.LIGHT, 100)).sub(Language.msg("desktop.docsHelp"));
                hint.setSize(650, 20);
                int wide = hint.getPreferredSize().height;
                hint.setSize(250, 20);
                assertTrue(hint.getPreferredSize().height > wide);
                assertEquals(hint.getPreferredSize().height, hint.getMaximumSize().height);
                assertEquals(0, hint.getMinimumSize().width);
            } finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void transferDisplayTranslatesOnlySystemFields() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new MemoryPreferences());
            try {
                Map<String,String> state = new HashMap<>();
                state.put("status", "Failed"); state.put("error", "Transfer unavailable");
                state.put("name", "Preparing {size} $1 中文.bin");
                state.put("done", "10"); state.put("size", "100");
                Map<String,String> original = new HashMap<>(state);
                for (String code : new String[]{"ru", "zh-Hans", "en"}) {
                    Language.choose(code);
                    String display = PrivateFileDialog.statusText(state);
                    assertTrue(display.startsWith(Language.system("Failed") + "\n"));
                    assertTrue(display.contains("\nPreparing {size} $1 中文.bin\n"));
                    assertTrue(display.endsWith(Language.text("files.unavailable")));
                    assertEquals(original, state);
                    assertEquals("unknown backend diagnostic 中文", Language.system("unknown backend diagnostic 中文"));
                    assertEquals("", Language.system(null));
                }
                Language.choose("ru");
                assertNotEquals("Preparing", Language.system("Preparing"));
                assertNotEquals("Failed", Language.system("Failed"));
            } finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void glyphFallbackPreservesFontSize() {
        Font original = new Theme(Theme.Mode.LIGHT, 100).semibold(17.5f);
        Font fallback = Language.fontFor(original, "语言 Русский");
        assertEquals(17.5f, fallback.getSize2D(), .001);
        assertEquals(-1, fallback.canDisplayUpTo("语言 Русский"));
    }
    @Test public void dynamicServerDiagnosticsPreserveValuesAndUnknownDetails() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new MemoryPreferences());
            try {
                String literal = "Copy 中文 $1 {error} <img>", number = "00012345678901234567890";
                for (String code : new String[]{"en", "ru", "zh-Hans"}) {
                    Language.choose(code);
                    for (String[] value : new String[][] {
                        {"resync refused: ", "serverError.resyncRefused"}, {"node error: ", "serverError.nodeFailure"},
                        {"hosting failed: ", "serverError.hostFailure"}, {"could not create the collection folder: ", "serverError.collectionFolder"},
                        {"could not list: ", "serverError.listFailure"}, {"could not delete: ", "serverError.deleteFailure"}}) {
                        assertEquals(Language.text(value[1], "error", literal), Language.system(value[0] + literal));
                        assertEquals(Language.text(value[1], "error", "Copy"), Language.system(value[0] + "Copy"));
                        assertEquals(Language.text(value[1], "error", Language.text("serverError.noContact")), Language.system(value[0] + "no such contact"));
                    }
                    assertEquals(Language.text("iosError.unknownContact", "id", literal), Language.system("unknown contact " + literal));
                    assertEquals(Language.text("iosError.chunkUnavailable", "id", literal), Language.system("chunk unavailable: " + literal));
                    assertEquals(Language.text("serverError.counterAboveCurrent", "current", number), Language.system("can only RAISE above the current " + number));
                    assertEquals(Language.text("serverError.counterAboveMaximum", "maximum", number), Language.system("above the key's maximum " + number));
                    assertEquals(Language.text("serverError.groupLimit", "maximum", "128", "requested", "129"), Language.system("a group holds at most 128 members (129 asked)"));
                    for (String raw : new String[]{"can only RAISE above the current -1", "can only RAISE above the current 1\n", "above the key's maximum 1e9", "a group holds at most 128 members (129 asked)\n"})
                        assertEquals(raw, Language.system(raw));
                    String prefix = "node error: ", tail = prefix.repeat(4) + literal;
                    assertTrue(Language.system(prefix.repeat(8) + tail).endsWith(tail));
                }
            } finally { Language.usePreferences(previous); }
        });
    }
}
