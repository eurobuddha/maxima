package com.eurobuddha.maxima.desktop.ui;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.*;
import java.util.prefs.Preferences;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real preference backend in a unique disposable node, exercised across independent JVMs. */
public final class DesktopLanguageRestartPreview {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static boolean select(String choice, Path output) throws Exception {
        boolean[] result = {false}; AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            int[] ticks = {0};
            javax.swing.Timer timer = new javax.swing.Timer(100, null);
            timer.addActionListener(event -> {
                try {
                    for (Window window : Window.getWindows()) if (window instanceof JDialog && window.isShowing()) {
                        JDialog dialog = (JDialog)window;
                        if (choice == null) { timer.stop(); dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)); return; }
                        for (Component component : DesktopChatLanguageTest.descendants(dialog.getContentPane()))
                            if (component instanceof JButton && choice.equals(((JButton)component).getText())) {
                                Container root = dialog.getContentPane();
                                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(root.getWidth(),root.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                                Graphics2D graphics=image.createGraphics();root.printAll(graphics);graphics.dispose();
                                ImageIO.write(image,"png",output.toFile());
                                timer.stop();((JButton)component).doClick();return;
                            }
                    }
                    if (++ticks[0] >= 100) throw new AssertionError("Language chooser did not appear");
                } catch (Throwable error) {
                    failure.set(error);timer.stop();for(Window window:Window.getWindows())window.dispose();
                }
            });
            timer.start(); result[0]=Language.chooser(null);timer.stop();
        });
        if(failure.get()!=null)throw new AssertionError(failure.get());
        return result[0];
    }
    public static void main(String[] args) throws Exception {
        String phase=args[0], nodeName=args[1];Path out=Paths.get(args[2]);Files.createDirectories(out);
        check(nodeName.startsWith("com/eurobuddha/maxima/localization-fixture-"),"Fixture preference namespace required");
        Preferences fixture=Preferences.userRoot().node(nodeName);
        SwingUtilities.invokeAndWait(()->Language.usePreferences(fixture));
        try {
            if("first".equals(phase)) {
                check(!Language.hasChoice(),"Fixture already has a choice");
                check(!select(null,out.resolve("selector-cancel.png")),"Closing must cancel first choice");
                check(!Language.hasChoice(),"Closing must not save an automatic choice");
                check(select("Русский",out.resolve("selector-ru.png")),"Russian selection failed");
                check("ru".equals(Language.code()),"Russian choice not applied");fixture.flush();
            } else if("second".equals(phase)) {
                check(Language.hasChoice()&&"ru".equals(Language.code()),"Russian preference did not survive process restart");
                check(select("简体中文",out.resolve("selector-zh-Hans.png")),"Chinese selection failed");fixture.flush();
            } else if("third".equals(phase)) {
                check(Language.hasChoice()&&"zh-Hans".equals(Language.code()),"Chinese preference did not survive process restart");
                fixture.removeNode();Preferences.userRoot().flush();
            } else throw new IllegalArgumentException("Unknown phase");
            System.out.println("PASS preference process " + phase);
        } finally {SwingUtilities.invokeAndWait(()->{for(Window window:Window.getWindows())window.dispose();});}
    }
}
