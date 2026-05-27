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

import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.tags.TagCompression;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

class TiffNewHelper {
    private static final String PREF_LAST_NEW_TIFF_DIR = "viewer.copier.lastNewTiffDirectory";

    private final JFrame frame;
    private final TiffExplorer explorer;

    private volatile boolean copyingInProgress = false;
    private volatile boolean stopRequested = false;

    private JDialog settingsDialog;
    private JComboBox<UserByteOrder> byteOrderComboBox;
    private JCheckBox bigTiffCheckBox;
    private JCheckBox tiledCheckBox;
    private JTextField dimXField, dimYField, tileSizeXField, tileSizeYField;
    private JComboBox<Integer> channelCountComboBox; // 1, 3, 4
    private JComboBox<TiffSampleType> sampleTypeComboBox;
    private JComboBox<TagCompression> compressionComboBox;
    private JCheckBox patternCheckBox;
    private JButton colorButton;
    private Color selectedColor = Color.BLACK;

    public TiffNewHelper(JTiffExplorerFrame frame) {
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

    public void showNewTiffDialog(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "Null targetFile");
        settingsDialog = new JDialog(frame);
        settingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        settingsDialog.setTitle("Create new TIFF");
        settingsDialog.setLayout(new BorderLayout(10, 10));
        settingsDialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mainPanel.add(TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                The new blank TIFF file will be created:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                &nbsp;<br>
                """.formatted(
                targetFile.toAbsolutePath()
        ))));
        mainPanel.add(Box.createVerticalStrut(10));

        final JPanel settingsPanel = new JPanel();
        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));
        TinySwing.addTitledBorder(settingsPanel, "TIFF file settings");
        settingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        final JPanel oneRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        oneRowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        oneRowPanel.add(new JLabel("Byte order: "));
        byteOrderComboBox = new JComboBox<>(UserByteOrder.values());
        byteOrderComboBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        byteOrderComboBox.setMaximumSize(byteOrderComboBox.getPreferredSize());
        oneRowPanel.add(byteOrderComboBox);
        oneRowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, oneRowPanel.getPreferredSize().height));
        settingsPanel.add(oneRowPanel);
        settingsPanel.add(Box.createVerticalStrut(5));

        bigTiffCheckBox = new JCheckBox("Big-TIFF (necessary for large files >4 GB)");
        bigTiffCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        bigTiffCheckBox.setSelected(false);
        settingsPanel.add(bigTiffCheckBox);
        mainPanel.add(settingsPanel);
        mainPanel.add(Box.createVerticalStrut(10));
        settingsDialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        final JButton okButton = new JButton("Rewrite ImageDescription tag");
        final JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        settingsDialog.add(buttonPanel, BorderLayout.SOUTH);
        settingsDialog.getRootPane().setDefaultButton(okButton);
//        cancelButton.addActionListener(e -> cancelCopy());
//        startCopyButton.addActionListener(e -> startCopy(tiffFile, targetFile));

        settingsDialog.pack();

        TinySwing.addCloseOnEscape(settingsDialog);
        settingsDialog.setLocationRelativeTo(frame);
        settingsDialog.setVisible(true);
    }

    private void startCopy(Path sourceFile, Path targetFile) {
    }


    private void cancelCopy() {
        if (copyingInProgress) {
            stopRequested = true;
        } else {
            settingsDialog.dispose();
        }
    }
}
