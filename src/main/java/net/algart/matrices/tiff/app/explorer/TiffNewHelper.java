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

import net.algart.matrices.tiff.TiffIFD;
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
    private JComboBox<UserNumberOfChannels> numberOfChannelsComboBox;
    private JComboBox<TiffSampleType> sampleTypeComboBox;
    private JComboBox<TagCompression> compressionComboBox;
    private JCheckBox patternCheckBox;
    private JButton colorButton;
    private Color selectedColor = Color.GRAY;

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

    private JTextField colorHexField;
    private JPanel colorPreviewPanel;

    public void showNewTiffDialog(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "Null targetFile");
        settingsDialog = new JDialog(frame, true);
        settingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        settingsDialog.setTitle("Create new blank TIFF");
        settingsDialog.setLayout(new BorderLayout(10, 10));
        settingsDialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel headerLabel = TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                The new blank TIFF file will be created:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                """.formatted(targetFile.toAbsolutePath())));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(headerLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        JPanel gridPanel = new JPanel(new GridBagLayout());
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(4, 5, 4, 5);
        int row = 0;
        addGridBugRowCaption(gridPanel, constraints, "Dimensions", false, row++);
        dimXField = new JTextField("1024", 10);
        dimYField = new JTextField("1024", 10);
        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Width (pixels):"), dimXField, row++);
        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Height (pixels):"), dimYField, row++);

        tiledCheckBox = new JCheckBox("Tiled TIFF image");
        tiledCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        tileSizeXField = new JTextField(String.valueOf(TiffIFD.DEFAULT_TILE_SIZE), 10);
        tileSizeYField = new JTextField(String.valueOf(TiffIFD.DEFAULT_TILE_SIZE), 10);
        tileSizeXField.setEnabled(false);
        tileSizeYField.setEnabled(false);
        tiledCheckBox.addActionListener(e -> {
            tileSizeXField.setEnabled(tiledCheckBox.isSelected());
            tileSizeYField.setEnabled(tiledCheckBox.isSelected());
        });

        addGridBugRowSingle(gridPanel, constraints, tiledCheckBox, row++);
        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Tile width:"), tileSizeXField, row++);
        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Tile height:"), tileSizeYField, row++);

        addGridBugRowCaption(gridPanel, constraints, "Filling settings", true, row++);
        numberOfChannelsComboBox = new JComboBox<>(UserNumberOfChannels.values());
        numberOfChannelsComboBox.setSelectedItem(3);
        sampleTypeComboBox = new JComboBox<>(TiffSampleType.values());
        sampleTypeComboBox.setSelectedItem(TiffSampleType.UINT8);

        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Channels:"), numberOfChannelsComboBox, row++);
        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Sample Type:"), sampleTypeComboBox, row++);

        JPanel colorChooserPanel = new JPanel(new BorderLayout(5, 0));
        colorHexField = new JTextField("XXXXXX", 7);
        updateHexFromColor();
        colorPreviewPanel = new JPanel();
        colorPreviewPanel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        colorPreviewPanel.setBackground(selectedColor);
        colorPreviewPanel.setPreferredSize(new Dimension(30, 20));
        colorButton = new JButton("...");
        colorButton.setMargin(new Insets(2, 4, 2, 4));
        colorChooserPanel.add(colorHexField, BorderLayout.CENTER);
        colorChooserPanel.add(colorPreviewPanel, BorderLayout.WEST);
        colorChooserPanel.add(colorButton, BorderLayout.EAST);
        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Fill color (hex):"), colorChooserPanel, row++);

        colorHexField.addActionListener(e -> updateColorFromHex());
        colorHexField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                updateColorFromHex();
            }
        });
        colorButton.addActionListener(e -> selectColor());

        patternCheckBox = new JCheckBox("Repeat some figure in each tile");
        patternCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        addGridBugRowSingle(gridPanel, constraints, patternCheckBox, row++);

        addGridBugRowCaption(gridPanel, constraints, "TIFF file settings", true, row++);

        byteOrderComboBox = new JComboBox<>(UserByteOrder.values());
        byteOrderComboBox.setSelectedItem(UserByteOrder.BIG_ENDIAN);
        addGridBugRowLabelled(gridPanel, constraints, new JLabel("Byte order:"), byteOrderComboBox, row++);

        bigTiffCheckBox = new JCheckBox("Big-TIFF (necessary for large files >4 GB)");
        bigTiffCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        addGridBugRowSingle(gridPanel, constraints, bigTiffCheckBox, row++);

        mainPanel.add(gridPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        settingsDialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        final JButton okButton = new JButton("Create");
        final JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        settingsDialog.add(buttonPanel, BorderLayout.SOUTH);
        settingsDialog.getRootPane().setDefaultButton(okButton);

        cancelButton.addActionListener(e -> settingsDialog.dispose());
        okButton.addActionListener(e -> {
                settingsDialog.dispose();
        });

        settingsDialog.pack();
        TinySwing.addCloseOnEscape(settingsDialog);
        settingsDialog.setLocationRelativeTo(frame);
        settingsDialog.setVisible(true);
    }

    private static void addGridBugRowCaption(
            JPanel panel,
            GridBagConstraints constraints,
            String caption,
            boolean verticalGap,
            int row) {
        JLabel label = new JLabel(caption);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setEnabled(false);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        Box box = Box.createVerticalBox();
        box.add(Box.createVerticalStrut(verticalGap ? 15 : 0));
        box.add(label);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        addGridBugRowSingle(panel, constraints, box, row);
    }

    private static void addGridBugRowSingle(
            JPanel panel,
            GridBagConstraints constraints,
            JComponent component,
            int row) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        panel.add(component, constraints);
        constraints.gridwidth = 1;
    }

    private static void addGridBugRowLabelled(
            JPanel panel,
            GridBagConstraints constraints,
            JLabel label,
            JComponent component,
            int row) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0.0;
        panel.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        panel.add(component, constraints);
    }

    private void selectColorSimple() {
        Color newColor = JColorChooser.showDialog(settingsDialog, "Select Fill Color", selectedColor);
        if (newColor != null) {
            selectedColor = newColor;
            updateHexFromColor();
            colorPreviewPanel.setBackground(selectedColor);
        }
    }

    private void selectColor() {
        JColorChooser chooser = new JColorChooser(selectedColor);
        chooser.setPreviewPanel(new JPanel());

        for (var ccPanel : chooser.getChooserPanels()) {
            ccPanel.setColorTransparencySelectionEnabled(false);
        }

        JDialog dialog = JColorChooser.createDialog(
                settingsDialog,
                "Select Fill Color",
                true,
                chooser,
                okEvt -> {
                    Color newColor = chooser.getColor();
                    colorPreviewPanel.setBackground(newColor);
                    selectedColor = newColor;
                    updateHexFromColor();
                },
                cancelEvt -> {}
        );
        dialog.setVisible(true);
    }

    private void updateColorFromHex() {
        String text = colorHexField.getText().trim();
        try {
            selectedColor = Color.decode(text);
            colorPreviewPanel.setBackground(selectedColor);
        } catch (NumberFormatException e) {
            updateHexFromColor();
        }
    }

    private void updateHexFromColor() {
        colorHexField.setText(String.format("#%06X", (0xFFFFFF & selectedColor.getRGB())));
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

    enum UserNumberOfChannels {
        GRAYSCALE(1, "1 (grayscale)"),
        RGB(3, "3 (Color RGB)"),
        RGBA(4, "3 (Color RGB + Alpha)");

        private final int numberOfChannels;
        private final String name;

        UserNumberOfChannels(int numberOfChannels, String name) {
            this.numberOfChannels = numberOfChannels;
            this.name = name;
        }

        public int numberOfChannels() {
            return numberOfChannels;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
