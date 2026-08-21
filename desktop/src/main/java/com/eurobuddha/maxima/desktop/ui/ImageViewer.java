package com.eurobuddha.maxima.desktop.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * Full-screen image viewer — the desktop equivalent of the phone's
 * ZoomImageView dialog: wheel-zoom, drag-pan, double-click to reset, and a
 * chrome bar with Save and Copy (the desktop analog of the phone's Save/Share).
 */
final class ImageViewer {

    private ImageViewer() { }

    static void open(Window owner, BufferedImage img, String suggestedName) {
        JDialog d = new JDialog(owner, "Image", JDialog.ModalityType.APPLICATION_MODAL);
        d.setLayout(new BorderLayout());

        Canvas canvas = new Canvas(img);
        d.add(canvas, BorderLayout.CENTER);

        // Translucent chrome bar (matches the phone's #B3000000 overlay).
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bar.setBackground(new Color(0, 0, 0, 179));
        bar.add(chromeButton("Save", () -> save(d, img, suggestedName)));
        bar.add(chromeButton("Copy", () -> copy(img)));
        bar.add(chromeButton("Close", d::dispose));
        d.add(bar, BorderLayout.SOUTH);

        d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        d.setSize(Math.min(screen.width - 80, 1100), Math.min(screen.height - 80, 820));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static JButton chromeButton(String label, Runnable action) {
        JButton b = new JButton(label);
        b.setForeground(Color.WHITE);
        b.setBackground(new Color(255, 255, 255, 28));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(true);
        b.setFont(b.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static void save(Window owner, BufferedImage img, String suggestedName) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save image");
        fc.setSelectedFile(new File(suggestedName == null || suggestedName.isEmpty()
                ? "image.png" : suggestedName));
        if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File out = fc.getSelectedFile();
        String name = out.getName().toLowerCase();
        String fmt = name.endsWith(".jpg") || name.endsWith(".jpeg") ? "jpg" : "png";
        if (!name.endsWith("." + fmt) && !name.endsWith(".jpeg")) {
            out = new File(out.getParentFile(), out.getName() + "." + fmt);
        }
        try {
            ImageIO.write(img, fmt, out);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Couldn't save: " + ex.getMessage());
        }
    }

    private static void copy(BufferedImage img) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new TransferableImage(img), null);
        } catch (Exception ignored) {
        }
    }

    /** Image on the clipboard. */
    private static final class TransferableImage implements Transferable {
        private final Image image;
        TransferableImage(Image i) { image = i; }
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }
        public boolean isDataFlavorSupported(DataFlavor f) {
            return DataFlavor.imageFlavor.equals(f);
        }
        public Object getTransferData(DataFlavor f) {
            return image;
        }
    }

    /** Zoom/pan canvas. */
    private static final class Canvas extends JComponent {
        private final BufferedImage img;
        private double zoom = 1;       // 1 = fit-to-window
        private double fit = 1;
        private int panX, panY;
        private Point drag;

        Canvas(BufferedImage img) {
            this.img = img;
            setBackground(Color.BLACK);
            setOpaque(true);
            MouseAdapter m = new MouseAdapter() {
                public void mousePressed(MouseEvent e) { drag = e.getPoint(); }
                public void mouseDragged(MouseEvent e) {
                    if (drag != null) {
                        panX += e.getX() - drag.x;
                        panY += e.getY() - drag.y;
                        drag = e.getPoint();
                        repaint();
                    }
                }
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {   // reset to fit
                        zoom = 1; panX = 0; panY = 0; repaint();
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener((MouseWheelEvent e) -> {
                double f = e.getPreciseWheelRotation() < 0 ? 1.1 : 1 / 1.1;
                double nz = Math.max(1, Math.min(8, zoom * f));
                zoom = nz;
                repaint();
            });
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = getWidth(), h = getHeight();
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, w, h);
            int iw = img.getWidth(), ih = img.getHeight();
            fit = Math.min((double) w / iw, (double) h / ih);
            double scale = fit * zoom;
            int dw = (int) (iw * scale), dh = (int) (ih * scale);
            int x = (w - dw) / 2 + panX;
            int y = (h - dh) / 2 + panY;
            g2.drawImage(img, x, y, dw, dh, null);
            g2.dispose();
        }
    }
}
