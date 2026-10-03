package com.eurobuddha.maxima.desktop.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.TransferHandler;
import javax.swing.text.DefaultEditorKit;

/** Adds image import while retaining Swing's normal text paste, copy and cut. */
final class ImagePasteSupport extends TransferHandler {
    private static final long MAX_PIXELS = 40_000_000L;
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private final TransferHandler text;
    private final BooleanSupplier enabled;
    private final Consumer<Transferable> paste;

    private ImagePasteSupport(TransferHandler text, BooleanSupplier enabled, Consumer<Transferable> paste) {
        this.text = text; this.enabled = enabled; this.paste = paste;
    }

    static void install(JTextArea input, BooleanSupplier enabled, Consumer<Transferable> paste) {
        input.setTransferHandler(new ImagePasteSupport(input.getTransferHandler(), enabled, paste));
        input.getInputMap().put(KeyStroke.getKeyStroke("control V"), DefaultEditorKit.pasteAction);
        input.getInputMap().put(KeyStroke.getKeyStroke("meta V"), DefaultEditorKit.pasteAction);
    }

    private static boolean hasImage(DataFlavor[] flavors) {
        for (DataFlavor f : flavors) {
            if (DataFlavor.imageFlavor.equals(f) || DataFlavor.javaFileListFlavor.equals(f)) return true;
        }
        return false;
    }

    @Override public boolean canImport(JComponent c, DataFlavor[] flavors) {
        return enabled.getAsBoolean() && hasImage(flavors) || text != null && text.canImport(c, flavors);
    }

    @Override public boolean canImport(TransferSupport support) {
        return enabled.getAsBoolean() && hasImage(support.getDataFlavors())
                || text != null && text.canImport(support);
    }

    @Override public boolean importData(JComponent c, Transferable data) {
        if (enabled.getAsBoolean() && hasImage(data.getTransferDataFlavors())) {
            paste.accept(data); return true;
        }
        return text != null && text.importData(c, data);
    }

    @Override public boolean importData(TransferSupport support) {
        if (enabled.getAsBoolean() && hasImage(support.getDataFlavors())) {
            paste.accept(support.getTransferable()); return true;
        }
        return text != null && text.importData(support);
    }

    @Override public void exportToClipboard(JComponent c, Clipboard clipboard, int action) {
        if (text != null) text.exportToClipboard(c, clipboard, action);
    }

    @Override public int getSourceActions(JComponent c) {
        return text == null ? NONE : text.getSourceActions(c);
    }

    /** Called on the image worker. Never fetch URLs or interpret clipboard HTML. */
    static BufferedImage readImage(Transferable contents) throws Exception {
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            Image image = (Image) contents.getTransferData(DataFlavor.imageFlavor);
            if (image == null) throw new IOException("The clipboard image is empty.");
            // Native clipboards may return a lazily loaded ToolkitImage.
            javax.swing.ImageIcon loaded = new javax.swing.ImageIcon(image);
            int w = loaded.getIconWidth(), h = loaded.getIconHeight();
            checkSize(w, h);
            BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            try {
                g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
                if (!g.drawImage(image, 0, 0, null)) throw new IOException("The clipboard image is not ready.");
            } finally { g.dispose(); }
            return copy;
        }
        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            List<?> files = (List<?>) contents.getTransferData(DataFlavor.javaFileListFlavor);
            if (files.size() != 1 || !(files.get(0) instanceof File)) {
                throw new IOException("Paste one image at a time.");
            }
            File file = (File) files.get(0);
            if (!file.isFile() || file.length() > MAX_FILE_BYTES) {
                throw new IOException("Choose an image file smaller than 32 MB.");
            }
            try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
                if (in == null) throw new IOException("Couldn't read that image.");
                java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) throw new IOException("The copied file is not a supported image.");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    checkSize(reader.getWidth(0), reader.getHeight(0));
                    // Preview upright, leaving compression to the shared send path.
                    byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                    BufferedImage image = DesktopImagePrep.decode(bytes);
                    if (image == null) throw new IOException("Couldn't decode that image.");
                    return image;
                } finally { reader.dispose(); }
            }
        }
        throw new IOException("Copy an image first.");
    }

    private static void checkSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IOException("Image must contain at most 40 million pixels.");
        }
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("Couldn't prepare that image.");
        return out.toByteArray();
    }
}
