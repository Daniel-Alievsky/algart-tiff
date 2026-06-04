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
import java.util.*;
import java.util.function.Consumer;

class JTiffViewerFrame extends JFrame {
    private final TiffViewer viewer;
    private final JTiffViewerPanel viewerPanel;
    private final JTiffViewerScrollPane viewerScrollPane;
    private final JPanel noticePanel;
    private final JLabel noticeLabel;
    private final StableSizeLabel statusPixelCoordinatesLabel;
    private final StableSizeLabel statusPixelValueLabel;
    private final JLabel statusSelectionLabel;

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

        statusPixelCoordinatesLabel = new StableSizeLabel(TiffViewer.pixelCoordinatesToString(9999, 9999));
        statusPixelValueLabel = new StableSizeLabel(TiffViewer.DEFAULT_PIXEL_VALUE);
        // - current version does not use stability of statusPixelValueLabel
        statusSelectionLabel = new JLabel(TiffViewer.DEFAULT_STATUS);
        statusPixelCoordinatesLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        statusPixelValueLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        statusSelectionLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        JPanel statusLeftPanel = new JPanel(new BorderLayout());
        statusLeftPanel.add(statusPixelCoordinatesLabel, BorderLayout.WEST);
        statusLeftPanel.add(statusPixelValueLabel, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLeftPanel, BorderLayout.WEST);
        statusPanel.add(statusSelectionLabel, BorderLayout.CENTER);
        this.add(statusPanel, BorderLayout.SOUTH);

        resetImageInformation();
        // The following can be used instead of setAccelerator:
//        frame.getRootPane().registerKeyboardAction(
//                e -> tiffPanel.removeSelection(),
//                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
//                JComponent.WHEN_IN_FOCUSED_WINDOW);
        this.pack();
        statusPixelCoordinatesLabel.reserveCurrentSize();
        statusPixelCoordinatesLabel.setText(TiffViewer.DEFAULT_PIXEL_COORDINATES);

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

    public void setStatusPixelCoordinates(String status) {
        statusPixelCoordinatesLabel.setText(status);
    }

    public void setStatusPixelValue(String status) {
        statusPixelValueLabel.setText(status);
    }

    public void setStatusSelection(String status, boolean error) {
        final JLabel label = statusSelectionLabel;
        label.setForeground(error ? TiffExplorer.ERROR_COLOR : TiffExplorer.COMMON_COLOR);
        label.setText(status);
    }

        @Override
    public void dispose() {
        super.dispose();
        viewer.disposeResources();
        viewerPanel.disposeResources();
    }

    void resetImageInformation() {
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
        final double rescaleFactor = viewer.getRescaleFactor();
        final String rescaleTitle = rescaleFactor == 1.0 ? "" :
                String.format(Locale.US, ", scaled by %.3f", rescaleFactor);
        setTitle("TIFF Image #%d/%d (%dx%d, %d channel%s, %s%s bits/channel)  %s%s%s  [%s]".formatted(
                viewer.ifdIndex(), map.numberOfImagesUnchecked(),
                map.dimX(), map.dimY(),
                map.numberOfChannels(), map.numberOfChannels() == 1 ? "" : "s",
                map.sampleType().isSignedInteger() ? "signed " : "",
                bitDepth.isPresent() ? bitDepth.getAsInt() : Arrays.toString(map.bitsPerSample()),
                map.compressionOrNoneForMissing().orElse(TagCompression.NONE).prettyName(),
                zoomTitle,
                rescaleTitle,
                viewer.path().getFileName()));
        final TagPhotometric photometric = map.photometric().orElse(null);
        final boolean problematicPhotometric = photometric == null || !photometric.isSimplyRenderable();
        final boolean problematicPrecision = map.sampleType().isSignedInteger();
        if (problematicPhotometric) {
            noticeLabel.setText(
                    (photometric == null ? "Note: PhotometricInterpretation tag is not set" :
                            "Note: PhotometricInterpretation %s is not fully supported"
                            .formatted(photometric.prettyName()))
                            + "; colors may be incorrect");
        } else if (problematicPrecision) {
            noticeLabel.setText("Note: this image contains signed values (" + map.sampleType().prettyName() +
                    ") and may be rendered incorrectly");
        }
        noticePanel.setVisible(problematicPhotometric || problematicPrecision);
    }

    Rectangle getImageVisibleArea() {
        return getVisibleArea(viewerPanel.getZoom());
    }

    private Rectangle getVisibleArea(double zoom) {
        final JViewport viewport = viewerScrollPane.getViewport();
        final Point viewPosition = viewport.getViewPosition();
        final Dimension extentSize = viewport.getExtentSize();
        int x1 = (int) Math.floor(viewPosition.x / zoom);
        int y1 = (int) Math.floor(viewPosition.y / zoom);
        int x2 = (int) Math.ceil((viewPosition.x + extentSize.width) / zoom);
        int y2 = (int) Math.ceil((viewPosition.y + extentSize.height) / zoom);
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem saveImageAsTiffItem = new JMenuItem("Save image as TIFF...");
        saveImageAsTiffItem.setMnemonic(KeyEvent.VK_S);
        saveImageAsTiffItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveImageAsTiffItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseTiffFileToSaveImage(false);
            if (file != null) {
                try {
                    helper.showSaveEntireImageDialog(viewer, file);
                } catch (Exception ex) {
                    showErrorMessage(ex, "Error saving TIFF");
                }
            }
        });
        fileMenu.add(saveImageAsTiffItem);
        JMenuItem appendImageToTiffItem = new JMenuItem("Append image to TIFF...");
        appendImageToTiffItem.setMnemonic(KeyEvent.VK_D);
        appendImageToTiffItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        appendImageToTiffItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseTiffFileToAppendImage(false);
            if (file != null) {
                try {
                    helper.showAppendEntireImageDialog(viewer, file);
                } catch (Exception ex) {
                    showErrorMessage(ex, "Error appending TIFF");
                }
            }
        });
        fileMenu.add(appendImageToTiffItem);
        JMenuItem exportImageItem = new JMenuItem("Export image...");
        exportImageItem.setMnemonic(KeyEvent.VK_E);
        exportImageItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseFileToExportImage(viewer, false);
            if (file != null) {
                TinySwing.doLongOperation(this, () -> {
                    try {
                        helper.exportImageToFile(viewer, file, false);
                    } catch (Exception ex) {
                        // - including possible non-I/O exceptions like an empty file extension
                        showErrorMessage(ex, "Error exporting image");
                    }
                });
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
                    helper.showSaveSelectionDialog(viewer, file);
                } catch (Exception ex) {
                    showErrorMessage(ex, "Error saving the selected area");
                }
            }
        });
        fileMenu.add(saveSelectionAsTiffItem);
        JMenuItem appendSelectionToTiffItem = new JMenuItem("Append selection to TIFF...");
        appendSelectionToTiffItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        appendSelectionToTiffItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseTiffFileToAppendImage(true);
            if (file != null) {
                try {
                    helper.showAppendSelectionDialog(viewer, file);
                } catch (Exception ex) {
                    showErrorMessage(ex, "Error appending the selected area");
                }
            }
        });
        fileMenu.add(appendSelectionToTiffItem);
        JMenuItem exportSelectionItem = new JMenuItem("Export selection...");
        exportSelectionItem.addActionListener(e -> {
            final var helper = new TiffSaveImageHelper(this);
            Path file = helper.chooseFileToExportImage(viewer, true);
            if (file != null) {
                TinySwing.doLongOperation(this, () -> {
                    try {
                        helper.exportImageToFile(viewer, file, true);
                    } catch (Exception ex) {
                        // - including possible non-I/O exceptions like an empty file extension
                        showErrorMessage(ex, "Error exporting the selected area");
                    }
                });
            }
        });
        fileMenu.add(exportSelectionItem);
        fileMenu.addSeparator();
        // RandomAccessFile does not strictly lock the file: other processes, for example,
        // other instances of TiffExplorer may edit the same file
        JMenuItem reloadItem = new JMenuItem("Reload image");
        reloadItem.setMnemonic(KeyEvent.VK_R);
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
        selectAllItem.setMnemonic(KeyEvent.VK_A);
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
        removeSelectionItem.setMnemonic(KeyEvent.VK_D);
        removeSelectionItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        removeSelectionItem.addActionListener(e -> viewerPanel.removeSelection());
        editMenu.add(removeSelectionItem);
        editMenu.addSeparator();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> {
            TinySwing.doLongOperation(this, () -> {
                try {
                    final var helper = new TiffSaveImageHelper(this);
                    helper.copySelectedAreaToClipboard(viewer);
                    // Thread.currentThread().sleep(5000);
                } catch (Exception ex) {
                    // - including possible non-I/O exceptions
                    showErrorMessage(ex, "Error copying the image to the clipboard");
                }
            });
        });
        editMenu.add(copyItem);

        JMenu viewMenu = new JMenu("View");

        JRadioButtonMenuItem colorCorrectionDisabledItem = new JRadioButtonMenuItem("No color correction");
        JRadioButtonMenuItem colorCorrectionEnabledItem = new JRadioButtonMenuItem("Color correction (n/a)");
        JMenu colorCorrectionMenu = buildEnableDisableSubmenu(
                "Color correction",
                colorCorrectionDisabledItem,
                colorCorrectionEnabledItem,
                this::setColorCorrection);
        viewMenu.add(colorCorrectionMenu);

        JRadioButtonMenuItem rescaleDisabledItem = new JRadioButtonMenuItem("No samples correction");
        JRadioButtonMenuItem rescaleEnabledItem = new JRadioButtonMenuItem("Rescaling samples (n/a)");
        JMenu rescaleMenu = buildEnableDisableSubmenu(
                "Auto rescaling samples",
                rescaleDisabledItem,
                rescaleEnabledItem,
                this::setRescaleWhenIncreasingBitDepth);
        viewMenu.add(rescaleMenu);

        JMenuItem setRescaleFactorItem = new JMenuItem("Rescale with factor...");
        setRescaleFactorItem.setMnemonic(KeyEvent.VK_R);
        setRescaleFactorItem.addActionListener(e -> {
            TinySwing.doLongOperation(this, () -> {
                try {
                    viewer.findMaxVisibleValue();
                    viewer.showSetRescaleFactorDialog();
                } catch (Exception ex) {
                    // - including possible non-I/O exceptions like an empty file extension
                    showErrorMessage(ex, "Error while analysing the visible area");
                }
            });
        });
        viewMenu.add(setRescaleFactorItem);
        viewMenu.addSeparator();

        JCheckBoxMenuItem tileGridItem = new JCheckBoxMenuItem("Draw grid");
        tileGridItem.setSelected(false);
        tileGridItem.setMnemonic(KeyEvent.VK_G);
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
        zoomMenu.setMnemonic(KeyEvent.VK_Z);
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

        JMenu pixelValueMenu = new JMenu("Show pixel value as");
        ButtonGroup pixelFormatGroup = new ButtonGroup();
        final Map<UserPixelValueFormat, JRadioButtonMenuItem> pixelFormatItems = new LinkedHashMap<>();
        for (final UserPixelValueFormat format : UserPixelValueFormat.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(format.caption(false));
            if (format == UserPixelValueFormat.NONE) {
                item.setSelected(true);
            }
            item.addActionListener(e -> {
                viewer.setPixelValueFormat(format);
                viewer.resetSelectionStatus();
                viewer.resetPixelValueStatus();
            });
            pixelFormatGroup.add(item);
            pixelValueMenu.add(item);
            pixelFormatItems.put(format, item);
        }
        viewMenu.add(pixelValueMenu);
        viewMenu.addSeparator();

        JMenuItem showDecodingReportItem = new JMenuItem("Show decoding report");
        showDecodingReportItem.addActionListener(e -> {
            final TiffReadMap map = viewer.map();
            final var report = map.lastCodecReport();
            if (report != null) {
                JOptionPane.showMessageDialog(
                        this,
                        """
                                Additional information about decoding the last image %s
                                (in addition to the TIFF IFD structure, already shown
                                in the main TIFF Explorer window)
                                
                                %s
                                """.formatted(map.isTiled() ? "tile" : "strip", report),
                        "Decoding Report",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
        viewMenu.add(showDecodingReportItem);

        final MenuUpdater menuUpdater = new MenuUpdater(() -> {
            final TiffReadMap map = viewer.map();
            alignSelectionItem.setText(viewer.alignSelectionToTileGridCommand());
            alignSelectionItem.setEnabled(viewerPanel.isSelected());
            alignSelectionItem.setVisible(map.isTiled());
            removeSelectionItem.setEnabled(viewerPanel.isSelected());
            copyItem.setEnabled(viewerPanel.hasNonEmptySelection());
            exportSelectionItem.setEnabled(viewerPanel.hasNonEmptySelection());
            saveSelectionAsTiffItem.setEnabled(viewerPanel.hasNonEmptySelection());
            appendSelectionToTiffItem.setEnabled(viewerPanel.hasNonEmptySelection());

            final int rawBitDepth = map.tryEqualBitDepth().orElse(-1);
            final int actualBitDepth = map.sampleType().bitsPerSample();
            boolean rescaleApplicable = map.isRescaleWhenIncreasingBitDepthApplicable();
            boolean rescale = map.isRescaleWhenIncreasingBitDepth();
            updateEnableDisableSubmenu(rescaleMenu,
                    rescaleApplicable,
                    rescale,
                    rescaleDisabledItem,
                    rescaleEnabledItem,
                    "Auto rescaling samples",
                    "No rescaling (keep raw values)",
                    rescaleApplicable ?
                            "Rescaling " + precisionTitle(rawBitDepth) + " to " + precisionTitle(actualBitDepth) :
                            "Rescaling non-standard depths to 8-, 16- or 32-bit (e.g. 12-bit to 16-bit)",
                    viewMenu);
            updateEnableDisableSubmenu(
                    colorCorrectionMenu,
                    map.isColorCorrectionApplicable(),
                    map.isColorCorrection(),
                    colorCorrectionDisabledItem,
                    colorCorrectionEnabledItem,
                    "Color correction",
                    map.numberOfChannels() == 1 ?
                            "No correction (grayscale, 0 is black)" :
                            "No correction (unsupported color models will be displayed as RGB)",
                    map.numberOfChannels() == 1 ?
                            "Corrected (grayscale, 0 is white)" :
                            "Corrected (CMYK will be converted to RGB)",
                    viewMenu);
            setRescaleFactorItem.setEnabled(!map.isBinary());

            for (UserPixelValueFormat format : UserPixelValueFormat.values()) {
                JRadioButtonMenuItem menuItem = pixelFormatItems.get(format);
                menuItem.setEnabled(format.isSuitable(map.sampleType()));
                menuItem.setText(format.caption(map.sampleType().isSigned()));
            }
            tileGridItem.setText("Draw %s".formatted(map.isTiled() ? "tile grid" : "strip boundaries"));
            showDecodingReportItem.setEnabled(map.lastCodecReport() != null);
        });
        fileMenu.addMenuListener(menuUpdater);
        editMenu.addMenuListener(menuUpdater);
        viewMenu.addMenuListener(menuUpdater);

        fileMenu.setMnemonic(KeyEvent.VK_F);
        editMenu.setMnemonic(KeyEvent.VK_E);
        viewMenu.setMnemonic(KeyEvent.VK_V);
        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);
        return menuBar;
    }

    private static JMenu buildEnableDisableSubmenu(
            String title,
            JRadioButtonMenuItem disabledItem,
            JRadioButtonMenuItem enabledItem,
            Consumer<Boolean> action) {
        JMenu colorCorrectionMenu = new JMenu(title);
        ButtonGroup colorCorrectionGroup = new ButtonGroup();
        disabledItem.setEnabled(false);
        disabledItem.addActionListener(e -> action.accept(false));
        enabledItem.setEnabled(false);
        enabledItem.addActionListener(e -> action.accept(true));
        colorCorrectionGroup.add(disabledItem);
        colorCorrectionGroup.add(enabledItem);
        colorCorrectionMenu.add(disabledItem);
        colorCorrectionMenu.add(enabledItem);
        return colorCorrectionMenu;
    }

    private static void updateEnableDisableSubmenu(
            JMenu subMenu,
            boolean applicable,
            boolean enabled,
            JRadioButtonMenuItem disabledItem,
            JRadioButtonMenuItem enabledItem,
            String menuTitle,
            String disabledTitle,
            String enabledTitle,
            JMenu exampleMenu) {
        subMenu.setText(menuTitle + (applicable ? "  " : " (not applicable)  "));
        // - adding spaces to avoid Swing bug (probably https://bugs.openjdk.org/browse/JDK-8374506 )
        disabledItem.setText(disabledTitle);
        disabledItem.setEnabled(applicable);
        disabledItem.setSelected(!enabled);
        enabledItem.setText(enabledTitle);
        enabledItem.setEnabled(applicable);
        enabledItem.setSelected(enabled);
        final Color colorCorrectionForeground = applicable ?
                exampleMenu.getForeground() :
                UIManager.getColor("MenuItem.disabledForeground");
        if (colorCorrectionForeground != null) {
            subMenu.setForeground(new Color(colorCorrectionForeground.getRGB()));
            // - when possible, emulate the "disabled" menu state without actual disabling;
            // may not work without explicit new Color(...)
        }
    }

    private static String precisionTitle(int bitDepth) {
        return bitDepth == -1 ? "" : "%d-bit samples (0..%d)".formatted(bitDepth, (1L << bitDepth) - 1);
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
                resetImageInformation();
                viewer.setTileGridThickness(tileGridThickness);
            } catch (TooBigZoomException | IOException ex) {
                showErrorMessage(ex, "Cannot set zoom");
            }
            viewer.repaint();
            item.setSelected(true);
        });
        group.add(item);
        menu.add(item);
    }

    private void alignSelection() {
        final Rectangle selection = viewer.getImageSelection();
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

    private void setRescaleWhenIncreasingBitDepth(boolean rescaleWhenIncreasingBitDepth) {
        try {
            viewer.setRescaleWhenIncreasingBitDepth(rescaleWhenIncreasingBitDepth);
            viewer.repaint();
        } catch (IOException ex) {
            showErrorMessage(ex, "Error reloading image");
        }
    }

    private void setColorCorrection(boolean colorCorrection) {
        try {
            viewer.setColorCorrection(colorCorrection);
            viewer.repaint();
        } catch (IOException ex) {
            showErrorMessage(ex, "Error reloading image");
        }
    }

    private void showErrorMessage(Throwable e, String title) {
        TinySwing.showErrorMessage(this, e, title);
    }

    private static void fixMinimalSizes(JComponent component) {
        // - such solution does not work: fixing preferred size leads to "..." in JLabel
        Dimension preferredSize = component.getPreferredSize();
        component.setMinimumSize(preferredSize);
        component.setPreferredSize(preferredSize);
    }
}
