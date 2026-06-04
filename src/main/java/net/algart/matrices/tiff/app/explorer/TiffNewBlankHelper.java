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

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

class TiffNewBlankHelper {
    private static final String PREF_LAST_NEW_TIFF_DIR = "viewer.copier.lastNewTiffDirectory";

    private final JFrame frame;
    private final TiffExplorer explorer;

    private JDialog dialog;
    private JComboBox<UserByteOrder> byteOrderComboBox;
    private JCheckBox bigTiffCheckBox;
    private JTextField dimXField;
    private JTextField dimYField;
    private JCheckBox tiledCheckBox;
    private JTextField tileSizeXField;
    private JTextField tileSizeYField;
    private JComboBox<UserNumberOfChannels> numberOfChannelsComboBox;
    private JComboBox<TiffSampleType> sampleTypeComboBox;
    private JComboBox<String> compressionMethodComboBox;
    private JButton colorButton;
    private JTextField colorHexField;
    private JPanel colorPreviewPanel;
    private JCheckBox patternCheckBox;

    private UserByteOrder byteOrder = UserByteOrder.BIG_ENDIAN;
    private boolean bigTiff = false;
    private long dimX = 1024;
    private long dimY = 1024;
    private boolean tiled = true;
    private int tileSizeX = TiffIFD.DEFAULT_TILE_SIZE;
    private int tileSizeY = TiffIFD.DEFAULT_TILE_SIZE;
    private UserNumberOfChannels numberOfChannels = UserNumberOfChannels.RGB;
    private TiffSampleType sampleType = TiffSampleType.UINT8;
    private TagCompression compression = TagCompression.DEFLATE;
    private Color selectedColor = new Color(220, 230, 242);
    private boolean pattern = false;

    public TiffNewBlankHelper(JTiffExplorerFrame frame) {
        this.frame = Objects.requireNonNull(frame);
        this.explorer = frame.explorer();
    }

    public Path chooseTiffFileToCreate() {
        JFileChooser chooser = TinySwing.newFileChooser();
        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_NEW_TIFF_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Select new TIFF file name");
        chooser.setSelectedFile(new File("blank.tiff"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(TiffExplorer.TIFF_FILTER);
        chooser.setFileFilter(TiffExplorer.TIFF_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = TinySwing.chooseFileAndConfirmOverwrite(frame, chooser);
        if (file == null) {
            return null;
        }
        TiffExplorer.PREFERENCES.put(PREF_LAST_NEW_TIFF_DIR, file.getParent());
        return file.toPath();
    }

    public void showNewBlankTiffDialog(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "Null targetFile");
        dialog = new JDialog(frame, true);
        // dialog.setMinimumSize(new Dimension(500, 20)); // not too good idea
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setTitle("Create new blank TIFF");
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel headerLabel = TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                The new blank TIFF file will be created:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                """.formatted(targetFile.toAbsolutePath())));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(headerLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        JPanel gridPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 5, 4, 5);
        int row = 0;
        addGridBugRowCaption(gridPanel, gbc, "Dimensions", false, row++);
        dimXField = new JTextField(String.valueOf(dimX), 10);
        dimYField = new JTextField(String.valueOf(dimY), 10);
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Width (pixels):"), dimXField, row++);
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Height (pixels):"), dimYField, row++);

        tiledCheckBox = new JCheckBox("Tiled TIFF image");
        tiledCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        tiledCheckBox.setSelected(tiled);
        tileSizeXField = new JTextField(String.valueOf(tileSizeX), 10);
        tileSizeYField = new JTextField(String.valueOf(tileSizeY), 10);
        updateTileSizesEnabled();
        tiledCheckBox.addActionListener(e -> updateTileSizesEnabled());

        addGridBugRowSingle(gridPanel, gbc, tiledCheckBox, row++);
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Tile width:"), tileSizeXField, row++);
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Tile height:"), tileSizeYField, row++);

        addGridBugRowCaption(gridPanel, gbc, "Content settings", true, row++);
        numberOfChannelsComboBox = new JComboBox<>(UserNumberOfChannels.values());
        numberOfChannelsComboBox.setSelectedItem(numberOfChannels);
        sampleTypeComboBox = new JComboBox<>(TiffSampleType.values());
        sampleTypeComboBox.setMaximumRowCount(64);
        sampleTypeComboBox.setSelectedItem(sampleType);

        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Channels:"), numberOfChannelsComboBox, row++);
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Sample Type:"), sampleTypeComboBox, row++);
        compressionMethodComboBox = new JComboBox<>(TiffSaveImageHelper.makeCompressionNames());
        compressionMethodComboBox.setMaximumRowCount(64);
        compressionMethodComboBox.setSelectedItem(compression.prettyName());
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Compression method:"),
                compressionMethodComboBox, row++);

        JPanel colorChooserPanel = new JPanel(new BorderLayout(5, 0));
        colorHexField = new JTextField("XXXXXX", 7);
        updateHexFromColor();
        colorPreviewPanel = new JPanel();
        colorPreviewPanel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        colorPreviewPanel.setBackground(selectedColor);
        colorPreviewPanel.setPreferredSize(new Dimension(30, 20));
        colorButton = new JButton("...");
        colorButton.setMargin(new Insets(2, 4, 2, 4));
        colorChooserPanel.add(colorHexField, BorderLayout.CENTER);
        colorChooserPanel.add(colorPreviewPanel, BorderLayout.WEST);
        colorChooserPanel.add(colorButton, BorderLayout.EAST);
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Fill color (hex):"), colorChooserPanel, row++);

        colorHexField.addActionListener(e -> updateColorFromHex());
        colorHexField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                updateColorFromHex();
            }
        });
        colorButton.addActionListener(e -> selectColor());

        patternCheckBox = new JCheckBox("Repeat some figure in each tile");
        patternCheckBox.setSelected(pattern);
        patternCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        addGridBugRowSingle(gridPanel, gbc, patternCheckBox, row++);

        addGridBugRowCaption(gridPanel, gbc, "TIFF file settings", true, row++);
        byteOrderComboBox = new JComboBox<>(UserByteOrder.values());
        byteOrderComboBox.setSelectedItem(byteOrder);
        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Byte order:"), byteOrderComboBox, row++);

        bigTiffCheckBox = new JCheckBox("Big-TIFF (necessary for large files >4 GB)");
        bigTiffCheckBox.setSelected(bigTiff);
        bigTiffCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        addGridBugRowSingle(gridPanel, gbc, bigTiffCheckBox, row++);

        mainPanel.add(gridPanel);
        mainPanel.add(Box.createVerticalStrut(10));
        dialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        final JButton okButton = new JButton("Create");
        final JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(okButton);

        cancelButton.addActionListener(event -> dialog.dispose());
        okButton.addActionListener(event -> {
            try {
                createNewTiff(targetFile);
            } catch (Exception e) {
                TinySwing.showErrorMessage(frame, e, "Error creating new TIFF");
                return;
            }
            dialog.dispose();
            explorer.loadTiff(targetFile);
        });

        dialog.pack();
        TinySwing.addCloseOnEscape(dialog);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void updateTileSizesEnabled() {
        tileSizeXField.setEnabled(tiledCheckBox.isSelected());
        tileSizeYField.setEnabled(tiledCheckBox.isSelected());
    }

    private static void addGridBugRowCaption(
            JPanel panel,
            GridBagConstraints constraints,
            String caption,
            boolean verticalGap,
            int row) {
        JLabel label = new JLabel(caption);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setEnabled(false);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        Box box = Box.createVerticalBox();
        box.add(Box.createVerticalStrut(verticalGap ? 15 : 0));
        box.add(label);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        addGridBugRowSingle(panel, constraints, box, row);
    }

    private static void addGridBugRowSingle(
            JPanel panel,
            GridBagConstraints constraints,
            JComponent component,
            int row) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        panel.add(component, constraints);
        constraints.gridwidth = 1;
    }

    private static void addGridBugRowLabelled(
            JPanel panel,
            GridBagConstraints constraints,
            JLabel label,
            JComponent component,
            int row) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0.0;
        panel.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        panel.add(component, constraints);
    }

    private void selectColorSimple() {
        Color newColor = JColorChooser.showDialog(dialog, "Select Fill Color", selectedColor);
        if (newColor != null) {
            selectedColor = newColor;
            updateHexFromColor();
            colorPreviewPanel.setBackground(selectedColor);
        }
    }

    private void selectColor() {
        JColorChooser chooser = new JColorChooser(selectedColor);
        chooser.setPreviewPanel(new JPanel());

        for (var ccPanel : chooser.getChooserPanels()) {
            ccPanel.setColorTransparencySelectionEnabled(false);
        }

        JDialog dialog = JColorChooser.createDialog(
                this.dialog,
                "Select Fill Color",
                true,
                chooser,
                okEvent -> {
                    Color newColor = chooser.getColor();
                    colorPreviewPanel.setBackground(newColor);
                    selectedColor = newColor;
                    updateHexFromColor();
                },
                cancelEvent -> {
                }
        );
        dialog.setVisible(true);
    }

    private void updateColorFromHex() {
        String text = colorHexField.getText().trim();
        try {
            selectedColor = Color.decode(text);
            colorPreviewPanel.setBackground(selectedColor);
        } catch (NumberFormatException e) {
            TinySwing.showErrorMessage(frame, e, "Invalid color: " + text);
            updateHexFromColor();
        }
    }

    private void updateHexFromColor() {
        colorHexField.setText(String.format("#%06X", 0xFFFFFF & selectedColor.getRGB()));
    }

    private void createNewTiff(Path targetFile) throws IOException {
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            final boolean tiled = tiledCheckBox.isSelected();
            final long dimX = Long.parseLong(dimXField.getText().trim());
            final long dimY = Long.parseLong(dimYField.getText().trim());
            int tileSizeX = -1;
            int tileSizeY = -1;
            if (tiled) {
                tileSizeX = Integer.parseInt(tileSizeXField.getText().trim());
                tileSizeY = Integer.parseInt(tileSizeYField.getText().trim());
            }
            this.tiled = tiled;
            this.dimX = dimX;
            this.dimY = dimY;
            if (tiled) {
                this.tileSizeX = tileSizeX;
                this.tileSizeY = tileSizeY;
            }
            this.bigTiff = bigTiffCheckBox.isSelected();
            this.byteOrder = TinySwing.selectedValue(byteOrderComboBox);
            this.numberOfChannels = TinySwing.selectedValue(numberOfChannelsComboBox);
            this.sampleType = TinySwing.selectedValue(sampleTypeComboBox);
            final String compressionName = TinySwing.selectedValue(compressionMethodComboBox);
            this.compression = TagCompression.fromPrettyName(compressionName).orElseThrow();
            this.pattern = patternCheckBox.isSelected();
            writer.setBigTiff(bigTiff);
            writer.setByteOrder(byteOrder.byteOrder());
            writer.create();
            final TiffIFD ifd = TiffIFD.newIFD(this.tiled);
            ifd.putImageDimensions(dimX, dimY);
            if (this.tiled) {
                ifd.putTileSizes(tileSizeX, tileSizeY);
            }
            ifd.putPixelInformation(numberOfChannels.numberOfChannels(), sampleType);
            ifd.putCompression(compression);
            final TiffWriteMap map = writer.newFixedMap(ifd);
            if (pattern) {
                map.writeBlankRepeatingTile(m -> makePatternSamples(m, map, selectedColor));
            } else {
                map.writeBlank(selectedColor);
            }
        }
    }

    private static void makePatternSamples(Matrix<UpdatablePArray> matrix, TiffMap map, Color color) {
        new PatternImageBuilder(matrix, map,  color).build();
    }

    private static class PatternImageBuilder {
        private final TiffMap map;
        private final double[] filler;
        private final Matrix<UpdatablePArray> result;
        private final int numberOfChannels;

        PatternImageBuilder(Matrix<UpdatablePArray> result, TiffMap map, Color color) {
            Objects.requireNonNull(result);
            Objects.requireNonNull(map);
            Objects.requireNonNull(color);
            this.map = map;
            this.result = result;
            this.numberOfChannels = result.dim32(2);
            double[] channelValues = map.colorToChannelValues(color, false);
            this.filler = Arrays.copyOf(channelValues, numberOfChannels);
            if (numberOfChannels > channelValues.length) {
                Arrays.fill(filler, channelValues.length, numberOfChannels, 1.0);
            }
        }

        void build() {
            result.array().setData(0, buildData());
        }

        private Object buildData() {
            int dimX = result.dimX32();
            int dimY = result.dimY32();
            int halfX = dimX / 2;
            int halfY = dimY / 2;
            // - note: while using this class, dimX and dimY are 16*k,
            // so we are able not to worry about odd values
            double mX = Math.sqrt(0.5) / halfX;
            double mY = Math.sqrt(0.5) / halfY;
            return switch (map.sampleType()) {
                case BIT -> {
                    boolean[] channels = new boolean[dimX * dimY * numberOfChannels];
                    for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                        double filler = this.filler[c];
                        for (int y = 0; y < dimY; y++) {
                            double dySqr = sqr((y - halfY) * mY);
                            for (int x = 0; x < dimX; x++, disp++) {
                                channels[disp] = value(filler, (x - halfX) * mX, dySqr) > 0.5;
                            }
                        }
                    }
                    yield channels;
                }
                case UINT8, INT8 -> {
                    byte[] channels = new byte[dimX * dimY * numberOfChannels];
                    for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                        double filler = this.filler[c];
                        for (int y = 0; y < dimY; y++) {
                            double dySqr = sqr((y - halfY) * mY);
                            for (int x = 0; x < dimX; x++, disp++) {
                                channels[disp] = (byte) (value(filler, (x - halfX) * mX, dySqr) * 255.0);
                            }
                        }
                    }
                    yield channels;
                }
                case UINT16, INT16 -> {
                    short[] channels = new short[dimX * dimY * numberOfChannels];
                    for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                        double filler = this.filler[c];
                        for (int y = 0; y < dimY; y++) {
                            double dySqr = sqr((y - halfY) * mY);
                            for (int x = 0; x < dimX; x++, disp++) {
                                channels[disp] = (short) (value(filler, (x - halfX) * mX, dySqr) * 65535.0);
                            }
                        }
                    }
                    yield channels;
                }
                case INT32, UINT32 -> {
                    int[] channels = new int[dimX * dimY * numberOfChannels];
                    for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                        double filler = this.filler[c];
                        for (int y = 0; y < dimY; y++) {
                            double dySqr = sqr((y - halfY) * mY);
                            for (int x = 0; x < dimX; x++, disp++) {
                                channels[disp] = (int) (long) (value(filler, (x - halfX) * mX, dySqr) * 0xFFFFFFFFL);
                            }
                        }
                    }
                    yield channels;
                }
                case FLOAT -> {
                    float[] channels = new float[dimX * dimY * numberOfChannels];
                    for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                        double filler = this.filler[c];
                        for (int y = 0; y < dimY; y++) {
                            double dySqr = sqr((y - halfY) * mY);
                            for (int x = 0; x < dimX; x++, disp++) {
                                channels[disp] = (float) value(filler, (x - halfX) * mX, dySqr);
                            }
                        }
                    }
                    yield channels;
                }
                case DOUBLE -> {
                    double[] channels = new double[dimX * dimY * numberOfChannels];
                    for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                        double filler = this.filler[c];
                        for (int y = 0; y < dimY; y++) {
                            double dySqr = sqr((y - halfY) * mY);
                            for (int x = 0; x < dimX; x++, disp++) {
                                channels[disp] = value(filler, (x - halfX) * mX, dySqr);
                            }
                        }
                    }
                    yield channels;
                }
            };
        }

        private double value(double filler, double dx, double dySqr) {
            return filler + (0.9 - filler) * (dx * dx + dySqr);
            // - at the tile corners we will have 0.9: almost white;
            // it is better than white when the user selects white color to fill (we still see a subtle pattern)
        }

        private static double sqr(double v) {
            return v * v;
        }
    }
}
