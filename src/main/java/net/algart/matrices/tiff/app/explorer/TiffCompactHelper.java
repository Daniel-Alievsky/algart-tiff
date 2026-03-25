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

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

class TiffCompactHelper {
    private final JFrame frame;
    private final TiffExplorer explorer;

    private volatile boolean compactInProgress = false;
    private volatile boolean stopCompactRequested = false;
    private JLabel compactProgressLabel;
    private JButton startCompactButton;
    private JButton cancelCompactButton;
    private JDialog compactDialog;

    public TiffCompactHelper(JTiffExplorerFrame frame) {
        this.frame = Objects.requireNonNull(frame);
        this.explorer = frame.explorer();
    }

    public void showCompactDialog() {
        final Path tiffFile = explorer.getTiffFile();
        if (tiffFile == null) {
            return;
        }
        compactDialog = new JDialog(frame);
        compactDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        compactDialog.setTitle("Compact TIFF file " + tiffFile);
        compactDialog.setLayout(new BorderLayout(10, 10));
        compactDialog.setResizable(false);

        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        content.add(TiffExplorer.leftLabel(TiffExplorer.smartHtmlLines("""
                Compacting the TIFF file:<br>
                &nbsp;&nbsp;&nbsp;&nbsp;<b>%s</b><br>
                This operation will copy all IFD structures and all the images of this TIFF file<br> 
                into a temporary file and then rewrite the original file with this copy.<br>
                This helps to eliminate unused space and fragmentation,<br>
                providing more efficient access to the TIFF data.<br>
                You may create a backup copy if the file is important.
                """.formatted(tiffFile))));
        content.add(Box.createVerticalStrut(10));

        compactProgressLabel = new JLabel("999/999 tiles copied...");
        compactProgressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(compactProgressLabel);
        compactDialog.add(content, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startCompactButton = new JButton();
        cancelCompactButton = new JButton();
        initializeButtons();
        buttonPanel.add(startCompactButton);
        buttonPanel.add(cancelCompactButton);
        compactDialog.add(buttonPanel, BorderLayout.SOUTH);
        compactDialog.getRootPane().setDefaultButton(startCompactButton);
        cancelCompactButton.addActionListener(e -> cancelCompact());
        startCompactButton.addActionListener(e -> startCompact(tiffFile));

        stopCompactRequested = false;
        compactInProgress = false;
        compactDialog.pack();

        TiffExplorer.addCloseOnEscape(compactDialog);
        compactProgressLabel.setText("");
        compactDialog.setLocationRelativeTo(frame);
        compactDialog.setVisible(true);
    }

    private void startCompact(Path tiffFile) {
        final TiffCopier copier;
        try {
            copier = buildCopier();
            stopCompactRequested = false;
            startCompactButton.setEnabled(false);
            startCompactButton.setVisible(false);
            cancelCompactButton.setText("Cancel compacting");
            compactDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            compactInProgress = true;
        } catch (Exception e) {
            TiffExplorer.showErrorMessage(frame, e, "Error compacting TIFF");
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
//                if (true) throw new IOException("Test exception " + tiffFile);
                copier.compact(tiffFile);
                return null;
            }

            @Override
            protected void done() {
                boolean successful = false;
                try {
                    get();
                    successful = !copier.isCancelled();
                } catch (InterruptedException | ExecutionException e) {
                    TiffExplorer.showErrorMessage(frame, e, "Error compacting TIFF");
                }
                compactInProgress = false;
                compactDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                if (successful) {
                    compactDialog.dispose();
                    explorer.reload();
                } else {
                    initializeButtons();
                }
            }
        }.execute();
    }

    private void cancelCompact() {
        if (compactInProgress) {
            stopCompactRequested = true;
        } else {
            compactDialog.dispose();
        }
    }

    private void initializeButtons() {
        startCompactButton.setText("Start");
        cancelCompactButton.setText("Cancel");
        startCompactButton.setEnabled(true);
        startCompactButton.setVisible(true);
    }


    private TiffCopier buildCopier() {
        TiffCopier copier = new TiffCopier();
        copier.setInterruptionChecker(() -> stopCompactRequested);
        copier.setProgressUpdater(this::updateProgress, 500);
        return copier;
    }

    private void updateProgress(TiffCopier.ProgressInformation p) {
        SwingUtilities.invokeLater(() ->
                compactProgressLabel.setText(!p.isCopyingTemporaryFile() ?
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
}
