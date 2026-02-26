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
import net.algart.matrices.tiff.TiffCopier;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

class TiffImageExport {
    private static final String PREF_LAST_EXPORT_SELECTION_DIR = "lastExportSelectionDirectory";
    private static final String PREF_LAST_COPY_SELECTION_TO_TIFF_DIR = "lastCopySelectionToTiffDirectory";
    private static final FileFilter ANY_IMAGE_FILTER = new FileNameExtensionFilter(
            "Image files (*.png, *.jpg, *.jpeg, *.bmp, *.gif, *.tif, *.tiff)",
            "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff");
    private static final FileFilter TIFF_FILTER = new FileNameExtensionFilter(
            "TIFF files (*.tif, *.tiff)",
            "tif", "tiff");
    private static final int DEFAULT_FONT_SIZE = 15;

    private static final System.Logger LOG = System.getLogger(TiffImageViewer.class.getName());

    private final TiffImageViewer viewer;
    private final JFrame frame;

    private volatile boolean copyingInProgress = false;
    private volatile boolean stopRequested = false;

    private TiffCopier copier;
    private JLabel progressLabel;
    private JCheckBox directMode;
    private JLabel compressionQualityLabel;
    private JTextField compressionQualityField;
    private JComboBox<String> compressionMethodBox;
    private JButton startCopyButton;
    private JButton cancelCopyButton;
    private JDialog copySettingsDialog;

    public TiffImageExport(TiffImageViewer viewer, JFrame frame) {
        this.viewer = Objects.requireNonNull(viewer);
        this.frame = Objects.requireNonNull(frame);
    }

    public Path chooseFileToExport() {
        JFileChooser chooser = new JFileChooser();

        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_EXPORT_SELECTION_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Export selected image as...");
        chooser.setSelectedFile(new File("selected.png"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(ANY_IMAGE_FILTER);
        chooser.setFileFilter(ANY_IMAGE_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = chooseFile(chooser);
        if (file == null) return null;
        TiffExplorer.PREFERENCES.put(PREF_LAST_EXPORT_SELECTION_DIR, file.getParent());
        return file.toPath();
    }

    public Path chooseTiffFileToCopy() {
        JFileChooser chooser = new JFileChooser();

        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_COPY_SELECTION_TO_TIFF_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Сopy selected image to a new TIFF file...");
        chooser.setSelectedFile(new File("selected.tiff"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(TIFF_FILTER);
        chooser.setFileFilter(TIFF_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = chooseFile(chooser);
        if (file == null) {
            return null;
        }
        TiffExplorer.PREFERENCES.put(PREF_LAST_COPY_SELECTION_TO_TIFF_DIR, file.getParent());
        return file.toPath();
    }

    public void exportSelectedImageToFile(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "Null targetFile");
        BufferedImage image = viewer.readSelectedImage();
        if (image != null) {
            LOG.log(System.Logger.Level.DEBUG, "Export selected image to " + targetFile + ": " + image);
            MatrixIO.writeBufferedImage(targetFile, image);
        }
    }

    public void showCopyToTiffDialog(Path targetFile) {
        final Rectangle selection = viewer.getSelection();
        if (selection == null || selection.width <= 0 || selection.height <= 0) {
            return;
        }

        final TiffReadMap map = viewer.map();
        final boolean tileAligned = TiffCopier.isTileAligned(map, selection.x, selection.y);
        TagCompression compression = map.compression().orElse(TagCompression.NONE);
        if (!compression.isWritingSupported()) {
            compression = TagCompression.NONE;
        }
        copySettingsDialog = new JDialog(frame, "Copy selection to TIFF", true);
        copySettingsDialog.setLayout(new BorderLayout(10, 10));
        copySettingsDialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final JLabel infoLabel = new JLabel("""
                <html>
                Selected fragment %dx%d of the source file<br>
                &nbsp;&nbsp;&nbsp;&nbsp;%s<br>
                will be copied to a new TIFF file<br>
                &nbsp;&nbsp;&nbsp;&nbsp;%s<br>
                <br>
                Note: aligning selection to tile grid speeds up copying if the compression level is not specified.<br>
                
                """.formatted(
                selection.width, selection.height,
                map.streamName(), targetFile.getFileName()
        ));
        infoLabel.setFont(infoLabel.getFont().deriveFont((float) DEFAULT_FONT_SIZE));
        infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(infoLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        directMode = new JCheckBox("Quick direct copying (\"as-is\")");
        directMode.setEnabled(tileAligned);
        directMode.setSelected(tileAligned);
        directMode.addActionListener(e -> applyDirectMode());
        mainPanel.add(directMode);
        mainPanel.add(Box.createVerticalStrut(10));

        final JPanel settingsPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        compressionQualityLabel = new JLabel(compressionQualityLabel(TagCompression.JPEG_2000));
        // - JPEG_2000 is the maximally long variant for calculating positions
        settingsPanel.add(compressionQualityLabel);
        compressionQualityField = new JTextField(6);
        settingsPanel.add(compressionQualityField);
        settingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsPanel.add(new JLabel("Compression method:"));
        compressionMethodBox = new JComboBox<>(makeCompressionNames(compression));
        compressionMethodBox.setSelectedItem(compression.prettyName());
        compressionMethodBox.addActionListener(e -> correctCompressionQualityLabel());
        settingsPanel.add(compressionMethodBox);

        settingsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, settingsPanel.getPreferredSize().height));

        mainPanel.add(settingsPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        progressLabel = new JLabel("999/999 tiles copied...");
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(progressLabel);

        copySettingsDialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startCopyButton = new JButton("Start");
        cancelCopyButton = new JButton("Cancel");
        buttonPanel.add(startCopyButton);
        buttonPanel.add(cancelCopyButton);
        copySettingsDialog.add(buttonPanel, BorderLayout.SOUTH);
        copySettingsDialog.getRootPane().setDefaultButton(startCopyButton);
        cancelCopyButton.addActionListener(e -> cancelCopy());
        startCopyButton.addActionListener(e -> startCopy(targetFile, selection));

        stopRequested = false;
        copyingInProgress = false;
        copySettingsDialog.pack();
        TiffExplorer.addCloseOnEscape(copySettingsDialog);
        progressLabel.setText("");
        correctCompressionQualityLabel();
        applyDirectMode();
        copySettingsDialog.setLocationRelativeTo(frame);
        copySettingsDialog.setVisible(true);
    }

    private void startCopy(Path targetFile, Rectangle selection) {
        startCopyButton.setEnabled(false);
        startCopyButton.setVisible(false);
        cancelCopyButton.setText("Cancel copying");
        copyingInProgress = true;
        new SwingWorker<TiffWriter, Void>() {
            @Override
            protected TiffWriter doInBackground() throws Exception {
                TiffWriter writer = new TiffWriter(targetFile);
                writer.setSmartCorrection(true);
                writer.setFormatLike(viewer.reader());
                customizeCopying(writer);
                // - without this operator, direct copy will be impossible for LE format
                // if (true) throw new IOException("Test exception");
                writer.create();
                copier.copyImage(writer, viewer.map(), selection.x, selection.y, selection.width, selection.height);
                return writer;
            }

            @Override
            protected void done() {
                TiffWriter writer = null;
                try {
                    writer = get();
                } catch (InterruptedException | ExecutionException e) {
                    showErrorMessage(e, "Error copying TIFF");
                } finally {
                    if (writer != null) {
                        try {
                            writer.close();
                        } catch (IOException ex) {
                            showErrorMessage(ex, "Error closing TIFF writer");
                        }
                    }
                }
                copyingInProgress = false;
                copySettingsDialog.getRootPane().setDefaultButton(cancelCopyButton);
                cancelCopyButton.setText("Close");
                // copySettingsDialog.dispose();
            }

        }.execute();
    }

    private void cancelCopy() {
        if (copyingInProgress) {
            stopRequested = true;
        } else {
            copySettingsDialog.dispose();
        }
    }

    private File chooseFile(JFileChooser chooser) {
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
        return file;
    }

    private void customizeCopying(TiffWriter writer) {
        this.copier = new TiffCopier();
        copier.setInterruptionChecker(() -> stopRequested);
        copier.setProgressUpdater(p -> SwingUtilities.invokeLater(() -> {
            progressLabel.setText("%d/%d tiles copied (%s)%s".formatted(
                    p.tileIndex() + 1, p.tileCount(),
                    p.copier().actuallyDirectCopy() ? "direct" : "repacking",
                    p.isLastTileCopied() ? "" : "..."));
        }));
        copier.setDirectCopy(directMode.isSelected());
        final String text = compressionQualityField.getText().trim();
        boolean hasCompression = !text.isEmpty();
        if (hasCompression) {
            double compressionQuality;
            try {
                compressionQuality = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid compression quality: " + text);
            }
            writer.setCompressionQuality(compressionQuality);
        }
        if (!copier.isDirectCopy()) {
            TagCompression compression = getSelectedCompression();
            copier.setCompression(compression);
        }
    }

    private TagCompression getSelectedCompression() {
        final int selectedIndex = compressionMethodBox.getSelectedIndex();
        final String selected = compressionMethodBox.getItemAt(selectedIndex);
        return TagCompression.fromPrettyName(selected).orElseThrow();
    }

    private void correctCompressionQualityLabel() {
        compressionQualityLabel.setText(compressionQualityLabel(getSelectedCompression()));
    }

    private void applyDirectMode() {
        final boolean directCopy = directMode.isSelected();
        compressionQualityField.setEnabled(!directCopy);
        compressionMethodBox.setEnabled(!directCopy);
    }

    private static String compressionQualityLabel(TagCompression compression) {
        return "Compression quality" +
                (compression.isStandardJpeg() ? " (from 0 to 1)" :
                        compression.isJpeg2000() ? " (from ~0.5 to ~20 or higher)" : "")
                + ":";
    }

    private void showErrorMessage(Throwable e, String title) {
        TiffExplorer.showErrorMessage(frame, e, title);
    }

    private static String[] makeCompressionNames(TagCompression sourceCompression) {
        List<String> list = new ArrayList<>();
        for (TagCompression tagCompression : TagCompression.values()) {
            if (tagCompression.isOldFormat() && !sourceCompression.isOldFormat()) {
                // - don't show old formats excepting the case if the current compression is old
                continue;
            }
            if (tagCompression.isWritingSupported()) {
                list.add(tagCompression.prettyName());
            }
        }
        return list.toArray(new String[0]);
    }
}
