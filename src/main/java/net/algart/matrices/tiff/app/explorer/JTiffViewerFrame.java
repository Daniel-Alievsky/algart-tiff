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
import net.algart.matrices.tiff.tags.TagPhotometric;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

class JTiffViewerFrame extends JFrame {
    private final TiffViewer viewer;
    private final JTiffViewerPanel viewerPanel;
    private final JTiffViewerScrollPane viewerScrollPane;
    private final JPanel noticePanel;
    private final JLabel noticeLabel;
    private final JLabel statusLabel;

    public JTiffViewerFrame(TiffViewer viewer) {
        this.viewer = Objects.requireNonNull(viewer);
        if (viewer.map() == null) {
            throw new AssertionError("map must be set before using the viewer frame");
        }
        TiffExplorer.setTiffExplorerIcon(this);
        this.setLayout(new BorderLayout());
        this.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.setJMenuBar(buildMenuBar());

        viewerPanel = new JTiffViewerPanel(viewer);

        noticePanel = new JPanel();
        noticePanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        noticeLabel = new JLabel("Lorem ipsum");
        noticePanel.add(noticeLabel);
        this.add(noticePanel, BorderLayout.NORTH);

        viewerScrollPane = new JTiffViewerScrollPane(viewerPanel);
        viewerScrollPane.setBorder(BorderFactory.createEmptyBorder());
        this.add(viewerScrollPane, BorderLayout.CENTER);

        final JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        statusLabel = new JLabel(TiffViewer.DEFAULT_STATUS);
        statusPanel.add(statusLabel);
        this.add(statusPanel, BorderLayout.SOUTH);

        resetImage();
        // The following can be used instead of setAccelerator:
//        frame.getRootPane().registerKeyboardAction(
//                e -> tiffPanel.removeSelection(),
//                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
//                JComponent.WHEN_IN_FOCUSED_WINDOW);
        this.pack();

        final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        final Dimension frameSize = this.getSize();
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

    public JTiffViewerPanel tiffPanel() {
        return viewerPanel;
    }

    public JLabel statusLabel() {
        return statusLabel;
    }

    @Override
    public void dispose() {
        super.dispose();
        viewer.disposeResources();
        viewerPanel.disposeResources();
    }

    void resetImage() {
        final TiffReadMap map = viewer.map();
        final OptionalInt bitDepth = map.tryEqualBitDepth();
        final double zoom = viewerPanel.getZoom();
        final int intZoom100 = (int) (zoom * 100.0);
        final String zoom100 = zoom * 100.0 == intZoom100 ?
                String.valueOf(intZoom100) :
                "%.1f".formatted(zoom * 100.0);
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
        final TagPhotometric photometric = map.photometric().orElse(null);
        final boolean simplyRenderable = photometric != null && photometric.isSimplyRenderable();
        if (!simplyRenderable) {
            noticeLabel.setText(
                    (photometric == null ? "Note: PhotometricInterpretation tag is not set" :
                            "Note: PhotometricInterpretation %s is not fully supported"
                                    .formatted(photometric.prettyName()))
                            + "; colors may be incorrect");
        }
        noticePanel.setVisible(!simplyRenderable);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem saveImageAsTiffItem = new JMenuItem("Save image as TIFF...");
        saveImageAsTiffItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveImageAsTiffItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseTiffFileToSaveImage(false);
            if (file != null) {
                try {
                    helper.showSaveImageDialog(viewer, file, false);
                } catch (Exception ex) {
                    showErrorMessage(ex, "Error copying TIFF");
                }
            }
        });
        fileMenu.add(saveImageAsTiffItem);
        JMenuItem exportImageItem = new JMenuItem("Export image...");
        exportImageItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseFileToExportImage(viewer, false);
            if (file != null) {
                try {
                    helper.exportImageToFile(viewer, file, false);
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
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        saveSelectionAsTiffItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseTiffFileToSaveImage(true);
            if (file != null) {
                try {
                    helper.showSaveImageDialog(viewer, file, true);
                } catch (Exception ex) {
                    showErrorMessage(ex, "Error copying TIFF");
                }
            }
        });
        fileMenu.add(saveSelectionAsTiffItem);
        JMenuItem exportSelectionItem = new JMenuItem("Export selection...");
        exportSelectionItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseFileToExportImage(viewer, true);
            if (file != null) {
                try {
                    helper.exportImageToFile(viewer, file, true);
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
        JMenuItem reloadItem = new JMenuItem("Reload image");
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
            viewerPanel.setSelectionAll();
        });
        editMenu.add(selectAllItem);
        JMenuItem setSelectionItem = new JMenuItem("Set selection...");
        setSelectionItem.addActionListener(e -> viewer.showSetSelectionDialog());
        editMenu.add(setSelectionItem);
        JMenuItem alignSelectionItem = new JMenuItem("Align selection to tiles");
        alignSelectionItem.addActionListener(e -> alignSelection());
        alignSelectionItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        editMenu.add(alignSelectionItem);
        JMenuItem removeSelectionItem = new JMenuItem("Remove selection");
        removeSelectionItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        removeSelectionItem.addActionListener(e -> viewerPanel.removeSelection());
        editMenu.add(removeSelectionItem);
        editMenu.addSeparator();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> {
            TiffExplorer.setWaitCursor(this, true);
            SwingUtilities.invokeLater(() -> {
                try {
                    final var helper = new TiffSaveImageHelper(this);
                    helper.copySelectedAreaToClipboard(viewer);
                } catch (IOException ex) {
                    showErrorMessage(ex, "Error copying the image to the clipboard");
                }  finally {
                    TiffExplorer.setWaitCursor(this, false);
                }
            });
        });
        editMenu.add(copyItem);

        JMenu viewMenu = new JMenu("View");
        JMenu colorCorrectionMenu = new JMenu("Color correction");
        ButtonGroup correctionGroup = new ButtonGroup();
        JRadioButtonMenuItem rawItem = new JRadioButtonMenuItem("No color correction");
        rawItem.setEnabled(false);
        rawItem.addActionListener(e -> setColorCorrection(false));

        JRadioButtonMenuItem correctedItem = new JRadioButtonMenuItem("Color correction");
        correctedItem.setEnabled(false);
        correctedItem.addActionListener(e -> setColorCorrection(true));
        correctionGroup.add(rawItem);
        correctionGroup.add(correctedItem);
        colorCorrectionMenu.add(rawItem);
        colorCorrectionMenu.add(correctedItem);
        viewMenu.add(colorCorrectionMenu);
        viewMenu.addSeparator();

        JCheckBoxMenuItem tileGridItem = new JCheckBoxMenuItem("Draw grid");
        tileGridItem.setSelected(false);
        tileGridItem.addActionListener(e -> {
            try {
                viewer.setTileGridVisibility(tileGridItem.isSelected());
                viewerPanel.repaint();
            } catch (Exception ex) {
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
            final TiffReadMap map = viewer.map();
            alignSelectionItem.setText(viewer.alignSelectionToTileGridCommand());
            alignSelectionItem.setEnabled(viewerPanel.isSelected());
            alignSelectionItem.setVisible(map.isTiled());
            removeSelectionItem.setEnabled(viewerPanel.isSelected());
            copyItem.setEnabled(viewerPanel.hasNonEmptySelection());
            exportSelectionItem.setEnabled(viewerPanel.hasNonEmptySelection());
            saveSelectionAsTiffItem.setEnabled(viewerPanel.hasNonEmptySelection());

            final boolean colorCorrectionApplicable = map.isColorCorrectionApplicable();
            final boolean colorCorrection = viewer.isColorCorrection();
            String rawLabel = map.numberOfChannels() == 1 ?
                    "No correction (grayscale, 0 is black)" :
                    "No correction (unsupported color models will be displayed as RGB)";
            String correctedLabel = map.numberOfChannels() == 1 ?
                    "Corrected (grayscale, 0 is white)" :
                    "Corrected (CMYK will be converted to RGB)";
            colorCorrectionMenu.setText(colorCorrectionApplicable ? "Color correction" : "Color correction (n/a)");
            rawItem.setText(rawLabel);
            rawItem.setEnabled(colorCorrectionApplicable);
            rawItem.setSelected(!colorCorrection);
            correctedItem.setText(correctedLabel);
            correctedItem.setEnabled(colorCorrectionApplicable);
            correctedItem.setSelected(colorCorrection);
            Color colorCorrectionForeground = colorCorrectionApplicable ?
                    viewMenu.getForeground() :
                    UIManager.getColor("MenuItem.disabledForeground");
            if (colorCorrectionForeground != null) {
                colorCorrectionMenu.setForeground(new Color(colorCorrectionForeground.getRGB()));
                // - when possible, emulate the "disabled" menu state without actual disabling;
                // may not work without explicit new Color(...)
            }
            tileGridItem.setText("Draw %s".formatted(map.isTiled() ? "tile grid" : "strip boundaries"));
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
                viewerScrollPane.setZoomWithCentering(zoomValue);
                resetImage();
                viewer.setTileGridThickness(tileGridThickness);
            } catch (TooBigZoomException | IOException ex) {
                showErrorMessage(ex, "Cannot set zoom");
            }
            viewerPanel.repaint();
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
            viewerPanel.setImageSelection(fromX, fromY, toX, toY);
        }
    }

    private void setColorCorrection(boolean colorCorrection) {
        try {
            viewer.setColorCorrection(colorCorrection);
            viewerPanel.repaint();
        } catch (IOException ex) {
            showErrorMessage(ex, "Error reloading image");
        }
    }

    private void showErrorMessage(Throwable e, String title) {
        TiffExplorer.showErrorMessage(this, e, title);
    }
}
