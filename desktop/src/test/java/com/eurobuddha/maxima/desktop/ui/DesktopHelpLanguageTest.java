package com.eurobuddha.maxima.desktop.ui;

import java.util.*;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopHelpLanguageTest {
    @Test public void everyHelpTopicChangesLanguageAndUnknownTopicStaysLiteral() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences old = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try {
                String[] topics = {"status","address","hosts","mls","contacts","mailbox","outbox","services",
                    "contribution","witness","storage","direct","counters","ticks","readreceipts","groups",
                    "name","appearance","seed","relays","log","keyuses","nodekind"};
                for (String topic : topics) {
                    Language.choose("en");
                    String title = DesktopExplain.title(topic), body = DesktopExplain.body(topic);
                    for (String code : new String[]{"ru","zh-Hans"}) {
                        Language.choose(code);
                        assertNotEquals(title, DesktopExplain.title(topic));
                        assertNotEquals(body, DesktopExplain.body(topic));
                        assertFalse(DesktopExplain.body(topic).startsWith("help."));
                    }
                }
                String unknown = "literal {topic} $1 中文";
                assertEquals(unknown, DesktopExplain.title(unknown));
                assertTrue(DesktopExplain.body(unknown).contains(unknown));
                Language.choose("en");
                assertTrue(DesktopExplain.body("keyuses").contains("cannot sign again"));
                assertFalse(DesktopExplain.body("keyuses").contains("advances keys"));
            } finally { Language.usePreferences(old); Language.applyDialogLabels(); }
        });
    }

    @Test public void voiceCaptionStaysAsciiRegardlessOfDeviceAndSelectedLanguage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences old = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            Locale oldLocale = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("ar"));
                Class<?> type = Class.forName(VoiceNotes.class.getName() + "$Recorder");
                java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
                Object rec = ctor.newInstance();
                DesktopChatLanguageTest.set(rec,"startMs",System.currentTimeMillis()-12345L);
                for (String code : new String[]{"en","ru","zh-Hans"}) {
                    Language.choose(code);
                    String caption = (String) DesktopChatLanguageTest.call(rec,"caption",new Class<?>[0]);
                    assertTrue(caption,caption.matches("[0-9]+:[0-9]{2}\\|[0-9a-f]{32}"));
                    assertEquals("44444444444444444444444444444444",Waveform.splitCaption(caption)[1]);
                }
            } catch (Exception ex) { throw new AssertionError(ex); }
            finally { Locale.setDefault(oldLocale); Language.usePreferences(old); Language.applyDialogLabels(); }
        });
    }
}
