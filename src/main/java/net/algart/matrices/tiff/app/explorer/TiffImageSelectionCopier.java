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

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

class TiffImageSelectionCopier {
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
    private final TiffCopier copier;

    private volatile boolean copyingInProgress = false;
    private volatile boolean stopRequested = false;

    private JLabel progressLabel;
    private JTextField compressionField;

    public TiffImageSelectionCopier(TiffImageViewer viewer, JFrame frame) {
        this.viewer = Objects.requireNonNull(viewer);
        this.frame = Objects.requireNonNull(frame);
        this.copier = new TiffCopier().setInterruptionChecker(() -> stopRequested);
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

    // Not used in the current version: it is a long operation and should be performed via SwingWorker
    public void copySelectedImageToTiff(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "Null targetFile");
        Rectangle rectangle = viewer.getSelection();
        if (rectangle == null) {
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "Copying selected image to " + targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setSmartCorrection(true);
            writer.setFormatLike(viewer.reader());
            // - without this operator, direct copy will be impossible for LE format
            writer.create();
            copier.copyImage(writer, viewer.map(), rectangle.x, rectangle.y, rectangle.width, rectangle.height);
        }
    }

    public void showCopyToTiffDialog(Path targetFile) {
        final Rectangle selection = viewer.getSelection();
        if (selection == null || selection.width <= 0 || selection.height <= 0) {
            return;
        }

        final JDialog dialog = new JDialog(frame, "Copy selection to TIFF", true);
        dialog.setLayout(new BorderLayout(10, 10));

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
                viewer.map().streamName(), targetFile.getFileName()
        ));
        infoLabel.setFont(infoLabel.getFont().deriveFont((float) DEFAULT_FONT_SIZE));
        infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(infoLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        final JPanel compressionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        compressionPanel.add(new JLabel("Compression quality (for JPEG, from 0.0 to 1.0):"));
        compressionField = new JTextField(6);
        compressionPanel.add(compressionField);
        compressionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(compressionPanel);

        mainPanel.add(Box.createVerticalStrut(10));

        progressLabel = new JLabel("");
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        copier.setProgressUpdater(p -> SwingUtilities.invokeLater(() -> {
            progressLabel.setText("%d/%d tiles copied...".formatted(p.tileIndex() + 1, p.tileCount()));
        }));
        mainPanel.add(progressLabel);

        dialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        final JButton startButton = new JButton("Start");
        final JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(startButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        stopRequested = false;
        copyingInProgress = false;
        cancelButton.addActionListener(e -> {
            if (copyingInProgress) {
                stopRequested = true;
            } else {
                dialog.dispose();
            }
        });

        startButton.addActionListener(e -> {
            startButton.setEnabled(false);
            startButton.setVisible(false);
            cancelButton.setText("Cancel copying");
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
                    copier.copyImage(
                            writer, viewer.map(),
                            selection.x, selection.y, selection.width, selection.height);
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
                    cancelButton.setText("Close");
                    // dialog.dispose();
                }

            }.execute();
        });

        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
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
        final String text = compressionField.getText().trim();
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
        copier.setDirectCopy(!hasCompression);
    }


    private void showErrorMessage(Throwable e, String title) {
        TiffExplorer.showErrorMessage(frame, e, title);
    }
}
