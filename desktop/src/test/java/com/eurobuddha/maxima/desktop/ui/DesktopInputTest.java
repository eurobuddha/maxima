package com.eurobuddha.maxima.desktop.ui;

import org.junit.Test;
import static org.junit.Assert.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

public class DesktopInputTest {
    private static Transferable data(DataFlavor flavor, Object value) {
        return new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{flavor}; }
            public boolean isDataFlavorSupported(DataFlavor f) { return flavor.equals(f); }
            public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                return value;
            }
        };
    }

    @Test public void imagePasteDoesNotChangeDraftAndTextCopyCutPasteStillWork() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea input = new JTextArea("draft");
            input.setCaretPosition(5);
            AtomicReference<Transferable> pasted = new AtomicReference<>();
            ImagePasteSupport.install(input, () -> true, pasted::set);
            Transferable image = data(DataFlavor.imageFlavor, new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB));
            assertTrue(input.getTransferHandler().importData(new TransferHandler.TransferSupport(input, image)));
            assertSame(image, pasted.get());
            assertEquals("draft", input.getText());
            assertTrue(input.getTransferHandler().importData(input, new StringSelection(" text")));
            assertEquals("draft text", input.getText());
            Clipboard clipboard = new Clipboard("test-only");
            input.select(0, 5);
            input.getTransferHandler().exportToClipboard(input, clipboard, TransferHandler.COPY);
            try { assertEquals("draft", clipboard.getData(DataFlavor.stringFlavor)); }
            catch (Exception e) { throw new AssertionError(e); }
            assertEquals("draft text", input.getText());
            input.getTransferHandler().exportToClipboard(input, clipboard, TransferHandler.MOVE);
            assertEquals(" text", input.getText());
        });
    }

    @Test public void noImagePasteWithoutOpenConversation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea input = new JTextArea();
            AtomicInteger calls = new AtomicInteger();
            ImagePasteSupport.install(input, () -> false, ignored -> calls.incrementAndGet());
            Transferable image = data(DataFlavor.imageFlavor, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            assertFalse(input.getTransferHandler().importData(input, image));
            assertEquals(0, calls.get());
            assertTrue(input.getTransferHandler().importData(input, new StringSelection("normal text")));
        });
    }

    @Test public void screenshotTransparencyAndExistingPhotoPreparation() throws Exception {
        BufferedImage source = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(10, 10, Color.RED.getRGB());
        BufferedImage image = ImagePasteSupport.readImage(data(DataFlavor.imageFlavor, source));
        assertEquals(Color.WHITE.getRGB(), image.getRGB(0, 0));
        assertEquals(Color.RED.getRGB(), image.getRGB(10, 10));
        DesktopImagePrep.Result result = DesktopImagePrep.prepare(ImagePasteSupport.png(image), "image/png");
        assertTrue(result.jpeg);
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(result.bytes));
        assertEquals(900, decoded.getWidth());
        assertEquals(563, decoded.getHeight());
    }

    @Test public void copiedFileWorksAndNonImagesAreRejected() throws Exception {
        File file = File.createTempFile("parlons-paste-", ".png");
        try {
            ImageIO.write(new BufferedImage(25, 15, BufferedImage.TYPE_INT_RGB), "png", file);
            Transferable contents = data(DataFlavor.javaFileListFlavor, Collections.singletonList(file));
            assertEquals(25, ImagePasteSupport.readImage(contents).getWidth());
            Files.write(file.toPath(), "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try { ImagePasteSupport.readImage(contents); fail("invalid image accepted"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("image")); }
        } finally { file.delete(); }
    }

    @Test public void ctrlAndCommandPlusMinusAndResetAreBound() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel();
            AtomicInteger delta = new AtomicInteger(99);
            DesktopTextShortcuts.install(root, delta::set);
            for (int mask : new int[]{InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK}) {
                for (int key : new int[]{KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD}) {
                    invoke(root, key, mask, delta, 10);
                    invoke(root, key, mask | InputEvent.SHIFT_DOWN_MASK, delta, 10);
                }
                invoke(root, KeyEvent.VK_MINUS, mask, delta, -10);
                invoke(root, KeyEvent.VK_SUBTRACT, mask, delta, -10);
                invoke(root, KeyEvent.VK_0, mask, delta, 0);
            }
            assertNull(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0)));
        });
    }

    @Test public void fullMessagesAndLinksFitAtNarrowAndWideSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (int percent : new int[]{80, 100, 150}) {
                    for (int width : new int[]{430, 1040}) {
                        DesktopInputPreview.render(null, Theme.Mode.DARK, percent, width);
                    }
                }
            } catch (Exception ex) { throw new AssertionError(ex); }
        });
    }

    private static void invoke(JComponent root, int key, int mask, AtomicInteger value, int expected) {
        Object action = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(key, mask));
        assertNotNull(action);
        root.getActionMap().get(action).actionPerformed(null);
        assertEquals(expected, value.get());
    }

    @Test public void resizePreservesDraftAndSelectionAndNewWidgetsUseSameSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme theme = new Theme(Theme.Mode.LIGHT, 100);
            JPanel root = new JPanel();
            JTextArea draft = new JTextArea("keep this draft");
            draft.setFont(theme.font(13.5f)); draft.select(2, 7); root.add(draft);
            JComponent pill = new DKit(theme).pill("Connected", 0); root.add(pill);
            int originalWidth = pill.getMaximumSize().width;
            for (int i = 0; i < 20; i++) {
                theme.resizeText(root, 150);
                assertEquals(20.25f, draft.getFont().getSize2D(), .001);
                assertTrue(pill.getMaximumSize().width > originalWidth);
                assertEquals(theme.font(13.5f).getSize2D(), draft.getFont().getSize2D(), .001);
                theme.resizeText(root, 100);
            }
            assertEquals(originalWidth, pill.getMaximumSize().width);
            assertEquals("keep this draft", draft.getText());
            assertEquals(2, draft.getSelectionStart()); assertEquals(7, draft.getSelectionEnd());
            theme.resizeText(root, -100); assertEquals(80, theme.textPercent());
            theme.resizeText(root, 500); assertEquals(150, theme.textPercent());
        });
    }
}
