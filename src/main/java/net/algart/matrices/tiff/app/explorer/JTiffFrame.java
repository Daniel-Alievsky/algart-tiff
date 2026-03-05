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

import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

class JTiffFrame extends JFrame {
    private final TiffViewer viewer;
    private JTiffPanel tiffPanel;
    private JTiffScrollPane tiffScrollPane;
    private JLabel statusLabel;

    public JTiffFrame(TiffViewer viewer) {
        this.viewer = Objects.requireNonNull(viewer);
        TiffExplorer.setTiffExplorerIcon(this);
        this.setLayout(new BorderLayout());
        this.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.setJMenuBar(buildMenuBar());

        tiffPanel = new JTiffPanel(viewer);
        tiffScrollPane = new JTiffScrollPane(tiffPanel);
        tiffScrollPane.setBorder(BorderFactory.createEmptyBorder());
        this.add(tiffScrollPane, BorderLayout.CENTER);

        final JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        statusLabel = new JLabel(TiffViewer.DEFAULT_STATUS);
        statusPanel.add(statusLabel);
        this.add(statusPanel, BorderLayout.SOUTH);

        resetTitle();
        // The following can be used instead of setAccelerator:
//        frame.getRootPane().registerKeyboardAction(
//                e -> tiffPanel.removeSelection(),
//                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
//                JComponent.WHEN_IN_FOCUSED_WINDOW);
        this.pack();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension frameSize = this.getSize();
        frameSize.width = Math.min(frameSize.width, screenSize.width - 10);
        frameSize.height = Math.min(frameSize.height, screenSize.height - 50);
        // - ensure that scroll bar and other elements will be visible even for very large images
        this.setSize(frameSize);

        this.setLocationRelativeTo(null);
        this.setVisible(true);
    }

    public TiffViewer viewer() {
        return viewer;
    }

    public JTiffPanel tiffPanel() {
        return tiffPanel;
    }

    public JLabel statusLabel() {
        return statusLabel;
    }

    @Override
    public void dispose() {
        super.dispose();
        viewer.disposeResources();
        tiffPanel.disposeResources();
    }

    void resetTitle() {
        final TiffReadMap map = viewer.map();
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
        setTitle("TIFF Image #%d from %d in the file (%dx%d, %d channel%s, %s bits/channel)  %s%s  [%s]".formatted(
                viewer.ifdIndex(), map.numberOfImages(),
                map.dimX(), map.dimY(),
                map.numberOfChannels(), map.numberOfChannels() == 1 ? "" : "s",
                bitDepth.isPresent() ? bitDepth.getAsInt() : Arrays.toString(map.bitsPerSample()),
                map.compression().orElse(TagCompression.NONE).prettyName(),
                zoomTitle, viewer.path().getFileName()));
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem saveImageAsTiffItem = new JMenuItem("Save image as TIFF...");
        saveImageAsTiffItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveImageAsTiffItem.addActionListener(e -> {
            final var export = new TiffViewerExport(this);
            Path file = export.chooseTiffFileToSave(false);
            if (file != null) {
                try {
                    export.showCopyToTiffDialog(file, false);
                } catch (Exception ex) {
                    // - should not occur
                    showErrorMessage(ex, "Unexpected error");
                }
            }
        });
        fileMenu.add(saveImageAsTiffItem);
        JMenuItem exportImageItem = new JMenuItem("Export image...");
        exportImageItem.addActionListener(e -> {
            final var export = new TiffViewerExport(this);
            Path file = export.chooseFileToExport(false);
            if (file != null) {
                try {
                    export.exportImageToFile(file, false);
                } catch (Exception ex) {
                    // - including possible non-I/O exceptions like an empty file extension
                    showErrorMessage(ex, "Error exporting image");
                }
            }
        });
        fileMenu.add(exportImageItem);
        fileMenu.addSeparator();

        JMenuItem saveSelectionAsTiffItem = new JMenuItem("Save selection as TIFF...");
        saveSelectionAsTiffItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveSelectionAsTiffItem.addActionListener(e -> {
            final var export = new TiffViewerExport(this);
            Path file = export.chooseTiffFileToSave(true);
            if (file != null) {
                try {
                    export.showCopyToTiffDialog(file, true);
                } catch (Exception ex) {
                    // - should not occur
                    showErrorMessage(ex, "Unexpected error");
                }
            }
        });
        fileMenu.add(saveSelectionAsTiffItem);
        JMenuItem exportSelectionItem = new JMenuItem("Export selection...");
        exportSelectionItem.addActionListener(e -> {
            final var export = new TiffViewerExport(this);
            Path file = export.chooseFileToExport(true);
            if (file != null) {
                try {
                    export.exportImageToFile(file, true);
                } catch (Exception ex) {
                    // - including possible non-I/O exceptions like an empty file extension
                    showErrorMessage(ex, "Error exporting the selected area");
                }
            }
        });
        fileMenu.add(exportSelectionItem);
        fileMenu.addSeparator();
        // RandomAccessFile does not strictly lock the file: other processes, for example,
        // other instances of TiffExplorer may edit the same file
        JMenuItem reloadItem = new JMenuItem("Reload TIFF image");
        reloadItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        reloadItem.addActionListener(event -> {
            try {
                viewer.reload();
            } catch (Exception e) {
                showErrorMessage(e, "Error reloading TIFF");
            }
        });
        fileMenu.add(reloadItem);

        JMenu editMenu = new JMenu("Edit");
        JMenuItem selectAllItem = new JMenuItem("Select all");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        selectAllItem.addActionListener(e -> {
            tiffPanel.setSelectionAll();
        });
        editMenu.add(selectAllItem);
        JMenuItem setSelectionItem = new JMenuItem("Set selection...");
        setSelectionItem.addActionListener(e -> viewer.showSetSelectionDialog());
        editMenu.add(setSelectionItem);
        JMenuItem alignSelectionItem = viewer.map().isTiled() ?
                new JMenuItem("Align selection to tile grid") :
                null;
        if (alignSelectionItem != null) {
            alignSelectionItem.addActionListener(e -> alignSelection());
            alignSelectionItem.setAccelerator(KeyStroke.getKeyStroke(
                    KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
            editMenu.add(alignSelectionItem);
        }
        JMenuItem removeSelectionItem = new JMenuItem("Remove selection");
        removeSelectionItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        removeSelectionItem.addActionListener(e -> {
            tiffPanel.removeSelection();
        });
        editMenu.add(removeSelectionItem);
        editMenu.addSeparator();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> {
            try {
                final var export = new TiffViewerExport(this);
                export.copySelectedAreaToClipboard();
            } catch (IOException ex) {
                showErrorMessage(ex, "Error copying the image to the clipboard");
            }
        });
        editMenu.add(copyItem);

        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem tileGridItem = new JCheckBoxMenuItem(
                "Draw %s".formatted(viewer.map().isTiled() ? "tile grid" : "strip boundaries"));
        tileGridItem.setSelected(false);
        tileGridItem.addActionListener(e -> {
            try {
                viewer.setTileGridVisibility(tileGridItem.isSelected());
                tiffPanel.repaint();
            } catch (IOException ex) {
                showErrorMessage(ex, "Error reloading image");
            }
        });
        viewMenu.add(tileGridItem);

        JMenu zoomMenu = new JMenu("Zoom");
        ButtonGroup zoomGroup = new ButtonGroup();
        addZoomItem(zoomMenu, zoomGroup, "800% (8:1)", 8.0);
        addZoomItem(zoomMenu, zoomGroup, "400% (4:1)", 4.0);
        addZoomItem(zoomMenu, zoomGroup, "200% (2:1)", 2.0);
        addZoomItem(zoomMenu, zoomGroup, "100% (1:1)", 1.0);
        addZoomItem(zoomMenu, zoomGroup, "50% (1:2)", 0.5);
        addZoomItem(zoomMenu, zoomGroup, "25% (1:4, may be slow)", 0.25, 2);
        addZoomItem(zoomMenu, zoomGroup, "12.5% (1:8, may be slow)", 0.125, 4);
        viewMenu.addSeparator();
        viewMenu.add(zoomMenu);

        final MenuUpdater menuUpdater = new MenuUpdater(() -> {
            if (alignSelectionItem != null) {
                alignSelectionItem.setEnabled(tiffPanel.isSelected());
            }
            removeSelectionItem.setEnabled(tiffPanel.isSelected());
            copyItem.setEnabled(tiffPanel.hasNonEmptySelection());
            exportSelectionItem.setEnabled(tiffPanel.hasNonEmptySelection());
            saveSelectionAsTiffItem.setEnabled(tiffPanel.hasNonEmptySelection());
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
        addZoomItem(menu, group, title, zoomValue, 1);
    }

    private void addZoomItem(JMenu menu, ButtonGroup group, String title, double zoomValue, int tileGridThickness) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(title);
        if (zoomValue == 1.0) {
            item.setSelected(true);
        }
        item.addActionListener(e -> {
            try {
                tiffScrollPane.setZoomWithCentering(zoomValue);
                resetTitle();
                viewer.setTileGridThickness(tileGridThickness);
            } catch (TooBigZoomException | IOException ex) {
                showErrorMessage(ex, "Cannot set zoom");
            }
            tiffPanel.repaint();
            item.setSelected(true);
        });
        group.add(item);
        menu.add(item);
    }

    private void alignSelection() {
        final Rectangle selection = viewer.getSelection();
        final TiffReadMap map = viewer.map();
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

    private void showErrorMessage(Throwable e, String title) {
        TiffExplorer.showErrorMessage(this, e, title);
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
