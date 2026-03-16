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

import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.app.TiffInfo;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class TiffExplorer {
    private static final float ALL_FONTS_SCALE = 1.2f;
    // - default font sizes in Java API are usually too small

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

    void chooseAndOpenFile() {
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

    private void loadTiff(Path file) {
        this.tiffFile = file;
        frame.reload();
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

        final JLabel imageDescriptionLabel = new JLabel(
                "Description of TIFF image #%d (\"ImageDescription\" tag)".formatted(index));
        imageDescriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(imageDescriptionLabel);
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

        final JLabel warningLabel = new JLabel("""
                <html>Warning! This action will rewrite the IFD in the TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                The current image description will be permanently <b>replaced</b>.<br>
                You may create a backup copy if the file is important.</html>
                """.formatted(tiffFile));
        warningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(warningLabel);

        dialog.add(content, BorderLayout.CENTER);

        final JButton okButton = new JButton("Rewrite IFD in the file");
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

    void showRemoveTagsDialog() {
        int index = frame.selectedImage();
        if (!isInitialized(index)) {
            return;
        }

        final JDialog dialog = new JDialog(frame, "Remove IFD tags", true);
        dialog.setResizable(false);
        dialog.setLayout(new BorderLayout(10, 10));

        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final TiffIFD ifd = info.metadata().ifd(index);
        final List<Integer> tagsToPossiblyRemove = tagsToPossiblyRemove(ifd);

        final boolean hasTags = !tagsToPossiblyRemove.isEmpty();
        final JLabel tagListLabel = new JLabel(!hasTags ?
                "No tags can be safely removed" :
                "The following tags may be removed");
        tagListLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(tagListLabel);
        content.add(Box.createVerticalStrut(10));

        if (hasTags) {
            final JPanel tagListPanel = new JPanel();
            tagListPanel.setLayout(new BoxLayout(tagListPanel, BoxLayout.Y_AXIS));
            final Map<Integer, JCheckBox> selectedTags = new java.util.HashMap<>();
            for (int tag : tagsToPossiblyRemove) {
                final JCheckBox checkBox = new JCheckBox(Tags.prettyName(tag, true));
                tagListPanel.add(checkBox);
                selectedTags.put(tag, checkBox);
            }
            final JScrollPane scrollPane = new JScrollPane(
                    tagListPanel,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            );
            scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
            Dimension pref = tagListPanel.getPreferredSize();
            int maxHeight = 300;
            scrollPane.setPreferredSize(
                    new Dimension(scrollPane.getPreferredSize().width, Math.min(pref.height, maxHeight))
            );
            content.add(scrollPane);

            final JLabel warningLabel = new JLabel("""
                    <html>Warning! This action will rewrite the IFD in the TIFF file:<br>
                    &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                    The selected tags will be permanently <b>deleted</b>.<br>
                    This may cause the image to be displayed incorrectly.<br>
                    You may create a backup copy if the file is important.</html>
                    """.formatted(tiffFile));
            warningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(warningLabel);

            dialog.add(content, BorderLayout.CENTER);

            final JButton okButton = new JButton("Rewrite IFD in the file");
            final JButton cancelButton = new JButton("Cancel");

            okButton.addActionListener(event -> {
                java.util.List<Integer> tagsToRemove = new ArrayList<>();
                for (Map.Entry<Integer, JCheckBox> entry : selectedTags.entrySet()) {
                    if (entry.getValue().isSelected()) {
                        tagsToRemove.add(entry.getKey());
                    }
                }
                try {
                    removeTags(index, tagsToRemove);
                } catch (Exception e) {
                    showErrorMessage(frame, e, "Error updating IFD");
                }
                dialog.dispose();
            });
            cancelButton.addActionListener(e -> dialog.dispose());
            final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);
        } else {
            final JButton closeButton = new JButton("Close");
            closeButton.addActionListener(e -> dialog.dispose());
            final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            buttonPanel.add(closeButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);
        }
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
            writer.rewriteDescription(index, newDescription);
        }
        frame.reload();
    }

    private java.util.List<Integer> tagsToPossiblyRemove(TiffIFD ifd) {
        return ifd.map().keySet().stream()
                .filter(tag -> !ifd.isTagCritical(tag, false))
                .collect(Collectors.toList());
    }

    private void removeTags(int index, Collection<Integer> tags) throws IOException {
        if (tags.isEmpty()) {
            return;
        }
        try (TiffWriter writer = new TiffWriter(tiffFile, TiffCreateMode.OPEN_EXISTING)) {
            final TiffIFD ifd = writer.existingIFD(index);
            final TiffIFD changedIFD = new TiffIFD(ifd);
            for (int tag : tags) {
                changedIFD.remove(tag);
            }
            writer.replaceIFD(index, changedIFD, false);
            // - we don't need to relocate IFD: its size was decreased
        }
        frame.reload();
    }

    private boolean isInitialized(int index) {
        return info != null && index >= 0 && index < info.numberOfImages();
    }

    static void addCloseOnEscape(JDialog dialog) {
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
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
}
