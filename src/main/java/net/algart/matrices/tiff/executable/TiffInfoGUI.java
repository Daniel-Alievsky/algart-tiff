package net.algart.matrices.tiff.executable;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class TiffInfoGUI {
    private JFrame frame;
    private JComboBox<String> ifdComboBox;
    private JTextArea textArea;
    private JButton showImageButton;
    private JButton openFileButton;
    private JComboBox<TiffIFD.StringFormat> formatComboBox;

    private List<TiffIFD> ifds;
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
        ifdComboBox.removeAllItems();
        textArea.setText("");
        try (TiffReader reader = new TiffReader(tiffFile, TiffOpenMode.ALLOW_NON_TIFF)) {
            if (!reader.isTiff()) {
                JOptionPane.showMessageDialog(frame, "File is not a valid TIFF: " + tiffFile);
                return;
            }
            ifds = reader.allIFDs();
            for (int i = 0; i < ifds.size(); i++) {
                ifdComboBox.addItem("IFD #" + i);
            }
            if (!ifds.isEmpty()) {
                ifdComboBox.setSelectedIndex(0);
                updateTextArea();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error reading TIFF: " + ex.getMessage());
        }
    }

    private void updateTextArea() {
        int index = ifdComboBox.getSelectedIndex();
        if (index >= 0 && ifds != null && index < ifds.size()) {
            TiffIFD ifd = ifds.get(index);
            textArea.setText(ifd.toString(stringFormat));
        }
    }

    private void showImageWindow() {
        // Пока закомментировано
    }
}
