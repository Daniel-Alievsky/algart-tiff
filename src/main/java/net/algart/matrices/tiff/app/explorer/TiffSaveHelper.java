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
import net.algart.matrices.tiff.TiffReader;
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

class TiffSaveHelper {
    private static final int MAX_SINGLE_IMAGE_SIZE_IN_PIXELS = 25 * 1024 * 1024;
    // - 25 megapixels: even for RGBA with float precision, it is only 4*4*25 MB = 400 MB < 2^31 bytes

    private static final String PREF_LAST_EXPORT__DIR = "viewer.export.lastDirectory";
    private static final String PREF_LAST_SAVE_AS_TIFF_DIR = "viewer.copier.lastSaveAsTiffDirectory";
    private static final String PREF_AUTO_CLOSE = "viewer.copier.autoClose";

    private static final FileFilter ANY_IMAGE_FILTER = new FileNameExtensionFilter(
            "Image files (*.png, *.jpg, *.jpeg, *.bmp, *.gif, *.tif, *.tiff)",
            "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff");
    private static final FileFilter TIFF_FILTER = new FileNameExtensionFilter(
            "TIFF files (*.tif, *.tiff)",
            "tif", "tiff");

    private static final System.Logger LOG = System.getLogger(TiffViewer.class.getName());

    private final JFrame frame;

    private volatile boolean copyingInProgress = false;
    private volatile boolean stopCopyingRequested = false;

    private JCheckBox directMode;
    private JLabel compressionQualityLabel;
    private JTextField compressionQualityField;
    private JComboBox<String> compressionMethodBox;
    private JLabel copyProgressLabel;
    private JCheckBox autoClose;
    private JButton startCopyButton;
    private JButton cancelCopyButton;
    private JDialog copySettingsDialog;

    public TiffSaveHelper(JFrame frame) {
        this.frame = Objects.requireNonNull(frame);
    }

    public Path chooseFileToExport(TiffViewer viewer, boolean processSelection) {
        final String whatToExport = processSelection ? "the selected area" : "the image";
        if (!confirmImageSize(
                viewer,
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
        chooser.setDialogTitle("Export " + whatToExport);
        chooser.setSelectedFile(new File(processSelection ? "selected.png" : "image.png"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(ANY_IMAGE_FILTER);
        chooser.setFileFilter(ANY_IMAGE_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = chooseFile(chooser);
        if (file == null) {
            return null;
        }
        TiffExplorer.PREFERENCES.put(PREF_LAST_EXPORT__DIR, file.getParent());
        return file.toPath();
    }

    public Path chooseTiffFileToSave(boolean processSelection) {
        final String whatToSave = processSelection ? "the selected area" : "the image";
        JFileChooser chooser = new JFileChooser();
        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_SAVE_AS_TIFF_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Save " + whatToSave + " as a new TIFF file");
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

    public void copySelectedAreaToClipboard(TiffViewer viewer) throws IOException {
        Objects.requireNonNull(viewer, "Null viewer");
        if (!confirmImageSize(viewer,
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

    public void exportImageToFile(TiffViewer viewer, Path targetFile, boolean processSelection) throws IOException {
        Objects.requireNonNull(viewer, "Null viewer");
        Objects.requireNonNull(targetFile, "Null targetFile");
        BufferedImage image = processSelection ? viewer.readSelectedImage() : viewer.readEntireImage();
        if (image != null) {
            LOG.log(System.Logger.Level.DEBUG, "Export %s to %s: %s".formatted(
                    processSelection ? "the selected area" : "TIFF image", targetFile, image));
            MatrixIO.writeBufferedImage(targetFile, image);
        }
    }

    public void showCopyToTiffDialog(TiffViewer viewer, Path targetFile, boolean processSelection) {
        Objects.requireNonNull(viewer, "Null viewer");
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
        final String whatToSave = processSelection ? "the selected area" : "the image";

        final Path tiffFile = viewer.path();
        final TiffReadMap map = viewer.map();
        final int ifdIndex = viewer.ifdIndex();
        final TagCompression originalCompression = map.compression().orElse(TagCompression.NONE);
        final boolean originalCompressionSupported = originalCompression.isWritingSupported();
        final TagCompression compression = !originalCompressionSupported ? TagCompression.NONE : originalCompression;
        copySettingsDialog = new JDialog(frame);
        copySettingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        copySettingsDialog.setTitle("Save " + whatToSave + " as a new TIFF file");
        copySettingsDialog.setLayout(new BorderLayout(10, 10));
        copySettingsDialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final String whatToCopy = selection == null ?
                "The TIFF image #%d (%d\u00D7%d)".formatted(ifdIndex, sizeX, sizeY) :
                "The selected area %d\u00D7%d (top-left at %d,%d) of the TIFF image #%d"
                        .formatted(sizeX, sizeY, selection.x, selection.y, ifdIndex);
        final JLabel infoLabel = new JLabel(TiffExplorer.smartHtmlLines("""
                %s from the file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br><br>
                will be copied to a new TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>&nbsp;
                """.formatted(
                whatToCopy,
                tiffFile, targetFile.toAbsolutePath()
        )));
//        infoLabel.setFont(infoLabel.getFont().deriveFont((float) DEFAULT_FONT_SIZE));
        infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(infoLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        directMode = new JCheckBox("Quick direct copying (\"as-is\")");
        directMode.setEnabled(tileAligned);
        directMode.setSelected(false);
        // - by default, we prefer to enable editing compression
        directMode.addActionListener(e -> correctCompressionControls());
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
                    Quick copying is unavailable for an unaligned selection (starting at %d,%d).<br>
                    You can use<br>
                    &nbsp;&nbsp;&nbsp;&nbsp;Edit \u25B8 %s  (Ctrl+Alt+A)<br>
                    to enable direct copying.
                    """.formatted(
                    selection.x, selection.y,
                    viewer.alignSelectionToTileGridCommand());
            directCommentLabel = new JLabel(TiffExplorer.smartHtmlLines(directComment));
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
        compressionMethodBox.addActionListener(e -> correctCompressionControls());
        settingsPanel.add(compressionMethodBox);
        settingsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, settingsPanel.getPreferredSize().height));
        mainPanel.add(settingsPanel);

        if (!originalCompressionSupported) {
            final JLabel compressionMethodComment = new JLabel("""
                    <html>Note: the original compression method<br>
                    &nbsp;&nbsp;&nbsp;&nbsp;"%s"<br>
                    is not supported for writing.
                    """.formatted(originalCompression.prettyName()));
            compressionMethodComment.setAlignmentX(Component.LEFT_ALIGNMENT);
            compressionMethodComment.setEnabled(false);
            mainPanel.add(compressionMethodComment);
        }
        mainPanel.add(Box.createVerticalStrut(5));
        autoClose = new JCheckBox("Close this dialog automatically after copying");
        autoClose.setSelected(TiffExplorer.PREFERENCES.getBoolean(PREF_AUTO_CLOSE, false));
        mainPanel.add(autoClose);

        copyProgressLabel = new JLabel("999/999 tiles copied...");
        copyProgressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(copyProgressLabel);

        copySettingsDialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startCopyButton = new JButton();
        cancelCopyButton = new JButton();
        initializeButtons(false);
        buttonPanel.add(startCopyButton);
        buttonPanel.add(cancelCopyButton);
        copySettingsDialog.add(buttonPanel, BorderLayout.SOUTH);
        copySettingsDialog.getRootPane().setDefaultButton(startCopyButton);
        cancelCopyButton.addActionListener(e -> cancelCopy());
        startCopyButton.addActionListener(e -> startCopy(tiffFile, targetFile, ifdIndex, selection));

        stopCopyingRequested = false;
        copyingInProgress = false;
        copySettingsDialog.pack();

        TiffExplorer.addCloseOnEscape(copySettingsDialog);
        copyProgressLabel.setText("");
        correctCompressionControls();
        copySettingsDialog.setLocationRelativeTo(frame);
        copySettingsDialog.setVisible(true);
    }

    private void startCopy(Path sourceFile, Path targetFile, int ifdIndex, Rectangle r) {
        final TiffCopier copier;
        final Double compressionQuality;
        try {
            copier = buildCopier();
            compressionQuality = getCompressionQuality();
            stopCopyingRequested = false;
            startCopyButton.setEnabled(false);
            startCopyButton.setVisible(false);
            cancelCopyButton.setText("Cancel copying");
            copySettingsDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            copyingInProgress = true;
        } catch (Exception e) {
            TiffExplorer.showErrorMessage(frame, e, "Error copying TIFF");
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (TiffReader reader = new TiffReader(sourceFile);
                        TiffWriter writer = new TiffWriter(targetFile)) {
                    // - we must create a new reader without customizing setAutoCorrectColors
                    writer.setSmartCorrection(true);
                    writer.setFormatLike(reader);
                    // - without this operator, direct copy will be impossible for LE format
                    // if (true) throw new IOException("Test exception");
                    writer.setCompressionQuality(compressionQuality);
                    writer.create();
                    if (r == null) {
                        copier.copyImage(writer, reader, ifdIndex);
                    } else {
                        copier.copyRectangle(writer, reader, ifdIndex, r.x, r.y, r.width, r.height);
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                boolean successful = false;
                try {
                    get();
                    successful = !copier.isCancelled();
                } catch (InterruptedException | ExecutionException e) {
                    TiffExplorer.showErrorMessage(frame, e, "Error copying TIFF");
                }
                final boolean closeAfterCopy = autoClose.isSelected();
                TiffExplorer.PREFERENCES.putBoolean(PREF_AUTO_CLOSE, closeAfterCopy);
                copyingInProgress = false;
                copySettingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                if (successful) {
                    copySettingsDialog.getRootPane().setDefaultButton(cancelCopyButton);
                }
                initializeButtons(successful);
                if (successful && closeAfterCopy) {
                    copySettingsDialog.dispose();
                }
            }
        }.execute();
    }

    private void cancelCopy() {
        if (copyingInProgress) {
            stopCopyingRequested = true;
        } else {
            copySettingsDialog.dispose();
        }
    }

    private void initializeButtons(boolean again) {
        startCopyButton.setText(again ? "Start again" : "Start");
        cancelCopyButton.setText(again ? "Close" : "Cancel");
        startCopyButton.setEnabled(true);
        startCopyButton.setVisible(true);
    }

    private boolean confirmImageSize(
            TiffViewer viewer,
            String actionName,
            String recommendedAction,
            boolean processSelection) {
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
        copier.setInterruptionChecker(() -> stopCopyingRequested);
        copier.setProgressUpdater(this::updateProgress, 500);
        copier.setDirectCopy(directMode.isSelected());
        if (!copier.isDirectCopy()) {
            copier.setCompression(getSelectedCompression());
        }
        return copier;
    }

    private void updateProgress(TiffCopier.ProgressInformation p) {
        SwingUtilities.invokeLater(() ->
                copyProgressLabel.setText("%d/%d tiles copied (%s)%s".formatted(
                        p.tileIndex() + 1, p.tileCount(),
                        p.copier().isActuallyDirectCopy() ? "direct" : "repacking",
                        p.isLastTileCopied() ? "" : "...")));
    }

    private Double getCompressionQuality() {
        if (!compressionQualityField.isEnabled()) {
            return null;
            // - no sense to throw an exception for invalid text in a disabled field
        }
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

    private void correctCompressionControls() {
        final TagCompression compression = getSelectedCompression();
        final boolean directCopy = directMode.isSelected();
        final boolean compressionQualitySupported = compression.isCompressionQualitySupported() && !directCopy;
        compressionQualityLabel.setText(compressionQualityLabel(compression));
        compressionQualityLabel.setEnabled(compressionQualitySupported);
        compressionQualityField.setEnabled(compressionQualitySupported);
        compressionMethodBox.setEnabled(!directCopy);
    }

    private static String compressionQualityLabel(TagCompression compression) {
        return "Compression quality" +
                (compression.isStandardJpeg() ? " (from 0 to 1)" :
                        compression.isJpeg2000() ? " (from ~0.5 to ~20 or higher)" : "")
                + ":";
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
