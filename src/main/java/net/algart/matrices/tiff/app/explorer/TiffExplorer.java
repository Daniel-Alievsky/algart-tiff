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

import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.app.TiffInfo;
import net.algart.matrices.tiff.tags.TagPhotometric;
import net.algart.matrices.tiff.tags.Tags;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class TiffExplorer {
    static final Color COMMON_COLOR = TinySwing.getUIColor("Label.foreground", Color.BLACK);
    static final Color ERROR_COLOR = Color.RED;

    static final Preferences PREFERENCES = Preferences.userNodeForPackage(TiffExplorer.class);

    private static final float ALL_FONTS_SCALE = 1.3f;
    // - default font sizes in Java API are usually too small

    private static final String PREF_LAST_DIR = "main.lastDirectory";

    static final FileFilter TIFF_OR_SVS_FILTER = new FileNameExtensionFilter(
            "TIFF / SVS files (*.tif, *.tiff, *.svs)", "tif", "tiff", "svs");
    static final FileFilter TIFF_FILTER = new FileNameExtensionFilter(
            "TIFF files (*.tif, *.tiff)",
            "tif", "tiff");
    private static final FileFilter SVS_FILTER = new FileNameExtensionFilter(
            "SVS files only (*.svs)", "svs");

    private static final System.Logger LOG = System.getLogger(TiffExplorer.class.getName());

    private JTiffExplorerFrame frame;
    private FileFilter lastFileFilter = TIFF_OR_SVS_FILTER;

    private TiffInfo info = null;
    private Path tiffFile = null;
    private TiffIFD.StringFormat stringFormat = TiffIFD.StringFormat.NORMAL;

    public static void main(String[] args) {
        if (args.length >= 1 && args[0].equals("-h")) {
            System.out.println("Usage:");
            System.out.println("    " + TiffExplorer.class.getSimpleName() + " [some_file.tiff]");
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            UIDefaults defaults = UIManager.getDefaults();
            // UIManager.put("FileChooser.readOnly", Boolean.TRUE);
            // - not too good idea: renaming files is sometimes convenient
            final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                // - for other OS, scaling fonts is not too good idea: we risk degrading the interface
                for (Object key : defaults.keySet()) {
                    Object value = defaults.get(key);
                    if (value instanceof Font font) {
                        float newSize = font.getSize2D() * ALL_FONTS_SCALE;
                        defaults.put(key, font.deriveFont(newSize));
                    }
                }
            }
            // For cross-platform:
            // UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot set look and feel", e);
        }
        TiffExplorer tiffExplorer = new TiffExplorer();
        SwingUtilities.invokeLater(() -> {
            try {
                tiffExplorer.createGUI(args);
            } catch (Throwable e) {
                TinySwing.showErrorMessage(tiffExplorer.frame, e, "Error while creating GUI");
            }
        });
    }

    public boolean isInitialized() {
        int index = frame.selectedImage();
        return isInitialized(index);
    }

    public TiffInfo getInfo() {
        return info;
    }

    public Path getTiffFile() {
        return tiffFile;
    }

    public TiffIFD.StringFormat getStringFormat() {
        return stringFormat;
    }

    public TiffExplorer setStringFormat(TiffIFD.StringFormat stringFormat) {
        this.stringFormat = stringFormat;
        return this;
    }

    private void createGUI(String[] args) {
        this.frame = new JTiffExplorerFrame(this);
        if (args.length >= 1) {
            loadTiff(Path.of(args[0]));
        }
    }

    void chooseFileAndOpen() {
        JFileChooser chooser = TinySwing.newFileChooser();
        String last = PREFERENCES.get(PREF_LAST_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.addChoosableFileFilter(TIFF_OR_SVS_FILTER);
        chooser.addChoosableFileFilter(SVS_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(lastFileFilter != null ? lastFileFilter : chooser.getAcceptAllFileFilter());
        chooser.setDialogTitle("Select a TIFF file");
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file != null) {
                PREFERENCES.put(PREF_LAST_DIR, file.getParent());
                lastFileFilter = chooser.getFileFilter();
                if (lastFileFilter == chooser.getAcceptAllFileFilter()) {
                    lastFileFilter = null;
                }
                loadTiff(file.toPath());
            }
        }
    }

    void chooseFileAndShowSaveDialog() {
        TiffSaveHelper helper = new TiffSaveHelper(frame);
        Path file = helper.chooseTiffFileToSave();
        if (file != null) {
            try {
                helper.showSaveTiffDialog(file);
            } catch (Exception ex) {
                TinySwing.showErrorMessage(frame, ex, "Error copying TIFF");
            }
        }
    }

    void showCompactDialog() {
        new TiffSaveHelper(frame).showCompactDialog();
    }

    void reload() {
        frame.reload();
    }

    private void loadTiff(Path file) {
        this.tiffFile = file;
        reload();
    }

    void loadTiffInfo() throws IOException {
        info = new TiffInfo();
        info.setDisableAppendingForStrictFormats(true);
        info.setStringFormat(stringFormat);
//                 Thread.sleep(5000);
        info.collectTiffInfo(tiffFile);
    }

    void showImageWindow() {
        int index = frame.selectedImage();
        if (!isInitialized(index)) {
            return;
        }
        frame.setShowImageInProgress(true);
        try {
            new TiffViewer(tiffFile, index).show();
        } catch (IOException e) {
            TinySwing.showErrorMessage(frame, e, "Error opening the TIFF image");
        } finally {
            frame.setShowImageInProgress(false);
        }
    }

    void showEditDescriptionDialog() {
        int index = frame.selectedImage();
        if (!isInitialized(index)) {
            return;
        }

        final JDialog dialog = new JDialog(frame, "Edit image description", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));

        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final JTextArea descriptionArea = new JTextArea(6, 60);
        descriptionArea.setLineWrap(false);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setFont(frame.getCurrentPreferredMonoFont());
        final String description = info.metadata().description(index).description("");
        descriptionArea.setText(description);
        descriptionArea.setCaretPosition(0);

        final JScrollPane scrollPane = new JScrollPane(
                descriptionArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);

        content.add(TinySwing.leftLabel(
                "Description of TIFF image #%d (ImageDescription tag)".formatted(index)));
        content.add(Box.createVerticalStrut(5));
        content.add(scrollPane);

        final JPanel toolsPanel = new JPanel(new BorderLayout());
        toolsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        final JCheckBox wordWrapCheckbox = new JCheckBox("Word wrap");
        wordWrapCheckbox.setSelected(false);
        wordWrapCheckbox.addActionListener(event -> {
            boolean wordWrap = wordWrapCheckbox.isSelected();
            descriptionArea.setLineWrap(wordWrap);
        });
        wordWrapCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        toolsPanel.add(wordWrapCheckbox, BorderLayout.EAST);
        content.add(toolsPanel);
        toolsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, toolsPanel.getPreferredSize().height));
        content.add(Box.createVerticalStrut(10));

        content.add(TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                Warning! This action will rewrite the IFD %d in the TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                The current image description will be permanently <b>replaced</b>.<br>
                You may create a backup copy if the file is important.
                """.formatted(index, tiffFile))));

        dialog.add(content, BorderLayout.CENTER);

        final JButton okButton = new JButton("Rewrite ImageDescription tag");
        final JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(event -> {
            String newDescription = descriptionArea.getText();
            try {
                changeDescription(index, newDescription.isEmpty() ? null : newDescription);
            } catch (Exception e) {
                TinySwing.showErrorMessage(frame, e, "Error updating ImageDescription");
            }
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        TinySwing.addCloseOnEscape(dialog);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    void showRewritePhotometricDialog() {
        int index = frame.selectedImage();
        if (!isInitialized(index)) {
            return;
        }

        final JDialog dialog = new JDialog(frame, "Rewrite photometric interpretation", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setMinimumSize(new Dimension(250, 50));

        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final JButton okButton = new JButton("Rewrite PhotometricInterpretation tag");
        okButton.setEnabled(false);
        final JButton cancelButton = new JButton("Cancel");

        content.add(TinySwing.leftLabel("You may rewrite the PhotometricInterpretation tag in this IFD."));
        content.add(Box.createVerticalStrut(10));

        final TiffIFD ifd = info.metadata().ifd(index);
        final PhotometricItem existingPhotometricItem = new PhotometricItem(
                ifd.hasPhotometric() ? ifd.optPhotometricCode(-1) : null,
                ifd.optPhotometric().orElse(null));
        final boolean unknownPhotometric = ifd.hasPhotometric() && existingPhotometricItem.photometric == null;
        if (ifd.hasPhotometric()) {
            content.add(TinySwing.leftLabel("Current value:"));
            content.add(TinySwing.leftIndent(
                    new JLabel("<html><b>" + existingPhotometricItem + "</b></html>"), 25));
        } else {
            content.add(TinySwing.leftLabel("Nothing (does not exist)"));
        }
        content.add(Box.createVerticalStrut(10));

        content.add(TinySwing.leftLabel("New value to write:"));
        final JComboBox<PhotometricItem> photometricComboBox = new JComboBox<>();
        photometricComboBox.setMaximumRowCount(50);
        photometricComboBox.addItem(new PhotometricItem(null, null));
        for (TagPhotometric tag : TagPhotometric.values()) {
            photometricComboBox.addItem(new PhotometricItem(tag.code(), tag));
        }
        if (unknownPhotometric) {
            photometricComboBox.addItem(existingPhotometricItem);
        }
        photometricComboBox.setSelectedItem(existingPhotometricItem);
        photometricComboBox.addActionListener(e -> {
                    final PhotometricItem selected = (PhotometricItem) photometricComboBox.getSelectedItem();
                    okButton.setEnabled(selected != null && !selected.equalPhotometric(existingPhotometricItem));
                }
        );
        photometricComboBox.setMaximumSize(photometricComboBox.getPreferredSize());
        photometricComboBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(TinySwing.leftIndent(photometricComboBox, 20));
        content.add(Box.createVerticalStrut(15));

        content.add(TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                Warning! This is a <b>low-level modification</b> of the IFD %d in the TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                Changing this tag does <b>not</b> convert the actual pixel data.<br>
                Primarily for testing; usually causes the image to be displayed incorrectly.<br>
                You may create a backup copy if the file is important.
                """.formatted(index, tiffFile))));

        okButton.addActionListener(event -> {
            PhotometricItem selectedItem = (PhotometricItem) photometricComboBox.getSelectedItem();
            if (selectedItem == null) {
                // - just in case
                return;
            }
            try {
                replacePhotometric(index, ifd, selectedItem.code);
            } catch (Exception e) {
                TinySwing.showErrorMessage(frame, e, "Error updating IFD");
            }
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        TinySwing.addCloseOnEscape(dialog);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    void showRemoveTagsDialog() {
        int index = frame.selectedImage();
        if (!isInitialized(index)) {
            return;
        }

        final JDialog dialog = new JDialog(frame, "Remove IFD tags", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setMinimumSize(new Dimension(250, 50));

        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final TiffIFD ifd = info.metadata().ifd(index);
        final List<Integer> tagsToPossiblyRemove = tagsToPossiblyRemove(ifd);

        final boolean hasTags = !tagsToPossiblyRemove.isEmpty();
        content.add(TinySwing.leftLabel(!hasTags ?
                "No tags can be safely removed" :
                "The following tags may be removed"));
        final JPanel buttonPanel;
        if (hasTags) {
            content.add(Box.createVerticalStrut(10));
            final JButton okButton = new JButton("Remove the selected tags");
            okButton.setEnabled(false);
            final JButton cancelButton = new JButton("Cancel");

            final JPanel tagListPanel = new JPanel();
            tagListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            tagListPanel.setLayout(new BoxLayout(tagListPanel, BoxLayout.Y_AXIS));
            final Map<Integer, JCheckBox> tagsToSelect = new java.util.HashMap<>();
            for (int tag : tagsToPossiblyRemove) {
                final String caption = "<html>%s%s</html>".formatted(
                        Tags.prettyName(tag),
                        ifd.isTagCritical(tag, true) ? " <font color=\"red\">\u2013 be careful!</font>" : "");
                final JCheckBox checkBox = new JCheckBox(caption);
                checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                checkBox.addActionListener(e ->
                        okButton.setEnabled(!selectedTags(tagsToSelect).isEmpty()));
                tagListPanel.add(checkBox);
                tagsToSelect.put(tag, checkBox);
            }
            final Dimension tagListprefSize = tagListPanel.getPreferredSize();
            final int tagListMaxHeight = 200;
            if (tagListprefSize.height > tagListMaxHeight) {
                final JScrollPane scrollPane = new JScrollPane(
                        tagListPanel,
                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                );
                scrollPane.setBorder(BorderFactory.createEmptyBorder());
                scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
                scrollPane.setPreferredSize(
                        new Dimension(scrollPane.getPreferredSize().width + 30, tagListMaxHeight));
                content.add(scrollPane);
            } else {
                content.add(tagListPanel);
            }
            content.add(Box.createVerticalStrut(15));

            final JLabel warningLabel = TinySwing.newLabel(TinySwing.smartHtmlLines("""
                    Warning! This is a <b>low-level modification</b> of the IFD %d in the TIFF file:<br>
                    &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                    Selected tags will be permanently <b>deleted</b> from the IFD.<br>
                    In some cases, this may cause the image to be displayed incorrectly.<br>
                    You may create a backup copy if the file is important.
                    """.formatted(index, tiffFile)));
            warningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(warningLabel);

            okButton.addActionListener(event -> {
                List<Integer> tagsToRemove = selectedTags(tagsToSelect);
                try {
                    if (!removeTags(index, tagsToRemove)) {
                        return;
                    }
                } catch (Exception e) {
                    TinySwing.showErrorMessage(frame, e, "Error updating IFD");
                }
                dialog.dispose();
            });
            cancelButton.addActionListener(e -> dialog.dispose());
            buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
        } else {
            final JButton closeButton = new JButton("Close");
            closeButton.addActionListener(e -> dialog.dispose());
            buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            buttonPanel.add(closeButton);
        }
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        TinySwing.addCloseOnEscape(dialog);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    void showAboutDialog() {
        new JTiffExplorerAboutDialog(frame).setVisible(true);
    }

    private void changeDescription(int index, String newDescription) throws IOException {
        try (TiffWriter writer = new TiffWriter(tiffFile, TiffCreateMode.OPEN_EXISTING)) {
            writer.updateDescription(index, newDescription);
        }
        frame.reload();
    }

    private java.util.List<Integer> tagsToPossiblyRemove(TiffIFD ifd) {
        return ifd.map().keySet().stream()
                .filter(tag -> !ifd.isTagCritical(tag, false))
                .collect(Collectors.toList());
    }

    private static List<Integer> selectedTags(Map<Integer, JCheckBox> selectedTags) {
        return selectedTags.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private boolean removeTags(int index, Collection<Integer> tags) throws IOException {
        if (tags.isEmpty()) {
            return true;
        }
        final String tagList = tags.stream()
                .map(tag -> " \u2022 " + Tags.prettyName(tag))
                .collect(Collectors.joining("\n"));
        final int choice = JOptionPane.showConfirmDialog(
                frame,
                "You are about to permanently delete the following tags from the IFD:\n\n" +
                        tagList +
                        "\n\nAre you sure you want to proceed?",
                "Confirm Tag Removal",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.YES_OPTION) {
            return false;
        }
        try (TiffWriter writer = new TiffWriter(tiffFile, TiffCreateMode.OPEN_EXISTING)) {
            writer.updateIFD(index, ifd -> {
                for (int tag : tags) {
                    ifd.remove(tag);
                }
                return TiffWriter.IFDUpdateResult.IN_PLACE;
                // - we don't need to relocate IFD: its size was decreased
            });
        }
        frame.reload();
        return true;
    }

    private void replacePhotometric(int index, TiffIFD existingIFD, Integer photometricCode) throws IOException {
        final boolean addYCbCrSubSampling;
        if (photometricCode != null
                && photometricCode == TiffIFD.PHOTOMETRIC_INTERPRETATION_Y_CB_CR
                && !existingIFD.hasYCbCrSubsampling()) {
            final int choice = JOptionPane.showConfirmDialog(
                    frame,
                    """
                            You are changing the PhotometricInterpretation to YCbCr.
                            But the current IFD does not contain YCbCrSubSampling tag.
                            Default sub-sampling is 2x2, which can be incompatible with the existing image.
                            
                            Would you like to add the YCbCrSubSampling tag 1x1 (no sub-sampling)
                            to provide data structure compatibility?
                            """,
                    "Confirm Adding YCbCrSubSampling",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (choice == JOptionPane.CANCEL_OPTION) {
                return;
            }
            addYCbCrSubSampling = choice == JOptionPane.YES_OPTION;
        } else {
            addYCbCrSubSampling = false;
        }
        try (TiffWriter writer = new TiffWriter(tiffFile, TiffCreateMode.OPEN_EXISTING)) {
            writer.updateIFD(index, ifd -> {
                final Integer existing = ifd.hasPhotometric() ? ifd.optPhotometricCode(-1) : null;
                if (Objects.equals(existing, photometricCode)) {
                    return TiffWriter.IFDUpdateResult.UNCHANGED;
                }
                ifd.putPhotometricCode(photometricCode);
                if (addYCbCrSubSampling) {
                    ifd.putYCbCrSubsampling(1, 1);
                }
                return TiffWriter.IFDUpdateResult.ofExpanded(existing == null || addYCbCrSubSampling);
                // - we don't need to relocate IFD: its size was unchanged
            });
        }
        frame.reload();
    }

    private boolean isInitialized(int index) {
        return tiffFile != null && info != null &&  index >= 0 && index < info.numberOfImages();
    }

    static void setTiffExplorerIcon(JFrame frame) {
        frame.setIconImages(java.util.List.of(
                getAppIcon16().getImage(),
                getAppIcon32().getImage()));
    }

    static ImageIcon getAppIcon32() {
        return new ImageIcon(TinySwing.reqResource("TiffExplorer_icon_32.png"));
    }

    static ImageIcon getAppIcon16() {
        return new ImageIcon(TinySwing.reqResource("TiffExplorer_icon_16.png"));
    }

    // code=null: no photometric tag
    // code=157: unknown photometric tag
    private record PhotometricItem(Integer code, TagPhotometric photometric) {
        public boolean equalPhotometric(PhotometricItem other) {
            return Objects.equals(code, other.code);
        }

        @SuppressWarnings("NullableProblems")
        @Override
        public String toString() {
            return code == null ? "Nothing (does not exist)" :
                    photometric == null ? code + " (unknown)" : photometric.toString();
        }
    }
}
