package net.algart.matrices.tiff.executable;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class TiffInfoGUI {
    private static final int MAX_IMAGE_DIM = 10000;

    private JFrame frame;
    private JComboBox<String> ifdComboBox;
    private JTextArea textArea;
    private JButton showImageButton;
    private JButton openFileButton;
    private JComboBox<TiffIFD.StringFormat> formatComboBox;

    private TiffInfo info = null;
    private Path tiffFile;
    private TiffIFD.StringFormat stringFormat = TiffIFD.StringFormat.NORMAL;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TiffInfoGUI().createGUI());
    }

    private void createGUI() {
        frame = new JFrame("TIFF Inspector");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // Top panel: Open button + format ComboBox + IFD ComboBox + Show Image button
        JPanel topPanel = new JPanel(new FlowLayout());

        openFileButton = new JButton("Open TIFF");
        openFileButton.addActionListener(e -> chooseAndOpenFile());
        topPanel.add(openFileButton);

        formatComboBox = new JComboBox<>(TiffIFD.StringFormat.values());
        formatComboBox.setSelectedItem(stringFormat);
        formatComboBox.addActionListener(e -> {
            stringFormat = (TiffIFD.StringFormat) formatComboBox.getSelectedItem();
            updateTextArea();
        });
        topPanel.add(new JLabel("Format:"));
        topPanel.add(formatComboBox);

        ifdComboBox = new JComboBox<>();
        ifdComboBox.addActionListener(e -> updateTextArea());
        topPanel.add(new JLabel("Select IFD:"));
        topPanel.add(ifdComboBox);

        showImageButton = new JButton("Show Image");
        showImageButton.addActionListener(e -> showImageWindow());
        topPanel.add(showImageButton);

        frame.add(topPanel, BorderLayout.NORTH);

        // Center: Text area with scroll, line wrap enabled
        textArea = new JTextArea(30, 80);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(textArea);
        frame.add(scrollPane, BorderLayout.CENTER);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void chooseAndOpenFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select a TIFF file");
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file != null) {
                loadTiff(file.toPath());
            }
        }
    }

    private void loadTiff(Path file) {
        this.tiffFile = file;
        reload();
    }

    private void reload() {
        ifdComboBox.removeAllItems();
        textArea.setText("");
        TiffInfo info = new TiffInfo();
        try {
            info.collectTiffInfo(tiffFile);
            for (int i = 0; i < info.ifdInfo.size(); i++) {
                ifdComboBox.addItem("IFD #" + i);
            }
            if (!info.ifdInfo.isEmpty()) {
                ifdComboBox.setSelectedIndex(0);
                updateTextArea();
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Error reading TIFF: " + e.getMessage());
        }
    }

    private void updateTextArea() {
        int index = ifdComboBox.getSelectedIndex();
        if (index >= 0 && info != null && index < info.ifdCount()) {
            textArea.setText(info.ifdInformation(index));
        }
    }

    private void showImageWindow() {
        int index = ifdComboBox.getSelectedIndex();
        if (index < 0 || info == null || index >= info.ifdCount()) {
            return;
        }
        try (TiffReader reader = new TiffReader(tiffFile)) {
            final int dimX = reader.dimX(index);
            final int dimY = reader.dimY(index);
            if (dimX > MAX_IMAGE_DIM || dimY > MAX_IMAGE_DIM) {
                JOptionPane.showMessageDialog(frame,
                        ("Image too large to display: %dx%d%n" +
                                "Maximal image sizes that can be displayed are %dx%d")
                                .formatted(dimX, dimY, MAX_IMAGE_DIM, MAX_IMAGE_DIM));
                return;
            }
            BufferedImage bi = reader.readBufferedImage(index);
            JFrame imgFrame = new JFrame("IFD Image #" + index);
            imgFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            imgFrame.add(new JScrollPane(new JLabel(new ImageIcon(bi))));
            imgFrame.pack();
            imgFrame.setLocationRelativeTo(frame);
            imgFrame.setVisible(true);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Cannot read image: " + e.getMessage());
        }
    }
}
