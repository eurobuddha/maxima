package com.eurobuddha.maxima.desktop.ui;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.IntConsumer;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/** Window-scoped text size shortcuts, including shifted plus and numeric keypads. */
final class DesktopTextShortcuts {
    static void install(JComponent root, IntConsumer change) {
        for (int mask : new int[]{InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK}) {
            for (int shift : new int[]{0, InputEvent.SHIFT_DOWN_MASK}) {
                for (int key : new int[]{KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD}) {
                    bind(root, key, mask | shift, "text-larger", 10, change);
                }
                for (int key : new int[]{KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT}) {
                    bind(root, key, mask | shift, "text-smaller", -10, change);
                }
            }
            bind(root, KeyEvent.VK_0, mask, "text-reset", 0, change);
            bind(root, KeyEvent.VK_NUMPAD0, mask, "text-reset", 0, change);
        }
    }

    private static void bind(JComponent root, int key, int mask, String name,
                             int delta, IntConsumer change) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, mask), name);
        root.getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent event) { change.accept(delta); }
        });
    }

    private DesktopTextShortcuts() { }
}
