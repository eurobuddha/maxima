package com.eurobuddha.maxima.desktop.ui;

import java.awt.Font;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.text.PlainDocument;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopFontLanguageTest {
    @Test public void bundledChineseFaceCoversCatalogueWithoutInstalledChineseFonts() throws Exception {
        // Load the physical resource directly: a logical OS font would hide a missing asset.
        Font physical;
        try (java.io.InputStream in = Theme.class.getResourceAsStream("/fonts/NotoSansSC-Regular.otf")) {
            assertNotNull(in);
            physical = Font.createFont(Font.TRUETYPE_FONT, in);
        }
        assertEquals("Noto Sans SC", physical.getFamily(java.util.Locale.ROOT));
        String catalogue;
        try (java.io.InputStream in = Language.class.getResourceAsStream("/i18n/messages.json")) {
            assertNotNull(in);
            catalogue = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        catalogue.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .distinct().forEach(cp -> assertTrue("Missing Han code point " + Integer.toHexString(cp), physical.canDisplay(cp)));
        for (int style : new int[]{Font.PLAIN, Font.BOLD, Font.ITALIC}) {
            Font fallback = Theme.chineseFont(style, 17.5f);
            assertEquals(17.5f, fallback.getSize2D(), .001);
            assertEquals(style, fallback.getStyle());
            assertEquals(-1, fallback.canDisplayUpTo("English 中文 Русский，。！？"));
        }
    }

    @Test public void systemEmojiCoverageIsRetainedInMixedChineseText() {
        Font authored = new Theme(Theme.Mode.LIGHT, 100).font(13);
        Font system = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        for (String emoji : new String[]{"😀", "🌍", "☎", "✓"}) {
            if (system.canDisplayUpTo(emoji) < 0) {
                String mixed = "中文 Русский " + emoji;
                assertEquals(mixed, -1, Language.fontFor(authored, mixed).canDisplayUpTo(mixed));
            }
        }
        assertSame(authored, Language.fontFor(authored, "English"));
        Font partial = Language.fontFor(authored, "中文 " + new String(Character.toChars(0x10FFFF)));
        assertTrue(partial.canDisplay('中'));
        assertTrue(partial.canDisplay('文'));
    }

    @Test public void restoresEveryAuthoredWeightIncludingChineseFirstConstruction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try {
                Theme theme = new Theme(Theme.Mode.LIGHT, 100);
                Font[] fonts = {theme.font(13), theme.medium(13), theme.semibold(13), theme.bold(13), theme.extrabold(13)};
                for (String initial : new String[]{"en", "zh-Hans"}) for (Font font : fonts) {
                    Language.choose(initial);
                    JLabel label = new DKit(theme).label(Language.msg("nav.chats"), font, theme.text);
                    for (String code : new String[]{"zh-Hans", "ru", "en", "zh-Hans", "en"}) {
                        Language.choose(code); Language.refresh(label);
                        assertEquals(Language.text("nav.chats"), label.getText());
                        assertEquals(-1, label.getFont().canDisplayUpTo(label.getText()));
                        if ("en".equals(code)) assertEquals(font, label.getFont());
                    }
                }
            } finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void resizeWhileChineseRetainsWeightAndRestoresAtNewSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try {
                Theme theme = new Theme(Theme.Mode.LIGHT, 100);
                JLabel label = new DKit(theme).title(Language.msg("nav.chats"));
                Font original = label.getFont();
                Language.choose("zh-Hans"); Language.refresh(label);
                theme.resizeText(label, 150);
                assertEquals(30, label.getFont().getSize2D(), .001);
                Language.choose("en"); Language.refresh(label);
                assertEquals(original.deriveFont(30f), label.getFont());
                theme.resizeText(label, 100);
                assertEquals(original, label.getFont());
                Font replacement = theme.semibold(17);
                label.setFont(replacement);
                Language.choose("zh-Hans"); Language.refresh(label);
                Language.choose("en"); Language.refresh(label);
                assertEquals(replacement, label.getFont());
            } finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void typedChineseAndReplacementDocumentPreserveDraftAndCaret() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try {
                Theme theme = new Theme(Theme.Mode.LIGHT, 100);
                JTextField field = new DKit(theme).field(Language.msg("common.search"));
                Font original = theme.font(13);
                field.setText("中文 черновик"); field.select(2, 6);
                Object document = field.getDocument();
                for (String code : new String[]{"zh-Hans", "ru", "en"}) {
                    Language.choose(code); Language.refresh(field);
                    assertSame(document, field.getDocument());
                    assertEquals("中文 черновик", field.getText());
                    assertEquals(2, field.getSelectionStart()); assertEquals(6, field.getSelectionEnd());
                    assertEquals(-1, field.getFont().canDisplayUpTo(field.getText()));
                }
                PlainDocument old = (PlainDocument) field.getDocument();
                int oldListeners = old.getDocumentListeners().length;
                field.setDocument(new PlainDocument());
                assertTrue(old.getDocumentListeners().length < oldListeners);
                field.setText("中文");
                assertEquals(-1, field.getFont().canDisplayUpTo(field.getText()));
                field.setText("English draft");
                assertEquals(original, field.getFont());
                JLabel name = new DKit(theme).title("张三");
                assertEquals(-1, name.getFont().canDisplayUpTo(name.getText()));
                name.setText("Alice");
                assertEquals(theme.extrabold(20), name.getFont());
            } finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void fontSelectionNeverReadsPasswordContents() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try {
                JPasswordField password = new JPasswordField() {
                    @Override public String getText() { throw new AssertionError("Password read for font selection"); }
                    @Override public char[] getPassword() { throw new AssertionError("Password read for font selection"); }
                };
                Object document = password.getDocument();
                Language.font(password, new Theme(Theme.Mode.LIGHT, 100).font(13));
                for (String code : new String[]{"zh-Hans", "en"}) {
                    Language.choose(code); Language.refresh(password);
                    assertSame(document, password.getDocument());
                }
            } finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }
}
