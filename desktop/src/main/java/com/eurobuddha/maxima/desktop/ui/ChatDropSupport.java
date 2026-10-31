package com.eurobuddha.maxima.desktop.ui;

import java.awt.datatransfer.*;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.*;
import java.util.function.*;
import javax.swing.*;

/** File drops layered over the existing text/image clipboard handler. */
final class ChatDropSupport extends TransferHandler {
    private final TransferHandler previous;
    private final BooleanSupplier enabled;
    private final Consumer<List<File>> receive;
    private ChatDropSupport(TransferHandler previous, BooleanSupplier enabled, Consumer<List<File>> receive) {
        this.previous=previous; this.enabled=enabled; this.receive=receive;
    }
    static void install(JComponent target, BooleanSupplier enabled, Consumer<List<File>> receive) {
        if (!(target.getTransferHandler() instanceof ChatDropSupport))
            target.setTransferHandler(new ChatDropSupport(target.getTransferHandler(),enabled,receive));
    }
    static void installTree(java.awt.Container container, BooleanSupplier enabled, Consumer<List<File>> receive) {
        if (container instanceof JComponent) install((JComponent)container,enabled,receive);
        for (java.awt.Component child:container.getComponents())
            if (child instanceof java.awt.Container) installTree((java.awt.Container)child,enabled,receive);
    }
    @Override public boolean canImport(TransferSupport support) {
        if (support.isDrop() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            if (!enabled.getAsBoolean() || (support.getSourceDropActions() & COPY)==0) return false;
            support.setDropAction(COPY);
            support.setShowDropLocation(false);
            return true;
        }
        return previous!=null && previous.canImport(support);
    }
    @Override public boolean importData(TransferSupport support) {
        if (support.isDrop() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            if (!canImport(support)) return false;
            try { return receiveFiles(support.getTransferable()); }
            catch (Exception e) { return false; }
        }
        return previous!=null && previous.importData(support);
    }
    // Snapshot native drag data while it is valid. The consumer captures the
    // conversation now and defers any dialog until the OS drop has completed.
    boolean receiveFiles(Transferable data) throws Exception {
        if (!enabled.getAsBoolean()) return false;
        Object value=data.getTransferData(DataFlavor.javaFileListFlavor);
        if (!(value instanceof List<?>)) return false;
        List<File> files=new ArrayList<>();
        for (Object item:(List<?>)value) {
            if (!(item instanceof File)) return false;
            files.add((File)item);
        }
        if (files.isEmpty()) return false;
        receive.accept(Collections.unmodifiableList(files)); return true;
    }
    @Override public boolean importData(JComponent target, Transferable data) {
        return previous!=null && previous.importData(target,data);
    }
    @Override public boolean canImport(JComponent target,DataFlavor[] flavors) {
        return previous!=null && previous.canImport(target,flavors);
    }
    @Override public int getSourceActions(JComponent c) { return previous==null?NONE:previous.getSourceActions(c); }
    @Override public void exportToClipboard(JComponent c,Clipboard clip,int action) { if(previous!=null)previous.exportToClipboard(c,clip,action); }
    @Override public void exportAsDrag(JComponent c,InputEvent e,int action) { if(previous!=null)previous.exportAsDrag(c,e,action); }
}
