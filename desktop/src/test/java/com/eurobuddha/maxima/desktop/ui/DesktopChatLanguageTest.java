package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.app.i18n.SearchText;
import com.eurobuddha.maxima.core.chat.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.text.AttributedString;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopChatLanguageTest {
    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static void set(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = (object instanceof Class ? (Class<?>) object : object.getClass()).getDeclaredMethod(name, types);
        m.setAccessible(true); return m.invoke(object instanceof Class ? null : object, args);
    }
    static ChatEngine.Entry entry(String id, String body, boolean mine) throws Exception {
        Constructor<ChatEngine.Entry> c = ChatEngine.Entry.class.getDeclaredConstructor(String.class,
                String.class, String.class, String.class, String.class, long.class, boolean.class, String.class);
        c.setAccessible(true);
        return c.newInstance(id, "synthetic", "", "synthetic", body, System.currentTimeMillis(), mine, "sent");
    }
    static List<Component> descendants(Container root) {
        List<Component> list = new ArrayList<>();
        for (Component c : root.getComponents()) { list.add(c); if (c instanceof Container) list.addAll(descendants((Container) c)); }
        return list;
    }
    static boolean hasLabel(Container root, String value) {
        return descendants(root).stream().anyMatch(c -> c instanceof JLabel && value.equals(((JLabel) c).getText()));
    }

    @Test public void translatingBubblesPreservesVoiceControlsFileActionsAndUserText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try {
                ChatsPanel panel = new ChatsPanel(null, new Theme(Theme.Mode.LIGHT, 100));
                JPanel thread = (JPanel) field(panel, "mThread");
                String fileName = "Chats 中文 $1 {count}.bin";
                String memo = "Chats 中文 Русский";
                String[] bodies = {memo, "Link 中文: https://example.com/test", ChatPay.wrap("12.5", "MY TOKEN", "synthetic-tx", memo),
                        ChatMedia.wrap("application/octet-stream", ChatFile.PREFIX + "synthetic", fileName),
                        ChatMedia.wrap("audio/wav", "synthetic", "0:10|"),
                        ChatContact.wrap("synthetic", "Chats", "Mx-synthetic@127.0.0.1:1")};
                for (int i = 0; i < bodies.length; i++) thread.add((JComponent) call(panel, "bubble",
                        new Class<?>[]{ChatEngine.Entry.class, boolean.class, boolean.class}, entry("test-" + i, bodies[i], i == 2), true, true));
                List<Component> before = descendants(thread);
                Map<javax.swing.text.JTextComponent, javax.swing.text.Document> documents = new IdentityHashMap<>();
                for (Component c : before) if (c instanceof javax.swing.text.JTextComponent) {
                    javax.swing.text.JTextComponent text = (javax.swing.text.JTextComponent) c; documents.put(text, text.getDocument());
                }
                Component play = before.stream().filter(c -> c.getClass().getSimpleName().equals("PlayButton")).findFirst().get();
                call(play, "setPlaying", new Class<?>[]{boolean.class}, true); // visual state only; no audio capture/playback
                AbstractButton file = (AbstractButton) before.stream().filter(c -> c instanceof AbstractButton).findFirst().get();
                ActionListener[] actions = file.getActionListeners();
                for (String language : new String[]{"ru", "zh-Hans", "en"}) {
                    Language.choose(language); panel.languageChanged();
                    assertEquals(before, descendants(thread));
                    for (Map.Entry<javax.swing.text.JTextComponent, javax.swing.text.Document> e : documents.entrySet())
                        assertSame(e.getValue(), e.getKey().getDocument());
                    assertEquals(true, field(play, "playing"));
                    assertArrayEquals(actions, file.getActionListeners());
                    assertEquals(Language.text("desktopChat.transferAction"), file.getText());
                    assertTrue(before.stream().anyMatch(c -> c instanceof JTextArea && fileName.equals(((JTextArea) c).getText())));
                    assertTrue(hasLabel(thread, memo));
                    assertTrue(hasLabel(thread, Language.text("desktopChat.sentPayment")));
                    assertTrue(hasLabel(thread, Language.text("contacts.shared")));
                    assertTrue(hasLabel(thread, "👤 Chats"));
                }
            } catch (Exception e) { throw new AssertionError(e); }
            finally { Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }

    @Test public void compositionBlocksSendUntilEveryCharacterIsCommitted() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                ChatsPanel panel = new ChatsPanel(null, new Theme(Theme.Mode.LIGHT, 100));
                JTextArea input = (JTextArea) field(panel, "mInput");
                set(panel, "mOpen", "synthetic");
                input.setText("中文 draft");
                for (int committed : new int[]{0, 1}) {
                    InputMethodEvent event = new InputMethodEvent(input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                            new AttributedString("中文").getIterator(), committed, null, null);
                    for (InputMethodListener listener : input.getInputMethodListeners()) listener.inputMethodTextChanged(event);
                    assertFalse(event.isConsumed()); // Swing must still receive the text.
                    input.getActionMap().get("send").actionPerformed(new ActionEvent(input, 0, "send"));
                    input.getActionMap().get("newline").actionPerformed(new ActionEvent(input, 0, "newline"));
                    assertEquals("中文 draft", input.getText());
                }
                InputMethodEvent done = new InputMethodEvent(input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                        new AttributedString("中文").getIterator(), 2, null, null);
                for (InputMethodListener listener : input.getInputMethodListeners()) listener.inputMethodTextChanged(done);
                assertEquals(false, field(panel, "mComposing"));
                input.select(3, 8);
                input.getActionMap().get("newline").actionPerformed(new ActionEvent(input, 0, "newline"));
                assertEquals("中文 \n", input.getText());
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void searchAndPreviewClippingKeepOriginalUnicodeOffsets() throws Exception {
        String text = "İstanbul 😀中文";
        assertEquals(text.indexOf("中文"), SearchText.indexOf(text, "中文"));
        assertEquals(0, SearchText.indexOf(text, "istanbul"));
        assertEquals("😀…", call(ChatsPanel.class, "clip", new Class<?>[]{String.class, int.class}, "😀中文", 2));
        String longText = "😀".repeat(50) + "needle" + "😀".repeat(50);
        String snippet = (String) call(ChatsPanel.class, "snippet", new Class<?>[]{String.class, int.class, int.class}, longText, 100, 6);
        assertTrue(snippet.contains("needle"));
        for (int i = 0; i < snippet.length(); i++) {
            char c = snippet.charAt(i);
            if (Character.isHighSurrogate(c)) assertTrue(i + 1 < snippet.length() && Character.isLowSurrogate(snippet.charAt(++i)));
            else assertFalse(Character.isLowSurrogate(c));
        }
    }

    @Test public void selectedLocaleControlsDatesRelativeTimesAndStructuredPreviews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences previous = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            Locale system = Locale.getDefault();
            try {
                Locale.setDefault(Locale.CHINA);
                Calendar date = new GregorianCalendar(2024, Calendar.JANUARY, 15);
                Language.choose("en"); assertEquals("January", Language.date(date.getTimeInMillis(), "MMMM"));
                Language.choose("ru"); assertEquals("22 минуты назад", Language.relative(22 * 60_000));
                assertEquals("в очереди", call(ChatsPanel.class, "stateText", new Class<?>[]{String.class}, "queued"));
                assertEquals("sent-literal-user-text", call(ChatsPanel.class, "previewBody", new Class<?>[]{String.class}, "sent-literal-user-text"));
                assertEquals(Language.text("media.voice"), call(ChatsPanel.class, "previewBody", new Class<?>[]{String.class}, ChatMedia.wrap("audio/wav", "synthetic", "0:01|")));
                Language.choose("zh-Hans"); assertEquals("2 小时前", Language.relative(2 * 3600_000));
                assertEquals("📎 中文.txt", call(ChatsPanel.class, "previewBody", new Class<?>[]{String.class}, ChatMedia.wrap("text/plain", ChatFile.PREFIX + "synthetic", "中文.txt")));
            } catch (Exception e) { throw new AssertionError(e); }
            finally { Locale.setDefault(system); Language.usePreferences(previous); Language.applyDialogLabels(); }
        });
    }
}
