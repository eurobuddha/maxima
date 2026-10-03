package com.eurobuddha.maxima.desktop.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import com.eurobuddha.maxima.core.chat.ChatEngine;

/** Offline visual fixture: no node, account, wallet or network is started. */
public final class DesktopInputPreview {
    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]); Files.createDirectories(out);
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (Theme.Mode mode : Theme.Mode.values()) {
                    for (int size : new int[]{100, 150}) {
                        for (int width : new int[]{430, 1040}) render(out, mode, size, width);
                    }
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    static void render(Path out, Theme.Mode mode, int percent, int width) throws Exception {
        Theme theme = new Theme(mode, 100);
        ChatsPanel panel = new ChatsPanel(null, theme);
        set(panel, "mOpen", "preview"); set(panel, "mShowList", false);
        ((JLabel) field(panel, "mThreadTitle")).setText("Alex");
        ((JLabel) field(panel, "mThreadSub")).setText("online");
        ((JTextArea) field(panel, "mInput")).setText("An unfinished message stays here");
        JPanel thread = (JPanel) field(panel, "mThread");
        Method bubble = ChatsPanel.class.getDeclaredMethod("bubble", ChatEngine.Entry.class, boolean.class, boolean.class);
        bubble.setAccessible(true);
        for (int i = 0; i < 4; i++) {
            String body = i == 0 ? "Try Ctrl + to make the text larger." : i == 1 ?
                    "This longer message should wrap naturally when the font size increases. The draft below stays in place." :
                    i == 2 ? "Paste a screenshot with Ctrl V or Command V." : "Here is a link: https://example.com/photos";
            Constructor<ChatEngine.Entry> constructor = ChatEngine.Entry.class.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, String.class, long.class, boolean.class, String.class);
            constructor.setAccessible(true);
            ChatEngine.Entry message = constructor.newInstance("preview-" + i, "preview", "", "preview", body,
                    System.currentTimeMillis(), i == 1, "sent");
            thread.add((JComponent) bubble.invoke(panel, message, true, true));
        }
        panel.setSize(width, 680); panel.onWidth(width);
        theme.resizeText(panel, percent);
        panel.onWidth(width);
        for (int i = 0; i < 5; i++) { invalidate(panel); layout(panel); }
        BufferedImage image = new BufferedImage(width, 680, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); panel.printAll(g); g.dispose();
        if (out != null) ImageIO.write(image, "png", out.resolve(mode + "-" + percent + "-" + width + ".png").toFile());
        for (Component row : thread.getComponents()) {
            for (Component component : ((Container) row).getComponents()) {
                if (!(component instanceof ChatsPanel.Bubble)) continue;
                Container bubbleComponent = (Container) component;
                Point origin = SwingUtilities.convertPoint(component, 0, 0, panel);
                if (origin.x < 0 || origin.x + component.getWidth() > width) {
                    throw new AssertionError("Bubble extends outside the conversation");
                }
                for (Component child : bubbleComponent.getComponents()) {
                    if (!(child instanceof javax.swing.text.JTextComponent)) continue;
                    javax.swing.text.JTextComponent text = (javax.swing.text.JTextComponent) child;
                    java.awt.geom.Rectangle2D end = text.modelToView2D(text.getDocument().getLength());
                    if (end != null && end.getMaxY() + child.getY() > component.getHeight()) {
                        throw new AssertionError("Message text is clipped at " + percent + "%");
                    }
                }
            }
        }
    }
    private static void invalidate(Container c) {
        c.invalidate();
        for (Component child : c.getComponents()) if (child instanceof Container) invalidate((Container) child);
    }
    private static void layout(Container c) {
        c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container) layout((Container) child);
    }
}
