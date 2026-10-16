package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.desktop.DesktopMain;
import com.eurobuddha.maxima.core.net.ReachabilityManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Isolated native UI fixtures. No node, account, relay, upload or transfer is started. */
public final class DesktopNativeLanguagePreview {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void chooser(Path out, String laf, String code, boolean save) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Language.choose(code);
            JFileChooser chooser = Language.fileChooser();
            File selected = out.resolve("Copy {name} $1 中文 Русский.txt").toFile();
            chooser.setCurrentDirectory(out.toFile());
            chooser.setSelectedFile(selected);
            chooser.setDialogTitle(Language.text(save ? "desktopPhotos.save" : "desktopFiles.choose"));
            javax.swing.Timer inspect = new javax.swing.Timer(800, event -> {
                try {
                    check(chooser.getLocale().equals(Language.locale()), "Locale did not follow explicit choice");
                    check(selected.equals(chooser.getSelectedFile()), "Selected filename changed");
                    check(chooser.getAcceptAllFileFilter().getDescription().equals(Language.text("fileChooser.allFiles")), "Filter remained untranslated");
                    check(chooser.getUI().getApproveButtonText(chooser).equals(Language.text(save ? "common.save" : "nodeWallet.open")), "Approve label remained untranslated");
                    if ("AquaLookAndFeel".equals(laf)) {
                        boolean checked = false;
                        for (Component widget : DesktopChatLanguageTest.descendants(chooser)) if (widget instanceof JTable) {
                            JTable table = (JTable)widget;
                            for(int row=0;row<table.getRowCount();row++) for(int column=0;column<table.getColumnCount();column++) {
                                Object value=table.getValueAt(row,column);
                                if(value instanceof java.util.Date) {
                                    Component cell=table.prepareRenderer(table.getCellRenderer(row,column),row,column);
                                    String expected=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL,java.text.DateFormat.SHORT,Language.locale()).format(value);
                                    check(cell instanceof JLabel && expected.equals(((JLabel)cell).getText()),"Native date ignored the selected language");
                                    checked=true;
                                }
                            }
                        }
                        check(checked,"No native date cell was checked");
                    }
                    Window window = SwingUtilities.getWindowAncestor(chooser);
                    Container root = ((JDialog) window).getContentPane();
                    BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
                    ImageIO.write(image, "png", out.resolve(laf + "-" + code + (save ? "-save" : "-open") + ".png").toFile());
                    if (save) chooser.approveSelection(); else chooser.cancelSelection();
                } catch (Throwable error) { failure.set(error); chooser.cancelSelection(); }
            });
            inspect.setRepeats(false); inspect.start();
            int result = save ? chooser.showSaveDialog(null) : chooser.showOpenDialog(null);
            check(result == (save ? JFileChooser.APPROVE_OPTION : JFileChooser.CANCEL_OPTION) || failure.get() != null, "Chooser action changed");
            check(selected.equals(chooser.getSelectedFile()), "Filename lost on close");
        });
        if (failure.get() != null) throw new AssertionError("Native picker " + laf + "/" + code, failure.get());
        System.out.println("PASS picker " + laf + " " + code + " " + (save ? "save" : "open"));
    }
    private static void tray(Path out) throws Exception {
        if (!SystemTray.isSupported()) { System.out.println("SKIP tray: OS tray unavailable"); return; }
        SwingUtilities.invokeAndWait(() -> {
            DesktopMain main = new DesktopMain(); TrayIcon icon = null;
            try {
                set(main, "mPublicAddress", "203.0.113.7:9501");
                set(main, "mReachDetail", "reachable at 203.0.113.7:9501 via upnp");
                set(main, "mReach", ReachabilityManager.State.ADVERTISED);
                set(main, "mAttachedClients", 22); set(main, "mRelayed", 123456L);
                Method install = DesktopMain.class.getDeclaredMethod("installTray", Path.class); install.setAccessible(true); install.invoke(main, out);
                icon = (TrayIcon) get(main, "mTray"); PopupMenu menu = icon.getPopupMenu();
                java.util.List<MenuItem> items = new ArrayList<>();
                for (int i = 0; i < menu.getItemCount(); i++) items.add(menu.getItem(i));
                for (String code : new String[]{"en", "ru", "zh-Hans"}) {
                    Language.choose(code);
                    check(icon == get(main, "mTray"), "Tray replaced");
                    check("203.0.113.7:9501".equals(get(main, "mPublicAddress")), "Address changed");
                    check(icon.getToolTip().equals(Language.text("desktopTray.reachable", "address", "203.0.113.7:9501")), "Tooltip stale");
                    for (int i = 0; i < items.size(); i++) check(items.get(i) == menu.getItem(i), "Menu rebuilt");
                    check(items.stream().anyMatch(item -> item.getLabel().equals(Language.text("language.change")) && item.getActionListeners().length == 1), "Language action missing");
                    check(items.stream().anyMatch(item -> item.getLabel().equals(Language.text("desktopTray.copyAddress"))), "Copy label stale");
                    String status = ((MenuItem)get(main,"mStatusItem")).getLabel();
                    check(status.equals(Language.text("desktopTray.status", "status", NetworkPanel.reachDetail("reachable at 203.0.113.7:9501 via upnp"))), "Status stale");
                    System.out.println("PASS tray " + code + ": " + status);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
            finally {
                try { Runnable listener = (Runnable)get(main,"mTrayLanguage"); if(listener != null) Language.removeListener(listener); } catch (Exception ignored) { }
                if (icon != null) SystemTray.getSystemTray().remove(icon);
            }
        });
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory", DesktopLanguagePreview.MemoryFactory.class.getName());
        Path out = Paths.get(args[0]); Files.createDirectories(out);
        Files.writeString(out.resolve("Copy {name} $1 中文 Русский.txt"), "Synthetic fixture only");
        try {
            for (String laf : new String[]{"javax.swing.plaf.metal.MetalLookAndFeel", "javax.swing.plaf.nimbus.NimbusLookAndFeel", "com.apple.laf.AquaLookAndFeel"}) {
                if (args.length > 1 && !laf.endsWith(args[1])) continue;
                try { SwingUtilities.invokeAndWait(() -> { try { UIManager.setLookAndFeel(laf); } catch (Exception e) { throw new RuntimeException(e); } }); }
                catch (Exception e) { System.out.println("SKIP unavailable look and feel: " + laf); continue; }
                String name = laf.substring(laf.lastIndexOf('.') + 1);
                for (String code : new String[]{"en", "ru", "zh-Hans"}) { chooser(out,name,code,false); chooser(out,name,code,true); }
            }
            if (args.length == 1) tray(out);
        } finally { SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); }); }
    }
}
