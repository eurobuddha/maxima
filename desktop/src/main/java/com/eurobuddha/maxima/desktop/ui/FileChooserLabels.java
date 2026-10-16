package com.eurobuddha.maxima.desktop.ui;

import javax.swing.UIManager;

/** Native Swing file-picker strings. Keep the existing chooser, file model and actions. */
final class FileChooserLabels {
    private FileChooserLabels() { }
    private static void label(String message, String... keys) {
        String text = Language.text(message);
        for (String key : keys) UIManager.put("FileChooser." + key, text);
    }
    private static void pattern(String key, String message, Object... arguments) {
        // Swing supplies filenames through MessageFormat after our named interpolation.
        UIManager.put("FileChooser." + key, Language.text(message, arguments));
    }
    static void configure(javax.swing.JFileChooser chooser) {
        // Aqua DateRenderer uses the process default, unlike Basic/Metal. Keep its native
        // renderer for selection/colors/icons and replace only the formatted date label.
        if ("Aqua".equals(UIManager.getLookAndFeel().getID())) localizeDates(chooser, chooser.getLocale());
    }
    static void localizeDates(java.awt.Container root, java.util.Locale locale) {
        if (root instanceof javax.swing.JTable) {
            javax.swing.JTable table = (javax.swing.JTable) root;
            javax.swing.table.TableCellRenderer nativeRenderer = table.getDefaultRenderer(java.util.Date.class);
            java.text.DateFormat dates = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL, java.text.DateFormat.SHORT, locale);
            table.setDefaultRenderer(java.util.Date.class, (view, value, selected, focused, row, column) -> {
                java.awt.Component cell = nativeRenderer.getTableCellRendererComponent(view, value, selected, focused, row, column);
                if (value instanceof java.util.Date && cell instanceof javax.swing.JLabel)
                    ((javax.swing.JLabel) cell).setText(dates.format((java.util.Date) value));
                return cell;
            });
        }
        for (java.awt.Component child : root.getComponents())
            if (child instanceof java.awt.Container) localizeDates((java.awt.Container) child, locale);
    }

    static void apply() {
        label("nodeWallet.open", "openButtonText", "openDialogTitleText", "openTitleText", "directoryOpenButtonText");
        label("common.save", "saveButtonText", "saveDialogTitleText", "saveTitleText");
        label("common.cancel", "cancelButtonText");
        label("common.create", "createButtonText");
        label("common.rename", "renameFileButtonText");
        label("scripts.delete", "deleteFileButtonText");
        label("common.refresh", "updateButtonText", "refreshActionLabelText");
        label("common.name", "fileNameHeaderText", "byNameText", "byText");
        label("fileChooser.allFiles", "acceptAllFileFilterText");
        label("fileChooser.cancelHelp", "cancelButtonToolTipText", "cancelChooseButtonToolTipText", "cancelNewFolderButtonToolTipText", "cancelOpenButtonToolTipText", "cancelSaveButtonToolTipText");
        label("fileChooser.directory", "directoryDescriptionText");
        label("fileChooser.file", "fileDescriptionText");
        label("fileChooser.openFolderHelp", "directoryOpenButtonToolTipText", "openDirectoryButtonToolTipText");
        label("fileChooser.openFileHelp", "openButtonToolTipText");
        label("fileChooser.saveFileHelp", "saveButtonToolTipText");
        label("fileChooser.help", "helpButtonText");
        label("fileChooser.helpDescription", "helpButtonToolTipText");
        label("fileChooser.refreshHelp", "updateButtonToolTipText");
        label("fileChooser.newFolder", "newFolderAccessibleName", "newFolderActionLabelText", "newFolderButtonText", "newFolderTitleText", "untitledFolderName", "other.newFolder", "win32.newFolder", "mac.newFolder");
        for (String key : new String[]{"other.newFolder.subsequent", "win32.newFolder.subsequent", "mac.newFolder.subsequent"})
            pattern(key, "fileChooser.newFolderNumber", "number", "{0}");
        label("fileChooser.newFolderHelp", "newFolderToolTipText", "newFolderButtonToolTipText");
        label("fileChooser.newFolderError", "newFolderErrorText", "newFolderParentDoesntExistTitleText", "newFolderNoDirectoryErrorTitleText");
        label("fileChooser.parentMissing", "newFolderParentDoesntExistText", "newFolderNoDirectoryErrorText");
        label("fileChooser.folderExists", "newFolderExistsErrorText");
        label("fileChooser.newFolderPrompt", "newFolderPromptText", "newFolderDialogText");
        pattern("renameErrorText", "fileChooser.renameError", "name", "{0}");
        pattern("renameErrorFileExistsText", "fileChooser.renameExists", "name", "{0}");
        pattern("renameFileErrorText", "fileChooser.renameToError", "name", "{0}", "target", "{1}");
        label("fileChooser.renameTitle", "renameErrorTitleText", "renameFileErrorTitle");
        label("fileChooser.renamePrompt", "renameFileDialogText");
        label("fileChooser.choose", "chooseButtonText");
        label("fileChooser.chooseHelp", "chooseButtonToolTipText", "chooseItemButtonToolTipText");
        label("fileChooser.directoryHelp", "chooseFolderButtonToolTipText", "directoryComboBoxToolTipText");
        label("fileChooser.nameHelp", "filenameTextFieldToolTipText");
        label("fileChooser.filterHelp", "filterComboBoxToolTipText");
        label("fileChooser.desktop", "desktopName");
        label("fileChooser.fileName", "fileNameLabelText");
        label("fileChooser.folderName", "folderNameLabelText");
        label("fileChooser.fileType", "filesOfTypeLabelText");
        label("fileChooser.saveAs", "saveDialogFileNameLabelText");
        label("fileChooser.untitled", "untitledFileName");
        label("fileChooser.modified", "fileDateHeaderText", "byDateText");
        label("fileChooser.attributes", "fileAttrHeaderText");
        label("fileChooser.size", "fileSizeHeaderText");
        label("fileChooser.type", "fileTypeHeaderText");
        label("fileChooser.details", "detailsViewActionLabelText", "detailsViewButtonAccessibleName", "detailsViewButtonToolTipText", "filesDetailsAccessibleName");
        label("fileChooser.list", "listViewActionLabelText", "listViewButtonAccessibleName", "listViewButtonToolTipText", "filesListAccessibleName");
        label("fileChooser.home", "homeFolderAccessibleName", "homeFolderToolTipText");
        label("fileChooser.lookIn", "lookInLabelText");
        label("fileChooser.saveIn", "saveInLabelText");
        label("fileChooser.up", "upFolderAccessibleName", "upFolderToolTipText");
        label("fileChooser.view", "viewMenuLabelText");
        label("fileChooser.files", "filesLabelText");
        label("fileChooser.folders", "foldersLabelText");
        label("fileChooser.path", "pathLabelText");
        label("fileChooser.filter", "filterLabelText");
        label("fileChooser.deleteHelp", "deleteFileButtonToolTipText");
        label("fileChooser.renameHelp", "renameFileButtonToolTipText");
        // Keep the look-and-feel keyboard shortcuts and input maps.
    }
}
