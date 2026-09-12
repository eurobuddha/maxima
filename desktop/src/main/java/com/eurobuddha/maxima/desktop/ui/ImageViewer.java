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

    interface Loader { BufferedImage load(com.eurobuddha.maxima.core.chat.ChatImages.Photo photo) throws Exception; }

    static void open(Window owner, com.eurobuddha.maxima.core.chat.ChatImages photos, Loader loader) {
        if (photos.current() == null) return;
        JDialog d = new JDialog(owner, "Image", JDialog.ModalityType.APPLICATION_MODAL);
        d.setLayout(new BorderLayout());
        Canvas canvas = new Canvas(null);
        d.add(canvas, BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bar.setBackground(new Color(0, 0, 0, 179));
        javax.swing.JLabel counter = new javax.swing.JLabel(); counter.setForeground(Color.WHITE);
        bar.add(counter);
        JButton save = chromeButton("Save", () -> {
            if (canvas.img != null) save(d, canvas.img,
                    com.eurobuddha.maxima.core.chat.ChatMedia.mime(photos.current().body).contains("png") ? "image.png" : "image.jpg");
        });
        JButton copy = chromeButton("Copy", () -> { if (canvas.img != null) copy(canvas.img); });
        java.util.concurrent.ThreadPoolExecutor worker = (java.util.concurrent.ThreadPoolExecutor)
                java.util.concurrent.Executors.newFixedThreadPool(1);
        java.util.concurrent.Future<?>[] pending = {null};
        int[] generation = {0};
        Runnable[] bind = new Runnable[1];
        java.util.function.IntConsumer page = delta -> { if (photos.move(delta)) bind[0].run(); };
        JButton prev = chromeButton("‹", () -> page.accept(-1)); prev.setToolTipText("Previous photo");
        JButton next = chromeButton("›", () -> page.accept(1)); next.setToolTipText("Next photo");
        JButton retry = chromeButton("Retry", () -> bind[0].run());
        bar.add(prev); bar.add(next); bar.add(retry); bar.add(save); bar.add(copy);
        bar.add(chromeButton("Close", d::dispose)); d.add(bar, BorderLayout.SOUTH);
        bind[0] = () -> {
            int ticket = ++generation[0];
            if (pending[0] != null) pending[0].cancel(true);
            worker.purge();
            canvas.setImage(null); canvas.status = "Loading photo…";
            save.setEnabled(false); copy.setEnabled(false); retry.setVisible(false);
            counter.setText((photos.index() + 1) + " / " + photos.size());
            prev.setEnabled(photos.index() > 0); next.setEnabled(photos.index() + 1 < photos.size());
            com.eurobuddha.maxima.core.chat.ChatImages.Photo photo = photos.current();
            pending[0] = worker.submit(() -> {
                BufferedImage result = null;
                try { result = loader.load(photo); } catch (Exception ignored) { }
                final BufferedImage image = result;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (ticket != generation[0] || !d.isDisplayable()) return;
                    canvas.status = "Photo unavailable"; canvas.setImage(image);
                    save.setEnabled(image != null); copy.setEnabled(image != null); retry.setVisible(image == null);
                });
            });
        };
        canvas.page = page;
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) {
                generation[0]++; worker.shutdownNow(); canvas.setImage(null);
            }
        });
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.getRootPane().registerKeyboardAction(e -> page.accept(-1),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.getRootPane().registerKeyboardAction(e -> page.accept(1),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        d.setSize(Math.min(screen.width - 80, 1100), Math.min(screen.height - 80, 820));
        d.setLocationRelativeTo(owner);
        bind[0].run(); d.setVisible(true);
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
        private BufferedImage img;
        private String status = "Loading photo…";
        private java.util.function.IntConsumer page;
        private Point down;
        private boolean canPage;
        private double zoom = 1;       // 1 = fit-to-window
        private double fit = 1;
        private int panX, panY;
        private Point drag;

        Canvas(BufferedImage img) {
            this.img = img;
            setBackground(Color.BLACK);
            setOpaque(true);
            MouseAdapter m = new MouseAdapter() {
                public void mousePressed(MouseEvent e) { drag = e.getPoint(); down = e.getPoint(); canPage = zoom <= 1.01; }
                public void mouseReleased(MouseEvent e) {
                    if (down != null && canPage && page != null) {
                        int dx = e.getX() - down.x, dy = e.getY() - down.y;
                        if (Math.abs(dx) > 56 && Math.abs(dx) > Math.abs(dy) * 1.5) page.accept(dx < 0 ? 1 : -1);
                    }
                    down = null; drag = null;
                }
                public void mouseDragged(MouseEvent e) {
                    if (drag != null && zoom > 1.01) {
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
                canPage = false;
                double f = e.getPreciseWheelRotation() < 0 ? 1.1 : 1 / 1.1;
                double nz = Math.max(1, Math.min(8, zoom * f));
                zoom = nz;
                repaint();
            });
        }

        void setImage(BufferedImage image) {
            img = image; zoom = 1; panX = 0; panY = 0; repaint();
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = getWidth(), h = getHeight();
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, w, h);
            if (img == null) {
                g2.setColor(Color.WHITE); g2.drawString(status, Math.max(12, w / 2 - 70), h / 2);
                g2.dispose(); return;
            }
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
