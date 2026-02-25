/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.algart.matrices.tiff.app.explorer;

import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

class TiffImageViewer {
    private static final String DEFAULT_STATUS =
            "Use mouse drag to select a rectangle, or SHIFT-drag to move the image";
    private static final long CACHING_MEMORY = 256 * 1048576L;
    // - 256MB = 16 * 16M is enough to store several user screens even with the zoom 1:4
    private static final boolean PRELOAD_LITTLE_AREA_WHILE_OPENING = true;
    // - should be true; you may clear this flag to debug possible I/O errors during the drawing process

    private static final System.Logger LOG = System.getLogger(TiffImageViewer.class.getName());

    private static final Color COMMON_COLOR = Color.BLACK;
    private static final Color ERROR_COLOR = Color.RED;

    private final TiffReaderWithGrid reader;
    private final int index;

    private JLabel statusLabel;

    private TiffReadMap map = null;
    private int numberOfImages = -1;

    private double lastImageZoom = 0.0;
    private Rectangle lastImageRectangle = null;
    private BufferedImage lastImage = null;
    private Throwable exception = null;

    private String lastStatus = DEFAULT_STATUS;
    private boolean lastErrorFlag = false;

    private JFrame frame;
    private JTiffPanel tiffPanel;
    private JTiffScrollPane tiffScrollPane;

    public TiffImageViewer(Path tiffFile, int index) throws IOException {
        Objects.requireNonNull(tiffFile);
        this.reader = new TiffReaderWithGrid(tiffFile);
        this.reader.setMaxCacheMemory(CACHING_MEMORY);
        LOG.log(System.Logger.Level.INFO, "Viewer opened " + reader.streamName());
        this.index = index;
    }

    public void disposeResources() {
        lastImage = null;
        tiffPanel.disposeResources();
        try {
            this.reader.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.INFO, "Error while closing " + reader.streamName() +
                    ": " + e.getMessage(), e);
            // - no sense to show this error to the end user
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "Viewer closed " + reader.streamName());
    }

    public TiffReader reader() {
        return reader;
    }

    public TiffReadMap map() {
        return map;
    }

    public void show() throws IOException {
        openMap();
        createGUI();
    }

    public void showNormalStatus() {
        final Rectangle r = getSelection();
        final String status = r == null ?
                DEFAULT_STATUS :
                r.width == 0 || r.height == 0 ?
                        "%dx%d: top-left (%d,%d)".formatted(
                                r.width, r.height, r.x, r.y) :
                        "%dx%d: top-left (%d,%d), bottom-right (%d,%d)".formatted(
                                r.width, r.height, r.x, r.y, r.x + r.width - 1, r.y + r.height - 1);
        showStatus(status);
    }

    public void showStatus(String status) {
        setStatus(status, false);
    }

    public void showError(String status) {
        setStatus(status, true);
    }

    public void reload() throws IOException {
        resetCache();
        openMap();
        tiffPanel.reset();
        updateView();
    }

    public void resetTitle() {
        final OptionalInt bitDepth = map.tryEqualBitDepth();
        final double zoom = tiffPanel.getZoom();
        final int intZoom100 = (int) (zoom * 100.0);
        final String zoom100 = zoom * 100.0 == intZoom100 ?
                String.valueOf(intZoom100) :
                "%.1f".formatted(zoom * 1);
        final String zoomTitle = zoom == 1.0 ? "" :
                zoom > 1.0
                        ? "  %s%% (%d:1)".formatted(zoom100, (int) zoom)
                        : "  %s%% (1:%d)".formatted(zoom100, (int) Math.round(1.0 / zoom));
        frame.setTitle("TIFF Image #%d from %d (%dx%d, %d channel%s, %s bits/channel)  %s%s".formatted(
                index, numberOfImages, map.dimX(), map.dimY(),
                map.numberOfChannels(), map.numberOfChannels() == 1 ? "" : "s",
                bitDepth.isPresent() ? bitDepth.getAsInt() : Arrays.toString(map.bitsPerSample()),
                map.compression().orElse(TagCompression.NONE).prettyName(),
                zoomTitle));
    }

    public Rectangle getSelection() {
        return tiffPanel.getImageSelection();
    }

    public void setSelection(int left, int top, int width, int height) {
        tiffPanel.setImageSelection(left, top, (long) left + (long) width, (long) top + (long) height);
    }

    public void copyImageToClipboard() throws IOException {
        BufferedImage image = readSelectedImage();
        if (image != null) {
            LOG.log(System.Logger.Level.DEBUG, "Copied image to clipboard: " + image);
            ClipboardTools.copyImageToClipboard(image);
        }
    }

    public BufferedImage reloadFragment(int zoomedFromX, int zoomedFromY, int zoomedToX, int zoomedToY, double zoom) {
        final int zoomedSizeX = zoomedToX - zoomedFromX;
        final int zoomedSizeY = zoomedToY - zoomedFromY;
        if (zoomedSizeX <= 0 || zoomedSizeY <= 0) {
            return null;
        }
        int fromX = (int) Math.floor(zoomedFromX / zoom);
        int fromY = (int) Math.floor(zoomedFromY / zoom);
        int toX = (int) Math.ceil(zoomedToX / zoom);
        int toY = (int) Math.ceil(zoomedToY / zoom);
        final Rectangle r = new Rectangle(fromX, fromY, toX - fromX, toY - fromY);
        if (zoom != lastImageZoom || !Objects.equals(r, this.lastImageRectangle)) {
            BufferedImage original;
            BufferedImage scaled;
            this.lastImageRectangle = new Rectangle(r);
            this.lastImageZoom = zoom;
            try {
                original = readImage(r);
                scaled = zoom == 1.0 || original == null ?
                        original :
                        new BufferedImage(zoomedSizeX, zoomedSizeY, original.getColorModel().hasAlpha() ?
                                BufferedImage.TYPE_INT_ARGB :
                                BufferedImage.TYPE_INT_RGB);
                exception = null;
            } catch (Throwable e) {
                // - including possible too large rectangles (IllegalArgumentException)
                original = null;
                scaled = null;
                exception = e;
            }
            if (exception != null) {
                LOG.log(System.Logger.Level.ERROR, "Error while reading " + map.streamName() +
                        ": " + exception.getMessage(), exception);
                showError(exception.getMessage());
                return null;
            }
            if (zoom != 1.0 && scaled != null) {
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        zoom < 1.0 ?
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR :
                                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(original, 0, 0, zoomedSizeX, zoomedSizeY, null);
                g.dispose();
            }
            lastImage = scaled;
            LOG.log(System.Logger.Level.DEBUG, "Viewer loaded the image region %dx%d starting at (%d,%d)%s"
                    .formatted(r.width, r.height, r.x, r.y,
                            zoom == 1.0 ? "" : " and scaled it to %dx%d (zoom %s)"
                                    .formatted(zoomedSizeX, zoomedSizeY, zoom)));
            showNormalStatus();
        }
        return lastImage;
    }

    private void alignSelection() {
        final Rectangle selection = getSelection();
        if (map.isTiled() && selection != null) {
            final long tileX = map.tileSizeX();
            final long tileY = map.tileSizeY();
            assert tileX > 0 && tileY > 0;
            final long fromX = (long) selection.x / tileX * tileX;
            final long fromY = (long) selection.y / tileY * tileY;
            final long toX = ((long) selection.x + (long) selection.width + tileX - 1) / tileX * tileX;
            final long toY = ((long) selection.y + (long) selection.height + tileY - 1) / tileY * tileY;
            tiffPanel.setImageSelection(fromX, fromY, toX, toY);
        }
    }

    public BufferedImage readSelectedImage() throws IOException {
        Rectangle rectangle = getSelection();
        return rectangle == null ? null : readImage(rectangle);
    }

    public BufferedImage readImage(Rectangle viewport) throws IOException {
        return viewport.width > 0 && viewport.height > 0 ?
                map.readBufferedImage(viewport.x, viewport.y, viewport.width, viewport.height) :
                null;
    }

    public void resetCache() {
        reader.resetCache();
        lastImageRectangle = null;
        lastImage = null;
        exception = null;
    }

    private void createGUI() {
        frame = new JTiffFrame(this);
        frame.setLayout(new BorderLayout());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());

        tiffPanel = new JTiffPanel(this);
        tiffScrollPane = new JTiffScrollPane(tiffPanel);
        tiffScrollPane.setBorder(BorderFactory.createEmptyBorder());
        frame.add(tiffScrollPane, BorderLayout.CENTER);

        final JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        statusLabel = new JLabel(DEFAULT_STATUS);
        statusPanel.add(statusLabel);
        frame.add(statusPanel, BorderLayout.SOUTH);

        resetTitle();
        frame.getRootPane().registerKeyboardAction(
                e -> tiffPanel.removeSelection(),
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        frame.pack();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension frameSize = frame.getSize();
        frameSize.width = Math.min(frameSize.width, screenSize.width - 10);
        frameSize.height = Math.min(frameSize.height, screenSize.height - 50);
        // - ensure that scroll bar and other elements will be visible even for very large images
        frame.setSize(frameSize);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem exportItem = new JMenuItem("Export selection...");
        exportItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        exportItem.addActionListener(e -> {
            final var copier = new TiffImageCopier(this, frame);
            Path file = copier.chooseFileToExport();
            if (file != null) {
                try {
                    copier.exportSelectedImageToFile(file);
                } catch (Exception ex) {
                    // - including possible non-I/O exceptions like an empty file extension
                    showErrorMessage(ex, "Error exporting image");
                }
            }
        });
        fileMenu.add(exportItem);
        JMenuItem saveToTiffItem = new JMenuItem("Save selection to a new TIFF...");
        saveToTiffItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveToTiffItem.addActionListener(e -> {
            final var copier = new TiffImageCopier(this, frame);
            Path file = copier.chooseTiffFileToCopy();
            if (file != null) {
                copier.showCopyToTiffDialog(file);
            }
        });
        fileMenu.add(saveToTiffItem);

        JMenu editMenu = new JMenu("Edit");
        JMenuItem selectAllItem = new JMenuItem("Select all");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        selectAllItem.addActionListener(e -> {
            tiffPanel.setSelectionAll();
        });
        editMenu.add(selectAllItem);
        JMenuItem setSelectionItem = new JMenuItem("Set selection...");
        setSelectionItem.addActionListener(e -> showSetSelectionDialog());
        editMenu.add(setSelectionItem);
        JMenuItem alignSelectionItem = map.isTiled() ? new JMenuItem("Align selection to tile grid") : null;
        if (alignSelectionItem != null) {
            alignSelectionItem.addActionListener(e -> alignSelection());
            alignSelectionItem.setAccelerator(KeyStroke.getKeyStroke(
                    KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
            editMenu.add(alignSelectionItem);
        }
        JMenuItem removeSelectionItem = new JMenuItem("Remove selection");
        removeSelectionItem.addActionListener(e -> {
            tiffPanel.removeSelection();
        });
        editMenu.add(removeSelectionItem);
        editMenu.addSeparator();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> {
            try {
                copyImageToClipboard();
            } catch (IOException ex) {
                showErrorMessage(ex, "Error copying image to clipboard");
            }
        });
        editMenu.add(copyItem);

        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem tileGridItem = new JCheckBoxMenuItem(
                "Draw %s".formatted(map.isTiled() ? "tile grid" : "strip boundaries"));
        tileGridItem.setSelected(false);
        tileGridItem.addActionListener(e -> {
            reader.setViewTileGrid(tileGridItem.isSelected());
            updateView();
        });
        viewMenu.add(tileGridItem);

        // RandomAccessFile does not strictly lock the file: other processes, for example,
        // other instances of TiffExplorer may edit the same file
        JMenuItem reloadItem = new JMenuItem("Reload TIFF image");
        reloadItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        reloadItem.addActionListener(event -> {
            try {
                reload();
            } catch (Exception e) {
                showErrorMessage(e, "Error reloading TIFF");
            }
        });
        viewMenu.add(reloadItem);
        JMenu zoomMenu = new JMenu("Zoom");
        ButtonGroup zoomGroup = new ButtonGroup();
        addZoomItem(zoomMenu, zoomGroup, "800% (8:1)", 8.0);
        addZoomItem(zoomMenu, zoomGroup, "400% (4:1)", 4.0);
        addZoomItem(zoomMenu, zoomGroup, "200% (2:1)", 2.0);
        addZoomItem(zoomMenu, zoomGroup, "100% (1:1)", 1.0);
        addZoomItem(zoomMenu, zoomGroup, "50% (1:2)", 0.5);
        addZoomItem(zoomMenu, zoomGroup, "25% (1:4, may be slow)", 0.25);
        addZoomItem(zoomMenu, zoomGroup, "12.5% (1:8, may be slow)", 0.125);
        viewMenu.addSeparator();
        viewMenu.add(zoomMenu);

        final MenuUpdater menuUpdater = new MenuUpdater(() -> {
            if (alignSelectionItem != null) {
                alignSelectionItem.setEnabled(tiffPanel.isSelected());
            }
            removeSelectionItem.setEnabled(tiffPanel.isSelected());
            copyItem.setEnabled(tiffPanel.hasNonEmptySelection());
            exportItem.setEnabled(tiffPanel.hasNonEmptySelection());
            saveToTiffItem.setEnabled(tiffPanel.hasNonEmptySelection());
        });
        fileMenu.addMenuListener(menuUpdater);
        editMenu.addMenuListener(menuUpdater);
        viewMenu.addMenuListener(menuUpdater);

        fileMenu.setMnemonic('F');
        viewMenu.setMnemonic('V');
        editMenu.setMnemonic('E');
        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);
        return menuBar;
    }

    private void addZoomItem(JMenu menu, ButtonGroup group, String title, double zoomValue) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(title);
        if (zoomValue == 1.0) {
            item.setSelected(true);
        }
        item.addActionListener(e -> {
            try {
                tiffScrollPane.setZoomWithCentering(zoomValue);
                resetTitle();
            } catch (IllegalArgumentException ex) {
                showErrorMessage(ex, "Cannot set zoom");
            }
            item.setSelected(true);
            updateView();
        });
        group.add(item);
        menu.add(item);
    }

    private void setStatus(String status, boolean error) {
        Objects.requireNonNull(status);
        if (error != lastErrorFlag || !status.equals(lastStatus)) {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setForeground(error ? ERROR_COLOR : COMMON_COLOR);
                statusLabel.setText(status);
            });
            lastStatus = status;
            lastErrorFlag = error;
        }
    }

    private void openMap() throws IOException {
        map = reader.map(index);
        numberOfImages = reader.numberOfImages();
        if (PRELOAD_LITTLE_AREA_WHILE_OPENING) {
            map.readSampleBytes(0, 0, 64, 64);
        }
    }

    private void updateView() {
        resetCache();
        tiffPanel.repaint();
    }

    private void showSetSelectionDialog() {
        final JPanel panel = new JPanel(new GridLayout(4, 2, 5, 5));
        final JTextField leftField = new JTextField(8);
        final JTextField topField = new JTextField(8);
        final JTextField widthField = new JTextField(8);
        final JTextField heightField = new JTextField(8);

        final Rectangle current = tiffPanel.getImageSelection();
        if (current != null) {
            leftField.setText(Integer.toString(current.x));
            topField.setText(Integer.toString(current.y));
            widthField.setText(Integer.toString(current.width));
            heightField.setText(Integer.toString(current.height));
        } else {
            leftField.setText("0");
            topField.setText("0");
            widthField.setText(Integer.toString(map.dimX()));
            heightField.setText(Integer.toString(map.dimY()));
        }

        panel.add(new JLabel("Left:"));
        panel.add(leftField);
        panel.add(new JLabel("Top:"));
        panel.add(topField);
        panel.add(new JLabel("Width:"));
        panel.add(widthField);
        panel.add(new JLabel("Height:"));
        panel.add(heightField);

        final int result = JOptionPane.showConfirmDialog(
                frame,
                panel,
                "Set selection rectangle",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            final int left = Integer.parseInt(leftField.getText().trim());
            final int top = Integer.parseInt(topField.getText().trim());
            final int width = Integer.parseInt(widthField.getText().trim());
            final int height = Integer.parseInt(heightField.getText().trim());
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Width and height must be non-negative");
            }
            setSelection(left, top, width, height);
        } catch (Exception ex) {
            showErrorMessage(ex, "Invalid selection values");
        }
    }

    private void showErrorMessage(Throwable e, String title) {
        TiffExplorer.showErrorMessage(frame, e, title);
    }

    private static final class MenuUpdater implements MenuListener {
        private final Runnable updater;

        private MenuUpdater(Runnable updater) {
            this.updater = updater;
        }

        @Override
        public void menuSelected(MenuEvent e) {
            updater.run();
        }

        @Override
        public void menuDeselected(MenuEvent e) {

        }

        @Override
        public void menuCanceled(MenuEvent e) {
        }
    }
}
