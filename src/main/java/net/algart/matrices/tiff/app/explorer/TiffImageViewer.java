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

import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

class TiffImageViewer {
    private static final String DEFAULT_STATUS =
            "Use mouse drag to select a rectangle, or SHIFT-drag to move the image";
    private static final long CACHING_MEMORY = 32 * 1048576L;
    // - 32MB is enough to store several user screens
    private static final boolean PRELOAD_LITTLE_AREA_WHILE_OPENING = true;
    // - should be true; you may clear this flag to debug possible I/O errors during the drawing process

    private static final System.Logger LOG = System.getLogger(TiffImageViewer.class.getName());

    private static final Color COMMON_COLOR = Color.BLACK;
    private static final Color ERROR_COLOR = Color.RED;

    private final TiffExplorer app;
    private final TiffReaderWithGrid reader;
    private final int index;

    private JLabel statusLabel;

    private TiffReadMap map = null;
    private int numberOfImages = -1;

    private Rectangle viewport = null;
    private BufferedImage image = null;
    private IOException exception = null;

    private String lastStatus = DEFAULT_STATUS;
    private boolean lastErrorFlag = false;

    private JTiffPanel tiffPanel;

    public TiffImageViewer(TiffExplorer app, Path tiffFile, int index) throws IOException {
        this.app = Objects.requireNonNull(app);
        Objects.requireNonNull(tiffFile);
        this.reader = new TiffReaderWithGrid(tiffFile);
        this.reader.setMaxCacheMemory(CACHING_MEMORY);
        LOG.log(System.Logger.Level.INFO, "Viewer opened " + reader.streamName());
        this.index = index;
    }

    public void disposeResources() {
        image = null;
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

    public TiffReadMap map() {
        return map;
    }

    public void show() throws IOException {
        openMap();
        createGUI();
    }

    public void showNormalStatus() {
        final Rectangle r = tiffPanel.currentFrame();
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

    /**
     * Loads the image fragment specified by the rectangle.
     * If the rectangle is too large, the argument is modified: the rectangle is cropped to fit the image dimensions.
     *
     * @param viewport fragment to load.
     * @return loaded image fragment or {@code null} if the fragment is empty.
     */
    public BufferedImage loadFragment(Rectangle viewport) {
        final int toX = Math.min(viewport.x + viewport.width, map.dimX());
        final int toY = Math.min(viewport.y + viewport.height, map.dimY());
        viewport.width = Math.max(toX - viewport.x, 0);
        viewport.height = Math.max(toY - viewport.y, 0);
        if (!Objects.equals(viewport, this.viewport)) {
            this.viewport = new Rectangle(viewport);
            try {
                image = viewport.width > 0 && viewport.height > 0 ?
                        map.readBufferedImage(viewport.x, viewport.y, viewport.width, viewport.height) :
                        null;
                exception = null;
            } catch (IOException e) {
                image = null;
                exception = e;
            }
            if (exception == null) {
                LOG.log(System.Logger.Level.DEBUG, "Viewer loaded the fragment %dx%d starting at (%d,%d)"
                        .formatted(viewport.width, viewport.height, viewport.x, viewport.y));
                showNormalStatus();
            } else {
                LOG.log(System.Logger.Level.ERROR, "Error while reading " + map.streamName() +
                        ": " + exception.getMessage(), exception);
                showError(exception.getMessage());
            }
        }
        return image;
    }

    public void resetCache() {
        reader.resetCache();
        viewport = null;
        image = null;
        exception = null;
    }

    private void createGUI() {
        final JTiffFrame frame = new JTiffFrame(this);
        frame.setTitle("TIFF Image #%d from %d images (%dx%d)".formatted(
                index, numberOfImages, map.dimX(), map.dimY()));
        frame.setLayout(new BorderLayout());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());

        tiffPanel = new JTiffPanel(this);
        final JScrollPane scrollPane = new JTiffScrollPane(tiffPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        frame.add(scrollPane, BorderLayout.CENTER);

        final JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        statusLabel = new JLabel(DEFAULT_STATUS);
        statusPanel.add(statusLabel);
        frame.add(statusPanel, BorderLayout.SOUTH);

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

        frame.setLocationRelativeTo(app.frame);
        frame.setVisible(true);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

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
                app.showErrorMessage(e, "Error reloading TIFF");
            }
        });
        viewMenu.add(reloadItem);

        JMenu editMenu = new JMenu("Edit");
        JMenuItem selectAllItem = new JMenuItem("Select all");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        selectAllItem.addActionListener(e -> {
            tiffPanel.setSelectionAll();
        });
        editMenu.add(selectAllItem);
        JMenuItem removeSelectionItem = new JMenuItem("Remove selection");
        removeSelectionItem.addActionListener(e -> {
            tiffPanel.removeSelection();
        });
        editMenu.add(removeSelectionItem);

        menuBar.add(viewMenu);
        menuBar.add(editMenu);
        editMenu.setMnemonic('E');
        viewMenu.setMnemonic('V');
        return menuBar;
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
}
