package com.eurobuddha.maxima.desktop.ui;

import java.io.File;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopNativeLanguageTest {
    @Test public void chooserUsesExplicitLanguageAndKeepsLiteralFileNames() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences old = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            Locale system = Locale.getDefault();
            try {
                Locale.setDefault(Locale.JAPANESE);
                for (String code : new String[]{"en", "ru", "zh-Hans"}) {
                    Language.choose(code);
                    JFileChooser chooser = Language.fileChooser();
                    File selected = new File("Copy {name} $1 中文 Русский.txt").getAbsoluteFile();
                    chooser.setSelectedFile(selected);
                    assertEquals(Language.locale(), chooser.getLocale());
                    assertEquals(Language.text("nodeWallet.open"), chooser.getUI().getApproveButtonText(chooser));
                    chooser.setDialogType(JFileChooser.SAVE_DIALOG);
                    assertEquals(Language.text("common.save"), chooser.getUI().getApproveButtonText(chooser));
                    assertEquals(Language.text("fileChooser.allFiles"), chooser.getAcceptAllFileFilter().getDescription());
                    assertEquals(selected, chooser.getSelectedFile());
                    assertEquals(Locale.JAPANESE, Locale.getDefault());
                }
            } finally { Locale.setDefault(system); Language.usePreferences(old); Language.applyDialogLabels(); }
        });
    }

    @Test public void dateAdaptationKeepsNativeRendererAndModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.util.Date date = new java.util.Date(1_704_155_400_000L);
            JTable table = new JTable(new javax.swing.table.DefaultTableModel(new Object[][]{{date}}, new Object[]{"Modified"}) {
                public Class<?> getColumnClass(int column) { return java.util.Date.class; }
            });
            JLabel nativeCell = new JLabel(); nativeCell.setForeground(java.awt.Color.MAGENTA);
            boolean[] receivedSelection = {false};
            table.setDefaultRenderer(java.util.Date.class, (view, value, selected, focused, row, column) -> {
                receivedSelection[0] = selected && focused && row == 0 && column == 0;
                nativeCell.setText("old system language"); return nativeCell;
            });
            Locale locale = Locale.forLanguageTag("ru");
            FileChooserLabels.localizeDates(table, locale);
            java.awt.Component cell = table.getCellRenderer(0,0).getTableCellRendererComponent(table,date,true,true,0,0);
            assertSame(nativeCell,cell); assertTrue(receivedSelection[0]);
            assertEquals(java.awt.Color.MAGENTA,cell.getForeground());assertSame(date,table.getValueAt(0,0));
            assertEquals(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL,java.text.DateFormat.SHORT,locale).format(date),nativeCell.getText());
        });
    }

    @Test public void nativeRenamePatternsKeepFileNamesLiteral() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences old = Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            String name = "Owner's {0} $1 中文.txt", target = "Новый {name}.txt";
            try {
                for (String code : new String[]{"en", "ru", "zh-Hans"}) {
                    Language.choose(code);
                    assertEquals(Language.text("fileChooser.renameError", "name", name),
                            MessageFormat.format(UIManager.getString("FileChooser.renameErrorText"), name));
                    assertEquals(Language.text("fileChooser.renameToError", "name", name, "target", target),
                            MessageFormat.format(UIManager.getString("FileChooser.renameFileErrorText"), name, target));
                    assertEquals(Language.text("fileChooser.newFolderNumber", "number", "12"),
                            MessageFormat.format(UIManager.getString("FileChooser.other.newFolder.subsequent"), "12"));
                }
            } finally { Language.usePreferences(old); Language.applyDialogLabels(); }
        });
    }
}
