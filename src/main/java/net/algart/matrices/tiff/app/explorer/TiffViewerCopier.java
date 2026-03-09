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

class TiffViewerCopier {
    private static final int MAX_SINGLE_IMAGE_SIZE_IN_PIXELS = 25 * 1024 * 1024;
    // - 25 megapixels: even for RGBA with float precision, it is only 4*4*25 MB = 400 MB < 2^31 bytes
    private static final String PREF_LAST_EXPORT__DIR = "lastExportDirectory";
    private static final String PREF_LAST_SAVE_AS_TIFF_DIR = "lastSaveAsTiffDirectory";
    private static final FileFilter ANY_IMAGE_FILTER = new FileNameExtensionFilter(
            "Image files (*.png, *.jpg, *.jpeg, *.bmp, *.gif, *.tif, *.tiff)",
            "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff");
    private static final FileFilter TIFF_FILTER = new FileNameExtensionFilter(
            "TIFF files (*.tif, *.tiff)",
            "tif", "tiff");
    private static final int DEFAULT_FONT_SIZE = 14;

    private static final System.Logger LOG = System.getLogger(TiffViewer.class.getName());

    private final TiffViewer viewer;
    private final JFrame frame;

    private volatile boolean copyingInProgress = false;
    private volatile boolean stopRequested = false;

    private JLabel progressLabel;
    private JCheckBox directMode;
    private JLabel compressionQualityLabel;
    private JTextField compressionQualityField;
    private JComboBox<String> compressionMethodBox;
    private JButton startCopyButton;
    private JButton cancelCopyButton;
    private JDialog copySettingsDialog;

    public TiffViewerCopier(JTiffViewerFrame frame) {
        this.frame = Objects.requireNonNull(frame);
        this.viewer = frame.viewer();
    }

    public Path chooseFileToExport(boolean processSelection) {
        final String whatToExport = processSelection ? "the selected area" : "the TIFF image";
        if (!confirmImageSize(
                "export " + whatToExport + " to another file format",
                processSelection ? "Save selection as TIFF" : "Save image as TIFF",
                processSelection)) {
            return null;
        }
        JFileChooser chooser = new JFileChooser();
        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_EXPORT__DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Export " + whatToExport + " as...");
        chooser.setSelectedFile(new File(processSelection ? "selected.png" : "image.png"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(ANY_IMAGE_FILTER);
        chooser.setFileFilter(ANY_IMAGE_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = chooseFile(chooser);
        if (file == null) return null;
        TiffExplorer.PREFERENCES.put(PREF_LAST_EXPORT__DIR, file.getParent());
        return file.toPath();
    }

    public Path chooseTiffFileToSave(boolean processSelection) {
        final String whatToSave = processSelection ? "the selected area" : "the TIFF image";
        JFileChooser chooser = new JFileChooser();
        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_SAVE_AS_TIFF_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Save " + whatToSave + " as a new TIFF file...");
        chooser.setSelectedFile(new File(processSelection ? "selected.tiff" : "image.tiff"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(TIFF_FILTER);
        chooser.setFileFilter(TIFF_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = chooseFile(chooser);
        if (file == null) {
            return null;
        }
        TiffExplorer.PREFERENCES.put(PREF_LAST_SAVE_AS_TIFF_DIR, file.getParent());
        return file.toPath();
    }

    public void copySelectedAreaToClipboard() throws IOException {
        if (!confirmImageSize(
                "copy the selected area to the clipboard",
                "Save selection as TIFF",
                true)) {
            return;
        }
        BufferedImage image = viewer.readSelectedImage();
        if (image != null) {
            LOG.log(System.Logger.Level.DEBUG, "Copied image to clipboard: " + image);
            ClipboardTools.copyImageToClipboard(image);
        }
    }

    public void exportImageToFile(Path targetFile, boolean processSelection) throws IOException {
        Objects.requireNonNull(targetFile, "Null targetFile");
        BufferedImage image = processSelection ? viewer.readSelectedImage() : viewer.readEntireImage();
        if (image != null) {
            LOG.log(System.Logger.Level.DEBUG, "Export %s to %s: %s".formatted(
                    processSelection ? "the selected area" : "TIFF image", targetFile, image));
            MatrixIO.writeBufferedImage(targetFile, image);
        }
    }

    public void showCopyToTiffDialog(Path targetFile, boolean processSelection) {
        final boolean tiled = viewer.map().isTiled();
        final int sizeX;
        final int sizeY;
        final Rectangle selection;
        final boolean tileAligned;
        if (processSelection) {
            selection = viewer.getSelection();
            if (selection == null || selection.width <= 0 || selection.height <= 0) {
                return;
            }
            sizeX = selection.width;
            sizeY = selection.height;
            tileAligned = tiled && TiffCopier.isTileAligned(viewer.map(), selection.x, selection.y);
        } else {
            selection = null;
            final TiffReadMap map = viewer.map();
            sizeX = map.dimX();
            sizeY = map.dimY();
            tileAligned = true;
        }
        final String whatToSave = processSelection ? "the selected area" : "the TIFF image";

        final TiffReadMap map = viewer.map();
        TagCompression compression = map.compression().orElse(TagCompression.NONE);
        if (!compression.isWritingSupported()) {
            compression = TagCompression.NONE;
        }
        copySettingsDialog = new JDialog(frame);
        copySettingsDialog.setTitle("Save " + whatToSave + " as a new TIFF file...");
        copySettingsDialog.setLayout(new BorderLayout(10, 10));
        copySettingsDialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final String whatToCopy = selection == null ?
                "The TIFF image #%d (%d\u00D7%d)".formatted(viewer.ifdIndex(), sizeX, sizeY) :
                "The selected area %d\u00D7%d (top-left at %d,%d) of the TIFF image #%d"
                        .formatted(sizeX, sizeY, selection.x, selection.y, viewer.ifdIndex());
        final JLabel infoLabel = new JLabel("""
                <html>
                %s from the file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br><br>
                will be copied to a new TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>&nbsp;
                """.formatted(
                whatToCopy,
                map.streamName(), targetFile.toAbsolutePath()
        ));
//        infoLabel.setFont(infoLabel.getFont().deriveFont((float) DEFAULT_FONT_SIZE));
        infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(infoLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        directMode = new JCheckBox("Quick direct copying (\"as-is\")");
        directMode.setEnabled(tileAligned);
        directMode.setSelected(tileAligned);
        directMode.addActionListener(e -> applyDirectMode());
        mainPanel.add(directMode);
        final JLabel directCommentLabel;
        if (selection != null) {
            final String directComment = !tiled ? """
                    Quick direct copying is unavailable: this image is stripped (not tiled).<br>
                    Direct copying is designed for copying large tiled images (such as SVS pyramids).
                    """
                    : tileAligned ? """
                    Quick copying is available: selection corner (%d,%d) is aligned to the tile grid.
                    """.formatted(selection.x, selection.y)
                    : """
                    Quick copying is unavailable for unaligned selection (started at %d,%d).<br>
                    You can use<br>
                    &nbsp;&nbsp;&nbsp;&nbsp;Edit \u25B8 %s  (Ctrl+Alt+A)<br>
                    to enable direct copying.
                    """.formatted(
                    selection.x, selection.y,
                    viewer.alignSelectionToTileGridCommand());
            directCommentLabel = new JLabel("<html>" + directComment);
            directCommentLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            directCommentLabel.setEnabled(false);
            // - gray color: this is a comment, not an important element
            mainPanel.add(directCommentLabel);
        }
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
        startCopyButton = new JButton();
        cancelCopyButton = new JButton();
        initializeButtons();
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

    private void initializeButtons() {
        startCopyButton.setText("Start");
        cancelCopyButton.setText("Cancel");
        startCopyButton.setEnabled(true);
        startCopyButton.setVisible(true);
    }

    private void startCopy(Path targetFile, Rectangle r) {
        final TiffCopier copier = buildCopier();
        final Double compressionQuality = getCompressionQuality();
        stopRequested = false;
        startCopyButton.setEnabled(false);
        startCopyButton.setVisible(false);
        cancelCopyButton.setText("Cancel copying");
        copyingInProgress = true;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (TiffWriter writer = new TiffWriter(targetFile)) {
                    writer.setSmartCorrection(true);
                    writer.setFormatLike(viewer.reader());
                    // - without this operator, direct copy will be impossible for LE format
                    // if (true) throw new IOException("Test exception");
                    writer.setCompressionQuality(compressionQuality);
                    writer.create();
                    if (r == null) {
                        copier.copyImage(writer, viewer.map());
                    } else {
                        copier.copyImage(writer, viewer.map(), r.x, r.y, r.width, r.height);
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException | ExecutionException e) {
                    showErrorMessage(e, "Error copying TIFF");
                }
                copyingInProgress = false;
                copySettingsDialog.getRootPane().setDefaultButton(cancelCopyButton);
                if (copier.isCancalled()) {
                    initializeButtons();
                } else {
                    cancelCopyButton.setText("Close");
                }
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

    private boolean confirmImageSize(String actionName, String recommendedAction, boolean processSelection) {
        final int sizeX;
        final int sizeY;
        if (processSelection) {
            final Rectangle selection = viewer.getSelection();
            if (selection == null || selection.width <= 0 || selection.height <= 0) {
                return false;
            }
            sizeX = selection.width;
            sizeY = selection.height;
        } else {
            final TiffReadMap map = viewer.map();
            sizeX = map.dimX();
            sizeY = map.dimY();
        }
        if ((long) sizeX * (long) sizeY > MAX_SINGLE_IMAGE_SIZE_IN_PIXELS) {
            int choice = JOptionPane.showConfirmDialog(
                    frame, """
                            You are trying to %s: %d×%d pixels.
                            
                            This operation may require a large amount of memory and can fail \
                            due to Java memory limits.
                            Instead, you can use "%s", which works reliably with images of any size.
                            
                            Do you want to continue?
                            
                            """.formatted(actionName, sizeX, sizeY, recommendedAction),
                    "Large Image Warning",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            return choice == JOptionPane.YES_OPTION;
        }
        return true;
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

    private TiffCopier buildCopier() {
        TiffCopier copier = new TiffCopier();
        copier.setInterruptionChecker(() -> stopRequested);
        copier.setProgressUpdater(p -> SwingUtilities.invokeLater(() -> {
            progressLabel.setText("%d/%d tiles copied (%s)%s".formatted(
                    p.tileIndex() + 1, p.tileCount(),
                    p.copier().actuallyDirectCopy() ? "direct" : "repacking",
                    p.isLastTileCopied() ? "" : "..."));
        }));
        copier.setDirectCopy(directMode.isSelected());
        if (!copier.isDirectCopy()) {
            copier.setCompression(getSelectedCompression());
        }
        return copier;
    }

    private Double getCompressionQuality() {
        final String text = compressionQualityField.getText().trim();
        try {
            return text.isEmpty() ? null : Double.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid compression quality: " + text);
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
