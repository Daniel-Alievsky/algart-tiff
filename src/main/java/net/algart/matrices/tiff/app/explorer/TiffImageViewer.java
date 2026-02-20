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

import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

class TiffImageViewer {
    private static final String DEFAULT_STATUS =
            "Use mouse drag to select a rectangle, or SHIFT-drag to move the image";
    private static final long CACHING_MEMORY = 32 * 1048576L;
    // - 32MB is enough to store several user screens
    private static final boolean PRELOAD_LITTLE_AREA_WHILE_OPENING = true;
    // - should be true; you may clear this flag to debug possible I/O errors during the drawing process

    private static final String PREF_LAST_EXPORT_SELECTION_DIR = "lastExportSelectionDirectory";
    private static final FileFilter ANY_IMAGE_FILTER = new FileNameExtensionFilter(
            "Image files (*.png, *.jpg, *.jpeg, *.bmp, *.gif, *.tif, *.tiff)",
            "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff");

    private static final System.Logger LOG = System.getLogger(TiffImageViewer.class.getName());

    private static final Color COMMON_COLOR = Color.BLACK;
    private static final Color ERROR_COLOR = Color.RED;

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

    private JFrame frame;
    private JTiffPanel tiffPanel;

    public TiffImageViewer(Path tiffFile, int index) throws IOException {
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
        final Rectangle r = tiffPanel.getSelection();
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

    public void copyImageToClipboard() throws IOException {
        BufferedImage image = readSelectedImage();
        if (image != null) {
            LOG.log(System.Logger.Level.DEBUG, "Copied image to clipboard: " + image);
            ClipboardTools.copyImageToClipboard(image);
        }
    }

    public void exportImageToFile(Path file) throws IOException {
        Objects.requireNonNull(file, "Null file");
        BufferedImage image = readSelectedImage();
        if (image != null) {
            LOG.log(System.Logger.Level.DEBUG, "Export image to " + file + ": " + image);
            MatrixIO.writeBufferedImage(file, image);
        }
    }

    /**
     * Loads the image fragment specified by the rectangle.
     * If the rectangle is too large, the argument is modified: the rectangle is cropped to fit the image dimensions.
     *
     * @param viewport fragment to load.
     * @return loaded image fragment or {@code null} if the fragment is empty.
     */
    public BufferedImage reloadFragment(Rectangle viewport) {
        final int toX = Math.min(viewport.x + viewport.width, map.dimX());
        final int toY = Math.min(viewport.y + viewport.height, map.dimY());
        viewport.width = Math.max(toX - viewport.x, 0);
        viewport.height = Math.max(toY - viewport.y, 0);
        if (!Objects.equals(viewport, this.viewport)) {
            this.viewport = new Rectangle(viewport);
            try {
                image = readImage(viewport);
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

    private BufferedImage readSelectedImage() throws IOException {
        Rectangle rectangle = tiffPanel.getSelection();
        return rectangle == null ? null : readImage(rectangle);
    }

    public BufferedImage readImage(Rectangle viewport) throws IOException {
        return viewport.width > 0 && viewport.height > 0 ?
                map.readBufferedImage(viewport.x, viewport.y, viewport.width, viewport.height) :
                null;
    }

    public void resetCache() {
        reader.resetCache();
        viewport = null;
        image = null;
        exception = null;
    }

    private void createGUI() {
        frame = new JTiffFrame(this);
        final OptionalInt bitDepth = map.tryEqualBitDepth();
        frame.setTitle("TIFF Image #%d from %d images (%dx%d, %d channel%s, %s bits/channel)  %s".formatted(
                index, numberOfImages, map.dimX(), map.dimY(),
                map.numberOfChannels(), map.numberOfChannels() == 1 ? "" : "s",
                bitDepth.isPresent() ? bitDepth.getAsInt() : Arrays.toString(map.bitsPerSample()),
                map.compression().orElse(TagCompression.NONE).prettyName()));
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

        frame.setLocationRelativeTo(null);
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
                showErrorMessage(e, "Error reloading TIFF");
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
        editMenu.addSeparator();
        JMenuItem exportItem = new JMenuItem("Export selection...");
        exportItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        exportItem.addActionListener(e -> {
            Path file = chooseExportFile();
            if (file != null) {
                try {
                    exportImageToFile(file);
                } catch (Exception ex) {
                    // - including possible non-I/O exceptions like an empty file extension
                    showErrorMessage(ex, "Error saving image");
                }
            }
        });
        editMenu.add(exportItem);

        final MenuUpdater menuUpdater  = new MenuUpdater(() -> {
            removeSelectionItem.setEnabled(tiffPanel.isSelected());
            copyItem.setEnabled(tiffPanel.hasNonEmptySelection());
            exportItem.setEnabled(tiffPanel.hasNonEmptySelection());
        });
        viewMenu.addMenuListener(menuUpdater);
        editMenu.addMenuListener(menuUpdater);

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

    private Path chooseExportFile() {
        JFileChooser chooser = new JFileChooser();

        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_EXPORT_SELECTION_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Export selected image as...");
        chooser.setSelectedFile(new File("untitled.png"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(ANY_IMAGE_FILTER);
        chooser.setFileFilter(ANY_IMAGE_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        int result = chooser.showSaveDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return null;
        }
        if (file.exists()) {
            int answer = JOptionPane.showConfirmDialog(
                    frame,
                    "File already exists:\n" + file.getAbsolutePath() + "\nDo you want to replace it?",
                    "Confirm overwrite",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.YES_OPTION) {
                return null;
            }
        }
        TiffExplorer.PREFERENCES.put(PREF_LAST_EXPORT_SELECTION_DIR, file.getParent());
        return file.toPath();
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
