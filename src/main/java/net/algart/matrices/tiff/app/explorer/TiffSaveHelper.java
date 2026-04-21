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

import net.algart.matrices.tiff.TiffCopier;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.app.TiffInfo;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

class TiffSaveHelper {
    private enum UserByteOrder {
        BIG_ENDIAN(ByteOrder.BIG_ENDIAN, "Big-endian"),
        LITTLE_ENDIAN(ByteOrder.LITTLE_ENDIAN, "Little-endian");

        private final ByteOrder byteOrder;
        private final String name;

        UserByteOrder(ByteOrder byteOrder, String name) {
            this.byteOrder = byteOrder;
            this.name = name;
        }

        public static UserByteOrder ofByteOrder(ByteOrder byteOrder) {
            Objects.requireNonNull(byteOrder, "Null byte order");
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                return BIG_ENDIAN;
            } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                return LITTLE_ENDIAN;
            } else {
                throw new IllegalArgumentException("Unknown byte order: " + byteOrder);
            }
        }

        public ByteOrder byteOrder() {
            return byteOrder;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final String PREF_LAST_SAVE_TIFF_DIR = "viewer.copier.lastSaveTiffDirectory";

    private final JFrame frame;
    private final TiffExplorer explorer;

    private volatile boolean copyingInProgress = false;
    private volatile boolean stopRequested = false;

    private JDialog settingsDialog;
    private JComboBox<UserByteOrder> byteOrderComboBox;
    private JCheckBox bigTiffCheckBox;
    private JLabel copyProgressLabel;
    private JButton startCopyButton;
    private JButton cancelCopyButton;

    public TiffSaveHelper(JTiffExplorerFrame frame) {
        this.frame = Objects.requireNonNull(frame);
        this.explorer = frame.explorer();
    }

    public Path chooseTiffFileToSave() {
        if (!explorer.isInitialized()) {
            return null;
        }
        JFileChooser chooser = TinySwing.newFileChooser();
        String last = TiffExplorer.PREFERENCES.get(PREF_LAST_SAVE_TIFF_DIR, null);
        File dir = new File(last == null ? "." : last);
        if (dir.isDirectory()) {
            chooser.setCurrentDirectory(dir);
        }
        chooser.setDialogTitle("Save the entire TIFF");
        chooser.setSelectedFile(new File("copy.tiff"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.addChoosableFileFilter(TiffExplorer.TIFF_FILTER);
        chooser.setFileFilter(TiffExplorer.TIFF_FILTER);
        chooser.setAcceptAllFileFilterUsed(true);
        File file = TinySwing.chooseFile(frame, chooser);
        if (file == null) {
            return null;
        }
        TiffExplorer.PREFERENCES.put(PREF_LAST_SAVE_TIFF_DIR, file.getParent());
        return file.toPath();
    }

    public void showSaveTiffDialog(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "Null targetFile");
        final Path tiffFile = explorer.getTiffFile();
        TiffInfo info = explorer.getInfo();
        if (tiffFile == null) {
            return;
        }
        TiffCopier.checkDifferentFiles(tiffFile, targetFile);

        settingsDialog = new JDialog(frame);
        settingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        settingsDialog.setTitle("Save a copy of the entire TIFF");
        settingsDialog.setLayout(new BorderLayout(10, 10));
        settingsDialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mainPanel.add(TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                The entire TIFF from the file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                will be copied to a new TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                &nbsp;<br>
                This operation will copy all IFD structures and all images of this TIFF file &mdash;
                image by image,<br>
                tile by tile (for tiled images) &mdash; into a new file.<br>
                This helps to eliminate unused space and fragmentation, like the command<br>
                &nbsp;&nbsp;&nbsp;&nbsp;File \u25B8 Compact TIFF...<br>
                To save only the current image, click "Show image" and use<br>
                &nbsp;&nbsp;&nbsp;&nbsp;File \u25B8 Save image as TIFF...<br>
                in the opened window.
                """.formatted(
                tiffFile.toAbsolutePath(), targetFile.toAbsolutePath()
        ))));
        mainPanel.add(Box.createVerticalStrut(10));

        final JPanel settingsPanel = new JPanel();
        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));
        addTitledBorder(settingsPanel, "TIFF file settings");
        settingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        final JPanel oneRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        oneRowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        oneRowPanel.add(new JLabel("Byte order: "));
        byteOrderComboBox = new JComboBox<>(UserByteOrder.values());
        byteOrderComboBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        ByteOrder byteOrder = info.byteOrder();
        if (byteOrder != null) {
            byteOrderComboBox.setSelectedItem(UserByteOrder.ofByteOrder(byteOrder));
        }
        byteOrderComboBox.setMaximumSize(byteOrderComboBox.getPreferredSize());
        oneRowPanel.add(byteOrderComboBox);
        oneRowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, oneRowPanel.getPreferredSize().height));
        settingsPanel.add(oneRowPanel);
        settingsPanel.add(Box.createVerticalStrut(5));

        bigTiffCheckBox = new JCheckBox("Big-TIFF (necessary for large files >4 GB)");
        bigTiffCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        bigTiffCheckBox.setSelected(info.isBigTiff());
        settingsPanel.add(bigTiffCheckBox);
        if (info.tiffFileLength() > 1024L * 1024L * 1024L) {
            settingsPanel.add(Box.createVerticalStrut(5));
            settingsPanel.add(TinySwing.leftLabel(
                    String.format(Locale.US,
                            TinySwing.smartHtmlLines(
                                    "The TIFF file is very large (<b>%.3f GB</b>, &gt;1 GB)!<br>" +
                                            "We recommend using Big-TIFF, allowing to store &gt;4 GB of data."),
                            (double) info.tiffFileLength() / (double) (1024L * 1024L * 1024L))));
        }
        mainPanel.add(settingsPanel);

        mainPanel.add(Box.createVerticalStrut(10));
        copyProgressLabel = TinySwing.leftLabel("999/999 tiles copied...");
        mainPanel.add(copyProgressLabel);
        settingsDialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startCopyButton = new JButton();
        cancelCopyButton = new JButton();
        initializeButtons();
        buttonPanel.add(startCopyButton);
        buttonPanel.add(cancelCopyButton);
        settingsDialog.add(buttonPanel, BorderLayout.SOUTH);
        settingsDialog.getRootPane().setDefaultButton(startCopyButton);
        cancelCopyButton.addActionListener(e -> cancelCopy());
        startCopyButton.addActionListener(e -> startCopy(tiffFile, targetFile));

        stopRequested = false;
        copyingInProgress = false;
        settingsDialog.pack();

        TinySwing.addCloseOnEscape(settingsDialog);
        copyProgressLabel.setText("");
        settingsDialog.setLocationRelativeTo(frame);
        settingsDialog.setVisible(true);
    }

    public void showCompactDialog() {
        final Path tiffFile = explorer.getTiffFile();
        if (tiffFile == null) {
            return;
        }
        settingsDialog = new JDialog(frame);
        settingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        settingsDialog.setTitle("Compact TIFF file " + tiffFile);
        settingsDialog.setLayout(new BorderLayout(10, 10));
        settingsDialog.setResizable(false);

        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        content.add(TinySwing.leftLabel(TinySwing.smartHtmlLines("""
                Compacting the TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                &nbsp;<br>
                This operation will copy all IFD structures and all images of this TIFF file &mdash;
                image by image,<br>
                tile by tile (for tiled images) &mdash; into a temporary file and
                then rewrite the original file with this copy.<br>
                This helps to eliminate unused space and fragmentation,
                providing more efficient access.<br>
                You may create a backup copy if the file is important.
                """.formatted(tiffFile))));
        content.add(Box.createVerticalStrut(10));

        copyProgressLabel = TinySwing.leftLabel("999/999 tiles copied...");
        content.add(copyProgressLabel);
        settingsDialog.add(content, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startCopyButton = new JButton();
        cancelCopyButton = new JButton();
        initializeButtons();
        buttonPanel.add(startCopyButton);
        buttonPanel.add(cancelCopyButton);
        settingsDialog.add(buttonPanel, BorderLayout.SOUTH);
        settingsDialog.getRootPane().setDefaultButton(startCopyButton);
        cancelCopyButton.addActionListener(e -> cancelCopy());
        startCopyButton.addActionListener(e -> startCompact(tiffFile));

        stopRequested = false;
        copyingInProgress = false;
        settingsDialog.pack();

        TinySwing.addCloseOnEscape(settingsDialog);
        copyProgressLabel.setText("");
        settingsDialog.setLocationRelativeTo(frame);
        settingsDialog.setVisible(true);
    }

    private void startCopy(Path sourceFile, Path targetFile) {
        startCopyOperation(copier -> {
            try (TiffReader reader = new TiffReader(sourceFile);
                 TiffWriter writer = new TiffWriter(targetFile)) {
                writer.setBigTiff(bigTiffCheckBox.isSelected());
                final UserByteOrder selected = byteOrderComboBox.getItemAt(byteOrderComboBox.getSelectedIndex());
                writer.setByteOrder(selected.byteOrder());
                copier.copyEntireTiff(writer, reader, false);
            }
        }, "copying", false);
    }

    private void startCompact(Path tiffFile) {
        startCopyOperation(copier -> copier.compact(tiffFile), "compacting", true);
    }

    private void startCopyOperation(CopyOperation operation, String name, boolean reloadOnSuccess) {
        final TiffCopier copier = buildCopier();
        try {
            stopRequested = false;
            startCopyButton.setEnabled(false);
            startCopyButton.setVisible(false);
            cancelCopyButton.setText("Cancel " + name);
            settingsDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            copyingInProgress = true;
        } catch (Exception e) {
            TinySwing.showErrorMessage(frame, e, "Error " + name + " TIFF");
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
//                if (true) throw new IOException("Test exception " + tiffFile);
                operation.perform(copier);
                return null;
            }

            @Override
            protected void done() {
                boolean successful = false;
                try {
                    get();
                    successful = !copier.isCancelled();
                } catch (InterruptedException | ExecutionException e) {
                    TinySwing.showErrorMessage(frame, e, "Error " + name + " TIFF");
                }
                copyingInProgress = false;
                settingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                if (successful) {
                    settingsDialog.dispose();
                    if (reloadOnSuccess) {
                        explorer.reload();
                    }
                } else {
                    initializeButtons();
                }
            }
        }.execute();
    }

    private void cancelCopy() {
        if (copyingInProgress) {
            stopRequested = true;
        } else {
            settingsDialog.dispose();
        }
    }

    private void initializeButtons() {
        startCopyButton.setText("Start");
        cancelCopyButton.setText("Cancel");
        startCopyButton.setEnabled(true);
        startCopyButton.setVisible(true);
    }

    private TiffCopier buildCopier() {
        TiffCopier copier = new TiffCopier();
        copier.setInterruptionChecker(() -> stopRequested);
        copier.setProgressUpdater(this::updateProgress, 500);
        return copier;
    }

    static void addTitledBorder(JPanel settingsPanel, String title) {
        final TitledBorder titledBorder = BorderFactory.createTitledBorder(title);
        final Border padding = BorderFactory.createEmptyBorder(5, 10, 10, 10);
        titledBorder.setTitleJustification(TitledBorder.CENTER);
        settingsPanel.setBorder(BorderFactory.createCompoundBorder(titledBorder, padding));
    }

    private void updateProgress(TiffCopier.ProgressInformation p) {
        SwingUtilities.invokeLater(() ->
                copyProgressLabel.setText(!p.isCopyingTemporaryFile() ?
                        "Image %d/%d, tile %d/%d...".formatted(
                                p.imageIndex() + 1, p.imageCount(),
                                p.tileIndex() + 1, p.tileCount()) :
                        p.isCompactingInMemory() ?
                                "Rewriting the TIFF file..." :
                                "Copying temporary file..."));
//        if (p.isCopyingTemporaryFile()) {
//            try {
//                Thread.currentThread().sleep(1000);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//        }
    }

    @FunctionalInterface
    private interface CopyOperation {
        void perform(TiffCopier copier) throws Exception;
    }
}
