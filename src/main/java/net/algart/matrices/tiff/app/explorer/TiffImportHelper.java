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
import net.algart.arrays.PArray;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffIO;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

class TiffImportHelper {
    private static final String PREF_LAST_IMPORT_DIR = "import.lastDirectory";
    private static final String PREF_LAST_IMPORT_NEW_TIFF_DIR = "import.lastNewTiffDirectory";

    private static final System.Logger LOG = System.getLogger(TiffImportHelper.class.getName());

    private static final FileFilter IMAGE_FILE_FILTER = new FileNameExtensionFilter(
            "Image files (*.png, *.jpg, *.jpeg, *.bmp, *.gif, *.tiff, *.tif, *.webp)",
            "png", "jpg", "jpeg", "bmp", "gif", "tiff", "tif", "webp"
    );
    private final JFrame frame;
    private final TiffExplorer explorer;

    private JDialog dialog;
    private JComboBox<UserByteOrder> byteOrderComboBox;
    private JCheckBox bigTiffCheckBox;
    private JCheckBox tiledCheckBox;
    private JTextField tileSizeXField;
    private JTextField tileSizeYField;
    private JComboBox<UserNumberOfChannels> numberOfChannelsComboBox;
    private JComboBox<String> compressionMethodComboBox;

    private UserByteOrder byteOrder = UserByteOrder.BIG_ENDIAN;
    private boolean bigTiff = false;
    private boolean tiled = false;
    // - unlike new blank image, tiles are usually not necessary while importing an image
    private int tileSizeX = TiffIFD.DEFAULT_TILE_SIZE;
    private int tileSizeY = TiffIFD.DEFAULT_TILE_SIZE;
    private TagCompression compression = TagCompression.DEFLATE;

    public TiffImportHelper(JTiffExplorerFrame frame) {
        this.frame = Objects.requireNonNull(frame);
        this.explorer = frame.explorer();
    }

    public Path chooseFileToImport() {
        JFileChooser chooser = TinySwing.newFileChooser();
        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_IMPORT_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.addChoosableFileFilter(IMAGE_FILE_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setDialogTitle("Select an image file to import");
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file != null) {
                TiffExplorer.PREFERENCES.put(PREF_LAST_IMPORT_DIR, file.getParent());
                return file.toPath();
            }
        }
        return null;
    }

    public Path chooseTiffFileToSave(boolean append) {
        JFileChooser chooser = TinySwing.newFileChooser();
        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_IMPORT_NEW_TIFF_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle(append ? "Existing TIFF file to append" : "New TIFF file");
        chooser.setSelectedFile(new File("imported.tiff"));
        chooser.addChoosableFileFilter(TiffExplorer.TIFF_FILTER);
        chooser.setFileFilter(TiffExplorer.TIFF_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = append ?
                TinySwing.chooseFileToOpen(frame, chooser) :
                TinySwing.chooseFileAndConfirmOverwrite(frame, chooser);
        if (file == null) {
            return null;
        }
        TiffExplorer.PREFERENCES.put(PREF_LAST_IMPORT_NEW_TIFF_DIR, file.getParent());
        return file.toPath();
    }

    public void showCustomizeTiffDialog(Path sourceFile, Path targetFile, boolean append) throws IOException {
        Objects.requireNonNull(sourceFile, "Null sourceFile");
        Objects.requireNonNull(targetFile, "Null targetFile");
        dialog = new JDialog(frame, true);
        // dialog.setMinimumSize(new Dimension(500, 20)); // not too good idea
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setTitle("Import image to TIFF");
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel headerLabel = TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                The selected image:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                will be %s TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                """.formatted(
                        sourceFile,
                        append ? "appended to an existing" : "written into a new",
                        targetFile.toAbsolutePath())));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(headerLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        JPanel gridPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 5, 4, 5);
        int row = 0;

        tiledCheckBox = new JCheckBox("Tiled TIFF image");
        tiledCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        tiledCheckBox.setSelected(tiled);
        tileSizeXField = new JTextField(String.valueOf(tileSizeX), 10);
        tileSizeYField = new JTextField(String.valueOf(tileSizeY), 10);
        updateTileSizesEnabled();
        tiledCheckBox.addActionListener(e -> updateTileSizesEnabled());

        TinySwing.addGridBugRowSingle(gridPanel, gbc, tiledCheckBox, row++);
        TinySwing.addGridBugRowLabelled(gridPanel, gbc, new JLabel("Tile width:"), tileSizeXField, row++);
        TinySwing.addGridBugRowLabelled(gridPanel, gbc, new JLabel("Tile height:"), tileSizeYField, row++);

        TinySwing.addGridBugRowCaption(gridPanel, gbc, "Content settings", true, row++);

//        numberOfChannelsComboBox = new JComboBox<>(UserNumberOfChannels.values());
//        numberOfChannelsComboBox.setSelectedItem(numberOfChannels);

//        sampleTypeComboBox = new JComboBox<>(Arrays.stream(TiffSampleType.values())
//                .map(TiffSampleType::prettyName)
//                .toArray(String[]::new));
//        sampleTypeComboBox.setMaximumRowCount(64);
//        sampleTypeComboBox.setSelectedItem(sampleType.prettyName());

//        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Channels:"), numberOfChannelsComboBox, row++);
//        addGridBugRowLabelled(gridPanel, gbc, new JLabel("Sample Type:"), sampleTypeComboBox, row++);
        // - probably we will add conversion in future

        compressionMethodComboBox = new JComboBox<>(TiffSaveImageHelper.makeCompressionNames());
        compressionMethodComboBox.setMaximumRowCount(64);
        compressionMethodComboBox.setSelectedItem(compression.prettyName());
        TinySwing.addGridBugRowLabelled(gridPanel, gbc, new JLabel("Compression method:"),
                compressionMethodComboBox, row++);

        if (!append) {
            TinySwing.addGridBugRowCaption(gridPanel, gbc, "TIFF file settings", true, row++);
            byteOrderComboBox = new JComboBox<>(UserByteOrder.values());
            byteOrderComboBox.setSelectedItem(byteOrder);
            TinySwing.addGridBugRowLabelled(gridPanel, gbc, new JLabel("Byte order:"), byteOrderComboBox, row++);

            bigTiffCheckBox = new JCheckBox("BigTIFF (necessary for large files >4 GB)");
            bigTiffCheckBox.setSelected(bigTiff);
            bigTiffCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            TinySwing.addGridBugRowSingle(gridPanel, gbc, bigTiffCheckBox, row++);
        }

        mainPanel.add(gridPanel);
        mainPanel.add(Box.createVerticalStrut(10));
        dialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        final JButton okButton = new JButton(append ? "Append" : "Create");
        final JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(okButton);

        cancelButton.addActionListener(event -> dialog.dispose());
        okButton.addActionListener(event -> {
            TinySwing.doLongOperation(dialog, () -> {
                try {
                    importToTiff(sourceFile, targetFile, append);
                } catch (Exception e) {
                    TinySwing.showErrorMessage(frame, e, "Error writing to TIFF");
                    return;
                }

                dialog.dispose();
                explorer.openFile(targetFile);
            });
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

    private void importToTiff(Path sourceFile, Path targetFile, boolean append) throws IOException {
        final List<? extends Matrix<? extends PArray>> image = TiffReader.readImage(sourceFile);
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            final boolean tiled = tiledCheckBox.isSelected();
            int tileSizeX = -1;
            int tileSizeY = -1;
            if (tiled) {
                tileSizeX = Integer.parseInt(tileSizeXField.getText().trim());
                tileSizeY = Integer.parseInt(tileSizeYField.getText().trim());
            }
            this.tiled = tiled;
            if (tiled) {
                this.tileSizeX = tileSizeX;
                this.tileSizeY = tileSizeY;
            }
            final String compressionName = TinySwing.selectedValue(compressionMethodComboBox);
            this.compression = TagCompression.fromPrettyName(compressionName).orElseThrow();
            if (!append) {
                this.bigTiff = bigTiffCheckBox.isSelected();
                this.byteOrder = TinySwing.selectedValue(byteOrderComboBox);
                writer.setBigTiff(bigTiff);
                writer.setByteOrder(byteOrder.byteOrder());
            }
            writer.create(append);
            final TiffIFD ifd = TiffIFD.newIFD(this.tiled);
            if (this.tiled) {
                ifd.putTileSizes(tileSizeX, tileSizeY);
            }
            ifd.putChannelsInformation(image);
            ifd.putCompression(compression);
            long t1 = System.nanoTime();
            final TiffWriteMap map = writer.newFixedMap(ifd, TiffIO.MapOption.without(TiffIO.MapOption.BUILD_GRID));
            long t2 = System.nanoTime();
            map.writeChannels(image);
            long t3 = System.nanoTime();
            LOG.log(System.Logger.Level.INFO, String.format(Locale.ROOT,
                    "Image written in %.3f ms: %.3f ms preparing map + %.3f ms writing to file",
                    (t3 - t1) * 1e-6, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6));
        }
    }
}
