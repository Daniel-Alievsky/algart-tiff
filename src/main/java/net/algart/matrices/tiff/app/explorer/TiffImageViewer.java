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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

class TiffImageViewer {
    private static final System.Logger LOG = System.getLogger(TiffExplorer.class.getName());
    private static final String DEFAULT_STATUS = "Please use SHIFT + mouse drag to move image";

    private static final Color COMMON_COLOR = Color.BLACK;
    private static final Color ERROR_COLOR = Color.RED;

    private final TiffExplorer app;
    private final TiffReaderWithGrid reader;
    private final int index;

    private JScrollPane scrollPane;
    private JLabel statusLabel;

    private TiffReadMap map = null;
    private int numberOfImages;
    private int dimX;
    private int dimY;

    private String lastStatus = DEFAULT_STATUS;
    private boolean lastErrorFlag = false;

    private JTiffPanel tiffPanel;

    public TiffImageViewer(TiffExplorer app, Path tiffFile, int index) throws IOException {
        this.app = Objects.requireNonNull(app);
        Objects.requireNonNull(tiffFile);
        this.reader = new TiffReaderWithGrid(tiffFile);
        LOG.log(System.Logger.Level.INFO, "Viewer opened " + reader.streamName());
        this.index = index;
    }

    public void dispose() {
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

    public void showDefaultStatus() {
        showStatus(DEFAULT_STATUS);
    }

    public void showStatus(String status) {
        setStatus(status, false);
    }

    public void showError(String status) {
        setStatus(status, true);
    }

    public void reload() throws IOException {
        reader.resetCache();
        openMap();
        tiffPanel.reset();
        updateView();
    }

    private void openMap() throws IOException {
        map = reader.map(index);
        numberOfImages = reader.numberOfImages();
        dimX = map.dimX();
        dimY = map.dimY();
    }

    private void createGUI() {
        JFrame frame = new JTiffFrame(this);
        frame.setTitle("TIFF Image #%d from %d images (%dx%d%s)".formatted(
                index, numberOfImages, dimX, dimY, dimX == map.dimX() && dimY == map.dimY() ?
                        "" :
                        " from %dx%d".formatted(map.dimX(), map.dimY())));
        frame.setLayout(new BorderLayout());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());

        tiffPanel = new JTiffPanel(this);
        scrollPane = new JTiffScrollPane(tiffPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        frame.add(scrollPane, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        statusLabel = new JLabel(DEFAULT_STATUS);
        statusPanel.add(statusLabel);
        frame.add(statusPanel, BorderLayout.SOUTH);
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
        JCheckBoxMenuItem tileItem = new JCheckBoxMenuItem(
                "Draw %s".formatted(map.isTiled() ? "tile grid" : "strip boundaries"));
        tileItem.setSelected(false);
        tileItem.addActionListener(e -> {
            reader.setViewTileGrid(tileItem.isSelected());
            updateView();
        });
        viewMenu.add(tileItem);
        menuBar.add(viewMenu);

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
        menuBar.add(viewMenu);
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

    private void updateView() {
        reader.resetCache();
        tiffPanel.repaint();
    }
}
