/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.matrices.tiff.executable;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.pyramids.TiffPyramidImageSet;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
    public static final String ALGART_TIFF_WEBSITE = "https://algart.net/java/AlgART-TIFF/";

    private static final boolean DEFAULT_WORD_WRAP = false;
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final int[] FONT_SIZES = {11, DEFAULT_FONT_SIZE, 18, 22};

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

        @Override
        public String toString() {
            return caption;
        }
    }

    private static final int MAX_IMAGE_DIM = 16000;
    // 16000 * 16000 * 4 channels RGBA * 16 bit/channel < Integer.MAX_VALUE
    private static final Color COMMON_BACKGROUND = new Color(240, 240, 240);
    private static final Color ERROR_BACKGROUND = new Color(255, 255, 155);
    private static final Color SVS_FOREGROUND = new Color(0, 128, 0);

    private static final String PREF_LAST_DIR = "lastDirectory";
    private static final String PREF_WINDOW_X = "windowX";
    private static final String PREF_WINDOW_Y = "windowY";
    private static final String PREF_WINDOW_WIDTH = "windowWidth";
    private static final String PREF_WINDOW_HEIGHT = "windowHeight";

    static final System.Logger LOG = System.getLogger(TiffInfoViewer.class.getName());

    private final Preferences prefs = Preferences.userNodeForPackage(TiffInfoViewer.class);

    private JFrame frame;
    private JComboBox<String> ifdComboBox;
    private JTextArea ifdTextArea;
    private JTextArea summaryInfoTextArea;
    private JTextArea svsInfoTextArea;

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
        SwingUtilities.invokeLater(() -> {
            try {
                new TiffInfoViewer().createGUI(args);
            } catch (Throwable e) {
                JOptionPane.showMessageDialog(null,
                        e.toString(), "Error", JOptionPane.ERROR_MESSAGE);
                LOG.log(System.Logger.Level.ERROR, "Error while creating GUI", e);
            }
        });
    }

    private void createGUI(String[] args) {
        frame = new JFrame("TIFF Information Viewer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setJMenuBar(buildMenuBar());

        JPanel topPanel = new JPanel(new BorderLayout());
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        frame.setIconImages(java.util.List.of(
                new ImageIcon(reqResource("icon16.png")).getImage(),
                new ImageIcon(reqResource("icon32.png")).getImage()));

        JButton openFileButton = new JButton("Open TIFF");
        openFileButton.addActionListener(e -> chooseAndOpenFile());
        leftPanel.add(openFileButton);

//        addFormatComboBox(leftPanel);
// - deprecated solution (replacing with the menu)

        addIFDComboBox(leftPanel);
        addShowImageButton(leftPanel);
        topPanel.add(leftPanel, BorderLayout.WEST);
        frame.add(topPanel, BorderLayout.NORTH);

        addBottomInfoTextArea();
        addIFDTextArea();

        frame.pack();
        final Dimension frameSize = frame.getPreferredSize();
        frame.setMinimumSize(new Dimension(
                frameSize.width, frameSize.height - ifdTextArea.getPreferredSize().height + 32));
        // - the user cannot reduce window size too much, so that elements become invisible
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

    private void addIFDTextArea() {
        JPanel textPanel = new JPanel(new BorderLayout());
        textPanel.setBackground(COMMON_BACKGROUND);
        textPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        ifdTextArea = new JTextArea(10, 80);
        ifdTextArea.setFont(getPreferredMonoFont(DEFAULT_FONT_SIZE));
        ifdTextArea.setEditable(false);
        ifdTextArea.setLineWrap(DEFAULT_WORD_WRAP);
        ifdTextArea.setWrapStyleWord(true);
        ifdTextArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        ifdTextArea.setBackground(Color.WHITE);
        ifdTextArea.setForeground(new Color(20, 20, 20));
        ifdTextArea.setOpaque(true);
        JScrollPane scrollPane = new JScrollPane(ifdTextArea,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        textPanel.add(scrollPane, BorderLayout.CENTER);
        frame.add(textPanel, BorderLayout.CENTER);
    }

    private void addBottomInfoTextArea() {
        summaryInfoTextArea = new JTextArea(2, 80);
        summaryInfoTextArea.setEditable(false);
        summaryInfoTextArea.setLineWrap(DEFAULT_WORD_WRAP);
        summaryInfoTextArea.setWrapStyleWord(true);
        summaryInfoTextArea.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        summaryInfoTextArea.setFont(getPreferredMonoFont(DEFAULT_FONT_SIZE));
        summaryInfoTextArea.setBackground(COMMON_BACKGROUND);
        summaryInfoTextArea.setForeground(new Color(20, 20, 20));
        summaryInfoTextArea.setOpaque(true);
        svsInfoTextArea = new JTextArea(2, 80);
        svsInfoTextArea.setEditable(false);
        svsInfoTextArea.setLineWrap(DEFAULT_WORD_WRAP);
        svsInfoTextArea.setWrapStyleWord(true);
        svsInfoTextArea.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        svsInfoTextArea.setFont(getPreferredMonoFont(DEFAULT_FONT_SIZE));
        svsInfoTextArea.setBackground(COMMON_BACKGROUND);
        svsInfoTextArea.setForeground(SVS_FOREGROUND);
        svsInfoTextArea.setOpaque(true);
        svsInfoTextArea.setVisible(false);
        // - invisible by default
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.add(summaryInfoTextArea);
        infoPanel.add(svsInfoTextArea);
        frame.add(infoPanel, BorderLayout.SOUTH);
    }

    private void addShowImageButton(JPanel leftPanel) {
        JButton showImageButton = new JButton("Show image");
        showImageButton.addActionListener(e -> showImageWindow());
        leftPanel.add(Box.createHorizontalStrut(10));
        leftPanel.add(showImageButton);
    }

    private void addIFDComboBox(JPanel leftPanel) {
        ifdComboBox = new JComboBox<>();
        ifdComboBox.setMaximumRowCount(16);
        ifdComboBox.addActionListener(e -> updateTextArea());
        ifdComboBox.setPrototypeDisplayValue("Image #999");
        leftPanel.add(Box.createHorizontalStrut(10));
        leftPanel.add(new JLabel("Select TIFF image (IFD):"));
        leftPanel.add(ifdComboBox);
    }

    // Deprecated
    private void addFormatComboBox(JPanel leftPanel) {
        JComboBox<ViewMode> formatComboBox = new JComboBox<>(ViewMode.values());
        formatComboBox.setSelectedItem(stringFormat);
        formatComboBox.addActionListener(e -> {
            ViewMode selectedItem = (ViewMode) formatComboBox.getSelectedItem();
            if (selectedItem != null) {
                stringFormat = selectedItem.stringFormat;
                reload();
                updateTextArea();
            }
        });
        leftPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        leftPanel.add(new JLabel("View mode:"));
        leftPanel.add(formatComboBox);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem openItem = new JMenuItem("Open TIFF...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> chooseAndOpenFile());
        fileMenu.add(openItem);
        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.ALT_DOWN_MASK));
        exitItem.addActionListener(e ->
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        fileMenu.add(exitItem);

        JMenu editMenu = new JMenu("Edit");
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> ifdTextArea.copy());
        editMenu.add(copyItem);

        JMenu viewMenu = new JMenu("View");
        JMenu viewModeMenu = new JMenu("View mode");
        ButtonGroup viewModeGroup = new ButtonGroup();

        for (final ViewMode viewMode : ViewMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(viewMode.toString());
            if (viewMode.stringFormat == stringFormat) {
                item.setSelected(true);
            }
            item.addActionListener(e -> {
                stringFormat = viewMode.stringFormat;
                reload();
                updateTextArea();
            });

            viewModeGroup.add(item);
            viewModeMenu.add(item);
        }
        viewMenu.add(viewModeMenu);
        JMenu fontSizeMenu = new JMenu("Font size");
        ButtonGroup fontSizeGroup = new ButtonGroup();
        for (int size : FONT_SIZES) {
            JRadioButtonMenuItem sizeItem = new JRadioButtonMenuItem(size + " pt");
            if (size == DEFAULT_FONT_SIZE) {
                sizeItem.setSelected(true);
            }
            sizeItem.addActionListener(e -> setTextAreasFontSize(size));
            fontSizeGroup.add(sizeItem);
            fontSizeMenu.add(sizeItem);
        }
        viewMenu.add(fontSizeMenu);
        JCheckBoxMenuItem wrapItem = new JCheckBoxMenuItem("Word wrap");
        wrapItem.setSelected(DEFAULT_WORD_WRAP);
        wrapItem.addActionListener(e -> {
            boolean wrap = wrapItem.isSelected();
            ifdTextArea.setLineWrap(wrap);
            summaryInfoTextArea.setLineWrap(wrap);
            svsInfoTextArea.setLineWrap(wrap);
        });
        viewMenu.add(wrapItem);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);

        fileMenu.setMnemonic('F');
        editMenu.setMnemonic('E');
        viewMenu.setMnemonic('V');
        helpMenu.setMnemonic('H');
        fixMenuItemMargins(fileMenu);
        fixMenuItemMargins(editMenu);
        fixMenuItemMargins(viewMenu);
        fixMenuItemMargins(helpMenu);
        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    private void fixMenuItemMargins(JMenu menu) {
        // No good ideas how to remove the left gap added on Windows...
//        for (int i = 0; i < menu.getMenuComponentCount(); i++) {
//            Component comp = menu.getMenuComponent(i);
//            if (comp instanceof JMenuItem item && !(item instanceof JRadioButtonMenuItem)) {
//                item.setMargin(new Insets(0, -10, 0, 0));
//                System.out.println(item.getText() + " " + item.getMargin());
//            }
//        }
    }

    private void setTextAreasFontSize(int size) {
        final Font mono = getPreferredMonoFont(size);
        ifdTextArea.setFont(mono);
        summaryInfoTextArea.setFont(mono);
        svsInfoTextArea.setFont(mono);
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
                new FileNameExtensionFilter(
                        "TIFF / SVS files (*.tif, *.tiff, *.svs)", "tif", "tiff", "svs");
        javax.swing.filechooser.FileFilter svsFilter =
                new FileNameExtensionFilter("SVS files only (*.svs)", "svs");
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
            summaryInfoTextArea.setText(info.prefixInfo() + "\n" + info.summaryInfo());
            summaryInfoTextArea.setBackground(info.isTiff() ? COMMON_BACKGROUND : ERROR_BACKGROUND);
            summaryInfoTextArea.setCaretPosition(0);
            svsInfoTextArea.setText(info.svsInfo());
            svsInfoTextArea.setVisible(info.pyramid().isSVS());
            svsInfoTextArea.setCaretPosition(0);
            TiffPyramidImageSet imageSet = info.pyramid().pyramidImageSet();
            assert imageSet != null;
            String longest = "";
            for (int i = 0; i < info.numberOfImages(); i++) {
                final StringBuilder sb = new StringBuilder("Image #" + i);
                final int layer = imageSet.imageToLayer(i);
                if (layer >= 0) {
                    sb.append(" (layer ").append(layer).append(")");
                }
                for (var kind : TiffPyramidImageSet.SpecialKind.values()) {
                    if (imageSet.specialKindIndex(kind) == i) {
                        sb.append(" (").append(kind.kindName()).append(")");
                    }
                }
                final String caption = sb.toString();
                ifdComboBox.addItem(caption);
                if (sb.length() > longest.length()) {
                    longest = caption;
                }
            }
            if (info.numberOfImages() > 0) {
                ifdComboBox.setSelectedIndex(0);
                updateTextArea();
            }
            ifdComboBox.setPrototypeDisplayValue(longest);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Error reading TIFF", e);
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Error reading TIFF", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateTextArea() {
        int index = ifdComboBox.getSelectedIndex();
        if (index >= 0 && info != null && index < info.numberOfImages()) {
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
        if (index < 0 || info == null || index >= info.numberOfImages()) {
            return;
        }
        try (TiffReader reader = new TiffReader(tiffFile)) {
            TiffReadMap map = reader.map(index);
            int dimX = map.dimX();
            int dimY = map.dimY();
            if (dimX > MAX_IMAGE_DIM || dimY > MAX_IMAGE_DIM) {
                dimX = Math.min(dimX, MAX_IMAGE_DIM);
                dimY = Math.min(dimY, MAX_IMAGE_DIM);
                int choice = JOptionPane.showConfirmDialog(
                        frame,
                        (
                                "The image is too large to display: %d×%d pixels.%n" +
                                        "We will show only its part %d×%d.%n" +
                                        "Do you want to continue?"
                        ).formatted(map.dimX(), map.dimY(), dimX, dimY),
                        "Large Image Warning",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            BufferedImage bi = map.readBufferedImage(0, 0, dimX, dimY);
            JFrame imgFrame = new JFrame("TIFF Image #" + index + " from " + info.numberOfImages() +
                    " images (" + dimX + "x" + dimY +
                    (dimX == map.dimX() && dimY == map.dimY() ?
                            "" :
                            " from " + map.dimX() + "x" + map.dimY()) +
                    ")");
            imgFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            imgFrame.add(new JScrollPane(new JLabel(new ImageIcon(bi))));
            imgFrame.pack();

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Dimension frameSize = imgFrame.getSize();
            frameSize.width = Math.min(frameSize.width, screenSize.width - 10);
            frameSize.height = Math.min(frameSize.height, screenSize.height - 50);
            // - ensure that scroll bar and other elements will be visible even for very large images
            imgFrame.setSize(frameSize);

            imgFrame.setLocationRelativeTo(frame);
            imgFrame.setVisible(true);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    frame, e.getMessage(), "Error reading TIFF image", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showAboutDialog() {
        JDialog dialog = new JDialog(frame, "About TIFF Info Viewer", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel icon = new JLabel(new ImageIcon(reqResource("icon32.png")));
        content.add(icon);
        content.add(Box.createVerticalStrut(16));

        JLabel title = new JLabel("AlgART TIFF Information Viewer");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        content.add(title);
        content.add(Box.createVerticalStrut(8));

        content.add(new JLabel("By Daniel Alievsky"));
        content.add(new JLabel("Version 1.5.1"));
        content.add(Box.createVerticalStrut(8));

        JLabel link = new JLabel("<html><a href=\"\">" + ALGART_TIFF_WEBSITE + "</a></html>");
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
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private static Font getPreferredMonoFont(int size) {
        String preferred = "Consolas";
        if (isFontAvailable(preferred)) {
            return new Font(preferred, Font.PLAIN, size);
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    private static boolean isFontAvailable(String fontName) {
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String f : fonts) {
            if (f.equalsIgnoreCase(fontName)) {
                return true;
            }
        }
        return false;
    }

    private static URL reqResource(String name) {
        final URL result = TiffInfoViewer.class.getResource(name);
        Objects.requireNonNull(result, "Resource " + name + " not found");
        return result;
    }
}
