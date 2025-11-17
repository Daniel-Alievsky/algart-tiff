package net.algart.matrices.tiff.executable;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;
import java.util.prefs.Preferences;

public class TiffInfoViewer {
    public enum ViewMode {
        BRIEF(TiffIFD.StringFormat.BRIEF, "Brief"),
        NORMAL(TiffIFD.StringFormat.NORMAL, "Normal"),
        NORMAL_SORTED(TiffIFD.StringFormat.NORMAL_SORTED, "Normal (sorted)"),
        DETAILED(TiffIFD.StringFormat.DETAILED, "Detailed"),
        JSON(TiffIFD.StringFormat.JSON, "JSON");

        private final TiffIFD.StringFormat stringFormat;
        private final String caption;

        ViewMode(TiffIFD.StringFormat stringFormat, String caption) {
            this.stringFormat = stringFormat;
            this.caption = caption;
        }

        public String toString() {
            return caption;
        }
    }

    private static final int MAX_IMAGE_DIM = 10000;
    private static final Color COMMON_BACKGROUND = new Color(240, 240, 240);
    private static final Color ERROR_BACKGROUND = new Color(255, 255, 155);

    private static final String PREF_LAST_DIR = "lastDirectory";
    private static final String PREF_WINDOW_X = "windowX";
    private static final String PREF_WINDOW_Y = "windowY";
    private static final String PREF_WINDOW_WIDTH = "windowWidth";
    private static final String PREF_WINDOW_HEIGHT = "windowHeight";

    static final System.Logger LOG = System.getLogger(TiffInfoViewer.class.getName());

    private final Preferences prefs = Preferences.userNodeForPackage(TiffInfoViewer.class);

    private JFrame frame;
    private JComboBox<String> ifdComboBox;
    private JTextArea commonTextArea;
    private JTextArea ifdTextArea;
    private JButton showImageButton;
    private JButton openFileButton;
    private JComboBox<ViewMode> formatComboBox;

    private TiffInfo info = null;
    private Path tiffFile = null;
    private TiffIFD.StringFormat stringFormat = TiffIFD.StringFormat.NORMAL;

    public static void main(String[] args) {
        if (args.length >= 1 && args[0].equals("-h")) {
            System.out.println("Usage: java " + TiffInfoViewer.class.getName() + " [some_file.tiff]");
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            // For cross-platform:
            // UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot set look and feel", e);
        }
        SwingUtilities.invokeLater(() -> new TiffInfoViewer().createGUI(args));
    }

    private void createGUI(String[] args) {
        frame = new JFrame("TIFF Inspector");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        frame.add(topPanel, BorderLayout.NORTH);
        frame.setIconImages(java.util.List.of(
                new ImageIcon(reqResource("icon16.png")).getImage(),
                new ImageIcon(reqResource("icon32.png")).getImage()));

        openFileButton = new JButton("Open TIFF");
        openFileButton.addActionListener(e -> chooseAndOpenFile());
        topPanel.add(openFileButton);

        formatComboBox = new JComboBox<>(ViewMode.values());
        formatComboBox.setSelectedItem(stringFormat);
        formatComboBox.addActionListener(e -> {
            ViewMode selectedItem = (ViewMode) formatComboBox.getSelectedItem();
            if (selectedItem != null) {
                stringFormat = selectedItem.stringFormat;
                reload();
                updateTextArea();
            }
        });
        topPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        topPanel.add(new JLabel("View mode:"));
        topPanel.add(formatComboBox);

        ifdComboBox = new JComboBox<>();
        ifdComboBox.addActionListener(e -> updateTextArea());
        ifdComboBox.setPrototypeDisplayValue("999999");
        topPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        topPanel.add(new JLabel("Select TIFF image (IFD):"));
        topPanel.add(ifdComboBox);

        showImageButton = new JButton("Show Image");
        showImageButton.addActionListener(e -> showImageWindow());
        topPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        topPanel.add(showImageButton);
        frame.add(topPanel, BorderLayout.NORTH);

        commonTextArea = new JTextArea(2, 80);
        commonTextArea.setEditable(false);
        commonTextArea.setLineWrap(true);
        commonTextArea.setWrapStyleWord(true);
        commonTextArea.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        commonTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        commonTextArea.setBackground(COMMON_BACKGROUND);
        commonTextArea.setForeground(new Color(20, 20, 20));

        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.add(commonTextArea, BorderLayout.CENTER);
        frame.add(infoPanel, BorderLayout.SOUTH);

        JPanel textPanel = new JPanel(new BorderLayout());
        textPanel.setBackground(COMMON_BACKGROUND);
        textPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        ifdTextArea = new JTextArea(30, 80);
        ifdTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        ifdTextArea.setEditable(false);
        ifdTextArea.setLineWrap(true);
        ifdTextArea.setWrapStyleWord(true);
        ifdTextArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        ifdTextArea.setBackground(Color.WHITE);
        ifdTextArea.setForeground(new Color(20, 20, 20));
        JScrollPane scrollPane = new JScrollPane(ifdTextArea,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        frame.add(scrollPane, BorderLayout.CENTER);
        textPanel.add(scrollPane, BorderLayout.CENTER);
        frame.add(textPanel, BorderLayout.CENTER);

        frame.pack();
        frame.setLocationRelativeTo(null);
        loadPreferences();
        frame.setVisible(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                savePreferences();
            }
        });
        if (args.length >= 1) {
            loadTiff(Path.of(args[0]));
        }
    }

    private void chooseAndOpenFile() {
        JFileChooser chooser = new JFileChooser();
        String last = prefs.get(PREF_LAST_DIR, null);
        if (last != null) {
            File dir = new File(last);
            if (dir.exists() && dir.isDirectory()) {
                chooser.setCurrentDirectory(dir);
            }
        }
        javax.swing.filechooser.FileFilter tiffFilter =
                new FileNameExtensionFilter("TIFF files (*.tif, *.tiff)", "tif", "tiff");
        javax.swing.filechooser.FileFilter svsFilter =
                new FileNameExtensionFilter("SVS files (*.svs)", "svs");
        chooser.addChoosableFileFilter(tiffFilter);
        chooser.addChoosableFileFilter(svsFilter);
        chooser.setAcceptAllFileFilterUsed(true);

        chooser.setFileFilter(tiffFilter);
        chooser.setDialogTitle("Select a TIFF file");
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file != null) {
                prefs.put(PREF_LAST_DIR, file.getParent());
                loadTiff(file.toPath());
            }
        }
    }

    private void loadTiff(Path file) {
        this.tiffFile = file;
        reload();
    }

    private void reload() {
        if (tiffFile == null) {
            return;
        }
        ifdComboBox.removeAllItems();
        ifdTextArea.setText("");
        info = new TiffInfo();
        info.setDisableAppendingForStrictFormats(true);
        info.setStringFormat(stringFormat);
        try {
            info.collectTiffInfo(tiffFile);
            commonTextArea.setText(info.prefixInfo() + "\n" + info.totalInfo());
            commonTextArea.setBackground(info.isTiff() ? COMMON_BACKGROUND : ERROR_BACKGROUND);
            commonTextArea.setCaretPosition(0);
            final int ifdCount = info.ifdCount();
            for (int i = 0; i < ifdCount; i++) {
                ifdComboBox.addItem("#" + i);
            }
            if (ifdCount > 0) {
                ifdComboBox.setSelectedIndex(0);
                updateTextArea();
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Error reading TIFF", e);
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Error reading TIFF", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateTextArea() {
        int index = ifdComboBox.getSelectedIndex();
        if (index >= 0 && info != null && index < info.ifdCount()) {
            ifdTextArea.setText(info.ifdInformation(index));
            ifdTextArea.setCaretPosition(0);
        }
    }

    private void savePreferences() {
        Rectangle bounds = frame.getBounds();
        prefs.putInt(PREF_WINDOW_X, bounds.x);
        prefs.putInt(PREF_WINDOW_Y, bounds.y);
        prefs.putInt(PREF_WINDOW_WIDTH, bounds.width);
        prefs.putInt(PREF_WINDOW_HEIGHT, bounds.height);
    }

    private void loadPreferences() {
        final int x = prefs.getInt(PREF_WINDOW_X, Integer.MIN_VALUE);
        final int y = prefs.getInt(PREF_WINDOW_Y, Integer.MIN_VALUE);
        final int w = prefs.getInt(PREF_WINDOW_WIDTH, frame.getWidth());
        final int h = prefs.getInt(PREF_WINDOW_HEIGHT, frame.getHeight());

        frame.setSize(w, h);

        if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
            Rectangle screen = frame.getGraphicsConfiguration().getBounds();
            if (x + w <= screen.x + screen.width &&
                    y + h <= screen.y + screen.height) {
                frame.setLocation(x, y);
            } else {
                frame.setLocationRelativeTo(null);
            }
        } else {
            frame.setLocationRelativeTo(null);
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

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Dimension frameSize = imgFrame.getSize();
            frameSize.width = Math.min(frameSize.width, screenSize.width - 10);
            frameSize.height = Math.min(frameSize.height, screenSize.height - 10);
            // - ensure that scroll bar and other elements will be visible even for very large images
            imgFrame.setSize(frameSize);

            imgFrame.setLocationRelativeTo(frame);
            imgFrame.setVisible(true);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    frame, e.getMessage(), "Error reading TIFF image", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static URL reqResource(String name) {
        final URL result = TiffInfoViewer.class.getResource(name);
        Objects.requireNonNull(result, "Resource " + name + " not found");
        return result;
    }
}
