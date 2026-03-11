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

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

public class TiffExplorer {
    public static final String ALGART_TIFF_WEBSITE = "https://algart.net/java/AlgART-TIFF/";

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

    private void changeDescription(int index, String newDescription) throws IOException {
        try (TiffWriter writer = new TiffWriter(tiffFile, TiffCreateMode.OPEN_EXISTING)) {
            writer.rewriteDescription(index, newDescription);
        }
        frame.reload();
    }

    void showImageWindow() {
        int index = frame.selectedImage();
        if (notInitialized(index)) {
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
        if (notInitialized(index)) {
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
            dialog.dispose();
            try {
                changeDescription(index, newDescription.isEmpty() ? null : newDescription);
            } catch (Exception e) {
                showErrorMessage(frame, e, "Error updating ImageDescription");
            }
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

    void showAboutDialog() {
        JDialog dialog = new JDialog(frame, "About TIFF Information Viewer", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setMinimumSize(new Dimension(100, 200));
        dialog.setResizable(false);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 50, 10, 50));

        JLabel icon = new JLabel(new ImageIcon(reqResource("TiffExplorer_icon_32.png")));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(icon);
        content.add(Box.createVerticalStrut(16));

        JLabel title = centeredLabel("AlgART TIFF Explorer");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        content.add(title);
        content.add(Box.createVerticalStrut(8));

        content.add(centeredLabel("By Daniel Alievsky (AlgART Laboratory)"));
        content.add(centeredLabel("Version 1.5.2"));
        content.add(Box.createVerticalStrut(8));

        JLabel link = centeredLabel("<html><a href=\"\">" + ALGART_TIFF_WEBSITE + "</a></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new java.net.URI(ALGART_TIFF_WEBSITE));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog,
                            "Cannot open browser:\n" + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        content.add(Box.createVerticalStrut(10));
        content.add(link);

        dialog.add(content, BorderLayout.CENTER);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> dialog.dispose());
        JPanel btnPanel = new JPanel();
        btnPanel.add(ok);

        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.pack();
        addCloseOnEscape(dialog);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private boolean notInitialized(int index) {
        return index < 0 || info == null || index >= info.numberOfImages();
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

    private static JLabel centeredLabel(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
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
