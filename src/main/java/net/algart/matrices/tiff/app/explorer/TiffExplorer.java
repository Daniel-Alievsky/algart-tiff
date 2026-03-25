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
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.app.TiffInfo;
import net.algart.matrices.tiff.tags.TagPhotometric;
import net.algart.matrices.tiff.tags.Tags;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class TiffExplorer {
    private static final float ALL_FONTS_SCALE = 1.3f;
    // - default font sizes in Java API are usually too small
    private static final String ALL_LINE_HEIGHT_SCALE = "1.3";

    private static final String PREF_LAST_DIR = "main.lastDirectory";

    private static final FileFilter TIFF_FILTER = new FileNameExtensionFilter(
            "TIFF / SVS files (*.tif, *.tiff, *.svs)", "tif", "tiff", "svs");
    private static final FileFilter SVS_FILTER = new FileNameExtensionFilter(
            "SVS files only (*.svs)", "svs");

    private static final System.Logger LOG = System.getLogger(TiffExplorer.class.getName());

    static final Preferences PREFERENCES = Preferences.userNodeForPackage(TiffExplorer.class);

    private JTiffExplorerFrame frame;
    private FileFilter lastFileFilter = TIFF_FILTER;

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

            for (Object key : defaults.keySet()) {
                Object value = defaults.get(key);
                if (value instanceof Font font) {
                    float newSize = font.getSize2D() * ALL_FONTS_SCALE;
                    defaults.put(key, font.deriveFont(newSize));
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
                showErrorMessage(tiffExplorer.frame, e, "Error while creating GUI");
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
        JFileChooser chooser = new JFileChooser();
        String last = PREFERENCES.get(PREF_LAST_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.addChoosableFileFilter(TIFF_FILTER);
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
                // - should not occur
                showErrorMessage(frame, ex, "Unexpected error");
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
            TiffViewer imageViewer = new TiffViewer(tiffFile, index);
            frame.setShowImageInProgress(true);
            imageViewer.show();
        } catch (IOException e) {
            showErrorMessage(frame, e, "Error opening the TIFF image");
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

        content.add(leftLabel("Description of TIFF image #%d (ImageDescription tag)".formatted(index)));
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

        content.add(leftLabel(smartHtmlLines("""
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
                showErrorMessage(frame, e, "Error updating ImageDescription");
            }
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        addCloseOnEscape(dialog);
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

        content.add(leftLabel("You may rewrite the PhotometricInterpretation tag in this IFD."));
        content.add(Box.createVerticalStrut(10));

        final TiffIFD ifd = info.metadata().ifd(index);
        final PhotometricItem existingPhotometricItem = new PhotometricItem(
                ifd.hasPhotometric() ? ifd.optPhotometricCode(-1) : null,
                ifd.optPhotometric().orElse(null));
        final boolean unknownPhotometric = ifd.hasPhotometric() && existingPhotometricItem.photometric == null;
        if (ifd.hasPhotometric()) {
            content.add(leftLabel("Current value:"));
            content.add(leftIndent(new JLabel("<html><b>" + existingPhotometricItem + "</b></html>"), 25));
        } else {
            content.add(leftLabel("Nothing (does not exist)"));
        }
        content.add(Box.createVerticalStrut(10));

        content.add(leftLabel("New value to write:"));
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
        content.add(leftIndent(photometricComboBox, 20));
        content.add(Box.createVerticalStrut(15));

        content.add(leftLabel(smartHtmlLines("""
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
                showErrorMessage(frame, e, "Error updating IFD");
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
        addCloseOnEscape(dialog);
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
        content.add(leftLabel(!hasTags ?
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
                        Tags.prettyName(tag, true),
                        ifd.isTagCritical(tag, true) ? " <font color=\"red\">\u2013 be careful!</font>" : "");
                final JCheckBox checkBox = new JCheckBox(caption);
                checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                checkBox.addActionListener(e ->
                        okButton.setEnabled(!selectedTags(tagsToSelect).isEmpty()));
                tagListPanel.add(checkBox);
                tagsToSelect.put(tag, checkBox);
            }
            final Dimension tagListprefSize = tagListPanel.getPreferredSize();
            final int maxHeight = 150;
            if (tagListprefSize.height > maxHeight) {
                final JScrollPane scrollPane = new JScrollPane(
                        tagListPanel,
                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                );
                scrollPane.setBorder(BorderFactory.createEmptyBorder());
                scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
                scrollPane.setPreferredSize(new Dimension(scrollPane.getPreferredSize().width + 30, maxHeight));
                content.add(scrollPane);
            } else {
                content.add(tagListPanel);
            }
            content.add(Box.createVerticalStrut(15));

            final JLabel warningLabel = new JLabel(smartHtmlLines("""
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
                    showErrorMessage(frame, e, "Error updating IFD");
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
        addCloseOnEscape(dialog);
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
                .map(tag -> " \u2022 " + Tags.prettyName(tag, true))
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
        return tiffFile != null && info != null && index >= 0 && index < info.numberOfImages();
    }

    static void addActionOnEscape(JDialog dialog, Runnable action) {
        dialog.getRootPane().registerKeyboardAction(
                e -> action.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    static void addCloseOnEscape(JDialog dialog) {
        addActionOnEscape(dialog, () -> {
            if (dialog.getDefaultCloseOperation() == JDialog.DISPOSE_ON_CLOSE) {
                dialog.dispose();
            }
        });
    }

    static void showErrorMessage(JFrame frame, Throwable e, String title) {
        if (e instanceof ExecutionException && e.getCause() != null) {
            // ExecutionExcepion is a wrapper added by this utility: no sense to show it
            e = e.getCause();
        }
        LOG.log(System.Logger.Level.ERROR, title + ": " + e.getMessage(), e);
        if (e instanceof TiffException && e.getCause() instanceof IOException) {
            // It is a wrapper added by TiffReader: no sense to show it to the end user
            e = e.getCause();
        }
        JOptionPane.showMessageDialog(frame, e.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    static String smartHtmlLines(String htmlContent) {
        String[] lines = htmlContent.trim().split("<br>", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append("<div%s>%s</div>".formatted(i == 0 ? "" : " style=\"margin-top: 5px\"", lines[i]));
        }
        return "<html>" + sb + "</html>";
    }

    static Color getUIColor(String name, Color defaultValue) {
        final Color color = UIManager.getColor(name);
        return color != null ? color : defaultValue;
    }

    static void setTiffExplorerIcon(JFrame frame) {
        frame.setIconImages(java.util.List.of(
                new ImageIcon(reqResource("TiffExplorer_icon_16.png")).getImage(),
                new ImageIcon(reqResource("TiffExplorer_icon_32.png")).getImage()));
    }

    static URL reqResource(String name) {
        final URL result = TiffExplorer.class.getResource(name);
        Objects.requireNonNull(result, "Resource " + name + " not found");
        return result;
    }

    static JLabel leftLabel(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    static JComponent leftIndent(JComponent component, int gap) {
        Box box = Box.createHorizontalBox();
        box.add(Box.createHorizontalStrut(gap));
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(component);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        return box;
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
