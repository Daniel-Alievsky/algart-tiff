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

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.samples.TiffSamples;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

class TiffViewer {
    static final String DEFAULT_PIXEL_COORDINATES = pixelCoordinatesToString(0, 0);
    static final String DEFAULT_PIXEL_VALUE = "";
    static final String DEFAULT_STATUS =
            "Use mouse drag to select a rectangle, or SHIFT-drag to move the image";
    static final boolean DEFAULT_COLOR_CORRECTION = true;
    // - different from TiffReader.DEFAULT_COLOR_CORRECTION
    private static final long CACHING_MEMORY = 256 * 1048576L;
    // - 256MB = 16 * 16M is enough to store several user screens even with the zoom 1:4
    private static final boolean PRELOAD_LITTLE_AREA_WHILE_OPENING = true;
    // - should be true; you may clear this flag to debug possible I/O errors during the drawing process

    private static final System.Logger LOG = System.getLogger(TiffViewer.class.getName());

    private final TiffReaderWithGrid reader;
    private final Path path;
    private final int ifdIndex;

    private UserPixelValueFormat pixelValueFormat = UserPixelValueFormat.NONE;
    private double maxPossibleValue = 1.0;
    private double maxVisibleValue = 0.0;
    private Double rescaleFactor = null;
    private double blackOffset = 0.0;

    private TiffReadMap map = null;

    private double lastImageZoom = 0.0;
    private Rectangle lastImageRectangle = null;
    private BufferedImage lastImage = null;

    private String lastStatus = DEFAULT_STATUS;
    private long lastPixelX = 0;
    private long lastPixelY = 0;
    private String lastPixelValue = null;
    private boolean lastErrorFlag = false;

    private JTiffViewerFrame frame;

    public TiffViewer(Path path, int ifdIndex) throws IOException {
        this.path = Objects.requireNonNull(path);
        this.reader = new TiffReaderWithGrid(path);
        this.reader.setColorCorrection(DEFAULT_COLOR_CORRECTION);
        this.reader.setMaxCacheMemory(CACHING_MEMORY);
        this.ifdIndex = ifdIndex;
        LOG.log(System.Logger.Level.INFO, "Viewer opened " + reader.streamName());
    }

    public void disposeResources() {
        lastImage = null;
        try {
            this.reader.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.INFO, "Error while closing " + reader.streamName() +
                    ": " + e.getMessage(), e);
            // - no sense to show this error to the end user
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "Viewer closed " + reader.streamName());
    }

    public Path path() {
        return path;
    }

    public TiffReader reader() {
        return reader;
    }

    public int ifdIndex() {
        return ifdIndex;
    }

    public UserPixelValueFormat getPixelValueFormat() {
        return pixelValueFormat;
    }

    public TiffViewer setPixelValueFormat(UserPixelValueFormat pixelValueFormat) {
        this.pixelValueFormat = Objects.requireNonNull(pixelValueFormat, "Null pixelValueFormat");
        return this;
    }

    public double getRescaleFactor() {
        return rescaleFactor == null ? 1.0 :  rescaleFactor;
    }

    public TiffViewer setRescaleFactor(double rescaleFactor) {
        this.rescaleFactor = rescaleFactor;
        return this;
    }

    public double getBlackOffset() {
        return blackOffset;
    }

    public TiffViewer setBlackOffset(double blackOffset) {
        this.blackOffset = blackOffset;
        return this;
    }

    public boolean isRescaled() {
        return getRescaleFactor() != 1.0 || getBlackOffset() != 0.0;
    }

    public void setRescaleWhenIncreasingBitDepth(boolean rescaleWhenIncreasingBitDepth) throws IOException {
        if (rescaleWhenIncreasingBitDepth != reader.isRescaleWhenIncreasingBitDepth()) {
            reader.setRescaleWhenIncreasingBitDepth(rescaleWhenIncreasingBitDepth);
            invalidateCache();
        }
    }

    public void setColorCorrection(boolean colorCorrection) throws IOException {
        if (colorCorrection != reader.isColorCorrection()) {
            reader.setColorCorrection(colorCorrection);
            invalidateCache();
        }
    }

    public void setTileGridVisibility(boolean visible) throws IOException {
        if (visible != reader.isViewTileGrid()) {
            reader.setViewTileGrid(visible);
            invalidateCache();
        }
    }

    public void setTileGridThickness(int tileGridThickness) throws IOException {
        reader.setTileGridThickness(tileGridThickness);
        invalidateCache();
    }

    public TiffReadMap map() {
        return map;
    }

    public JFrame frame() {
        return frame;
    }

    public void show() throws IOException {
        openMap();
        createGUI();
    }

    public void resetSelectionStatus() {
        final Rectangle r = getImageSelection();
        final String status = r == null ?
                defaultStatusMessage() :
                r.width == 0 || r.height == 0 ?
                        "%d\u00D7%d: top-left (%d,%d)".formatted(
                                r.width, r.height, r.x, r.y) :
                        "%d\u00D7%d: top-left (%d,%d), bottom-right (%d,%d)".formatted(
                                r.width, r.height, r.x, r.y, r.x + r.width - 1, r.y + r.height - 1);
        showStatus(status);
    }

    public void showStatus(String status) {
        setStatus(status, false);
    }

    public void showPixelInformation(long x, long y) {
        if (x != lastPixelX || y != lastPixelY) {
            final String pixelCoordinates = pixelCoordinatesToString(x, y);
            frame.setStatusPixelCoordinates(pixelCoordinates);
            setPixelValueInformation(x, y);
            lastPixelX = x;
            lastPixelY = y;
        }
    }

    public void showPixelInformation(int x, int y, double zoom) {
        showPixelInformation(Math.round(x / zoom), Math.round(y / zoom));
    }

    public void resetPixelValueStatus() {
        setPixelValueInformation(lastPixelX, lastPixelY);
    }

    public void showError(Throwable exception) {
        String message = exception.getMessage();
        setStatus(message == null ? exception.toString() : message, true);
    }

    public String alignSelectionToTileGridCommand() {
        return map == null ?
                "Align selection to tiles" :
                "Align selection to tiles (%d\u00D7%d)".formatted(map.tileSizeX(), map.tileSizeY());
    }

    public void reload() throws IOException {
        try {
            invalidateCache();
            openMap();
        } finally {
            frame.resetImageInformation();
            frame.tiffPanel().reset();
            repaint();
        }
    }

    public void repaint() {
        frame.tiffPanel().repaint();
        resetPixelValueStatus();
    }

    public Rectangle getImageSelection() {
        return frame.tiffPanel().getImageSelection();
    }

    public void setImageSelection(int left, int top, int width, int height) {
        frame.tiffPanel().setImageSelection(
                left,
                top,
                (long) left + (long) width,
                (long) top + (long) height);
    }

    public Rectangle getImageVisibleArea() {
        Rectangle r = frame.getImageVisibleArea();
        int dimX = map.dimX();
        int dimY = map.dimY();
        if (r.x >= dimX || r.y >= dimY) {
            return null;
        }
        return new Rectangle(r.x, r.y, Math.min(r.width, dimX - r.x), Math.min(r.height, dimY - r.y));
    }

    public BufferedImage reloadFragment(int zoomedFromX, int zoomedFromY, int zoomedToX, int zoomedToY, double zoom) {
        final int zoomedSizeX = zoomedToX - zoomedFromX;
        final int zoomedSizeY = zoomedToY - zoomedFromY;
        if (zoomedSizeX <= 0 || zoomedSizeY <= 0) {
            return null;
        }
        int fromX = (int) Math.floor(zoomedFromX / zoom);
        int fromY = (int) Math.floor(zoomedFromY / zoom);
        int toX = (int) Math.ceil(zoomedToX / zoom);
        int toY = (int) Math.ceil(zoomedToY / zoom);
        final Rectangle r = new Rectangle(fromX, fromY, toX - fromX, toY - fromY);
        if (zoom != lastImageZoom || !Objects.equals(r, this.lastImageRectangle)) {
            BufferedImage original;
            BufferedImage scaled;
            this.lastImageRectangle = new Rectangle(r);
            this.lastImageZoom = zoom;
            Throwable exception;
            try {
                original = readImage(r);
                // if (true) throw new IOException();
                scaled = zoom == 1.0 || original == null ?
                        original :
                        new BufferedImage(zoomedSizeX, zoomedSizeY, original.getColorModel().hasAlpha() ?
                                                                    BufferedImage.TYPE_INT_ARGB :
                                                                    BufferedImage.TYPE_INT_RGB);
                exception = null;
            } catch (Throwable e) {
                // - including possible too large rectangles (IllegalArgumentException)
                original = null;
                scaled = null;
                exception = e;
            }
            if (exception != null) {
                LOG.log(System.Logger.Level.WARNING, "Error while reading image from " + map.streamName() +
                        ": " + exception.getMessage(), exception);
                showError(exception);
                return null;
            }
            if (zoom != 1.0 && scaled != null) {
                // possible Image-based solution:
//                final Image scaledImage = original.getScaledInstance(zoomedSizeX, zoomedSizeY,
//                        zoom < 1 ?
//                                Image.SCALE_AREA_AVERAGING :
//                                Image.SCALE_FAST);
//                Graphics2D g = scaled.createGraphics();
//                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
//                                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
//                g.drawImage(scaledImage, 0, 0, null);
//                g.dispose();

                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(original, 0, 0, zoomedSizeX, zoomedSizeY, null);
                g.dispose();
            }
            lastImage = scaled;
            LOG.log(System.Logger.Level.DEBUG, "Viewer loaded the image region %dx%d starting at (%d,%d)%s"
                    .formatted(r.width, r.height, r.x, r.y,
                            zoom == 1.0 ? "" : " and scaled it to %dx%d (zoom %s)"
                                               .formatted(zoomedSizeX, zoomedSizeY, zoom)));
            resetSelectionStatus();
        }
        return lastImage;
    }

    public BufferedImage readEntireImage() throws IOException {
        return map.readBufferedImage();
    }

    public BufferedImage readSelectedImage() throws IOException {
        Rectangle rectangle = getImageSelection();
        return rectangle == null ? null : readImage(rectangle);
    }

    public BufferedImage readImage(Rectangle viewport) throws IOException {
        if (viewport.width <= 0 || viewport.height <= 0) {
            return null;
        }
        final Matrix<? extends PArray> mergedChannels =
                map.readMatrix(viewport.x, viewport.y, viewport.width, viewport.height);
        final Matrix<? extends PArray> rescaled = isRescaled() ? applyRescaling(mergedChannels) : mergedChannels;
        return map.toBufferedImage(rescaled.asLayers());
    }

    public void invalidateCache() throws IOException {
        reader.clearCache();
        lastImageRectangle = null;
        lastImage = null;
    }

    public boolean isColorCorrection() {
        return reader.isColorCorrection();
    }

    public static String pixelCoordinatesToString(long x, long y) {
        return "Pixel %d, %d".formatted(x, y);
    }

    public String pixelValueToString(PArray channelsArray, TiffSampleType sampleType) {
        if (channelsArray == null) {
            return "";
        }
        final TiffSampleType.Formatter formatter = sampleType.newFormatter();
        formatter.setHexadecimal(pixelValueFormat == UserPixelValueFormat.HEXADECIMAL);
        formatter.setNormalized(pixelValueFormat == UserPixelValueFormat.NORMALIZED);
        formatter.setSeparator(pixelValueFormat == UserPixelValueFormat.HEXADECIMAL ? " " : ", ");
        formatter.setMaxArrayLength(10);
        // - up to 10 channels (very improbable)
        String pixel = "[" + formatter.toString(channelsArray) + "]";
        String scaled = !isRescaled() ?
                "" :
                ", scaled: [" + formatter.toString(applyRescaling(channelsArray)) + "]";
        return pixel + scaled;
    }

    private void createGUI() {
        frame = new JTiffViewerFrame(this);
    }

    private void setStatus(String status, boolean error) {
        Objects.requireNonNull(status);
        if (error != lastErrorFlag || !status.equals(lastStatus)) {
            frame.setStatusSelection(status, error);
            lastStatus = status;
            lastErrorFlag = error;
        }
    }

    private String defaultStatusMessage() {
        return pixelValueFormat == UserPixelValueFormat.NONE ? DEFAULT_STATUS : "";
    }

    private void setPixelValueInformation(long x, long y) {
        PArray channels = null;
        boolean error = false;
        if (pixelValueFormat != UserPixelValueFormat.NONE && x >= 0 && y >= 0 && x < map.dimX() && y < map.dimY()) {
            try {
                channels = map.readMatrix((int) x, (int) y, 1, 1).array();
                // Thread.sleep(1000);
                // if (true) throw new IOException();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Error while reading a pixel from " + map.streamName() +
                        ": " + e.getMessage(), e);
                error = true;
                showError(e);
            }
        }
        final String pixelValue = error ? "ERROR" : pixelValueToString(channels, map.sampleType());
        if (!pixelValue.equals(lastPixelValue)) {
            frame.setStatusPixelValue(pixelValue);
            lastPixelValue = pixelValue;
        }
    }

    private void openMap() throws IOException {
        map = reader.map(ifdIndex);
        map.setExtraChannelsMode(TiffMap.ExtraChannelsMode.DROP_FOR_BUFFERED_IMAGE);
        if (PRELOAD_LITTLE_AREA_WHILE_OPENING) {
            map.readSampleBytes(0, 0, 64, 64);
        }
    }

    void findMaxVisibleValue() throws IOException {
        maxPossibleValue = map.sampleType().maxUnsignedValue();
        maxVisibleValue = maxPossibleValue;
        Rectangle r = getImageVisibleArea();
        if (r != null) {
            Matrix<UpdatablePArray> matrix = map.readMatrix(r.x, r.y, r.width, r.height);
            maxVisibleValue = TiffSamples.maxOf(matrix);
        }
    }

    void showSetRescaleFactorDialog() {
        final JDialog dialog = new JDialog(frame, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setTitle("Set pixel intensity transformation");
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setResizable(false);

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final double contrastRescaleFactor = maxVisibleValue == 0.0 ? 1.0 : maxPossibleValue / maxVisibleValue;
        mainPanel.add(TinySwing.leftLabel(TinySwing.smartHtmlLines(String.format(Locale.US, """
            Specify the intensity transformation parameters: the multiplier (<b>k</b>) 
            and the black offset (<b>b</b>)<br>
            for pixel sample values before visualization, 
            according to the formula <b>k</b>(<i>x</i>&minus;<b>b</b>).<br>
            &nbsp;<br>
            This is useful to contrast low-intensity images. For example, for grayscale matrices<br>
            of 32-bit integers containing object labels or particle indexes recognized during<br>
            image analysis, or for floating-point matrices containing gradients or boundaries.<br>
            &nbsp;<br>
            For the current visible area, we can recommend the factor <b>k</b>:<br>
            &nbsp;&nbsp;&nbsp;&nbsp;<b>%.3f</b> = %.1f / %.1f<br>
            &nbsp;&nbsp;&nbsp;&nbsp;(maximal possible value / maximum in the visible area)<br>
            &nbsp;<br>
            You may set this value by the "Auto contrast" button<br>
            or reset the value to 1.0 by the "Disable" button.<br>
            The black offset (<b>b</b>) can be set manually; its default value is 0.
            """, contrastRescaleFactor, maxPossibleValue, maxVisibleValue))));
        mainPanel.add(Box.createVerticalStrut(15));

        final JPanel settingsPanel = new JPanel();
        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));
        settingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        final JPanel gridPanel = new JPanel(new GridBagLayout());
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 5, 2, 5);

        c.gridx = 0; c.gridy = 0;
        gridPanel.add(new JLabel("<html>Rescale factor (<b>k</b>): "), c);
        c.gridx = 1; c.gridy = 0; c.weightx = 1.0;
        final JTextField factorField = new JTextField(24);
        gridPanel.add(factorField, c);
        factorField.setText(Double.toString(rescaleFactor == null ? contrastRescaleFactor : rescaleFactor));

        c.gridx = 0; c.gridy = 1; c.weightx = 0.0;
        gridPanel.add(new JLabel("<html>Black offset (<b>b</b>): "), c);
        c.gridx = 1; c.gridy = 1; c.weightx = 1.0;
        final JTextField blackOffsetField = new JTextField(24);
        blackOffsetField.setText(Double.toString(blackOffset));
        gridPanel.add(blackOffsetField, c);

        settingsPanel.add(gridPanel);
        settingsPanel.add(Box.createVerticalStrut(10));

        final JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        final JButton contrastButton = new JButton("Auto contrast");
        contrastButton.setToolTipText("Set rescale factor to " +
                contrastRescaleFactor + " = " + maxPossibleValue + " / " + maxVisibleValue);
        contrastButton.addActionListener(e -> {
            factorField.setText(String.valueOf(contrastRescaleFactor));
            factorField.requestFocusInWindow();
        });
        contrastButton.setMnemonic(KeyEvent.VK_A);

        final JButton disableButton = new JButton("Disable");
        disableButton.setToolTipText("Set rescale factor to 1.0 (disable rescaling)");
        disableButton.addActionListener(e -> {
            factorField.setText(String.valueOf(1.0));
            factorField.requestFocusInWindow();
        });
        disableButton.setMnemonic(KeyEvent.VK_D);
        actionsPanel.add(contrastButton);
        actionsPanel.add(disableButton);
        settingsPanel.add(actionsPanel);

        mainPanel.add(settingsPanel);
        dialog.add(mainPanel, BorderLayout.CENTER);

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        final JButton okButton = new JButton("OK");
        final JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(okButton);

        cancelButton.addActionListener(e -> dialog.dispose());

        okButton.addActionListener(e -> {
            try {
                final double newRescaleFactor = Double.parseDouble(factorField.getText().trim());
                if (newRescaleFactor <= 0.0 || Double.isNaN(newRescaleFactor) || Double.isInfinite(newRescaleFactor)) {
                    throw new IllegalArgumentException("Rescale factor must be a positive number");
                }
                final double newBlackOffset = Double.parseDouble(blackOffsetField.getText().trim());
                if (Double.isNaN(newBlackOffset) || Double.isInfinite(newBlackOffset)) {
                    throw new IllegalArgumentException("Black offset must be a finite number");
                }
                boolean changed = newRescaleFactor != getRescaleFactor() || newBlackOffset != getBlackOffset();
                setRescaleFactor(newRescaleFactor);
                // - always, even if rescaleFactor == null
                setBlackOffset(newBlackOffset);
                if (changed) {
                    invalidateCache();
                    repaint();
                    frame.resetImageInformation();
                }
                dialog.dispose();
            } catch (Exception ex) {
                TinySwing.showErrorMessage(frame, ex, "Invalid rescale factor value");
            }
        });

        dialog.pack();
        TinySwing.addCloseOnEscape(dialog);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    void showSetSelectionDialog() {
        final JPanel mainPanel = new JPanel(new GridLayout(4, 2, 5, 5));
        final JTextField leftField = new JTextField(8);
        final JTextField topField = new JTextField(8);
        final JTextField widthField = new JTextField(8);
        final JTextField heightField = new JTextField(8);

        final Rectangle current = frame.tiffPanel().getImageSelection();
        if (current != null) {
            leftField.setText(Integer.toString(current.x));
            topField.setText(Integer.toString(current.y));
            widthField.setText(Integer.toString(current.width));
            heightField.setText(Integer.toString(current.height));
        } else {
            leftField.setText("0");
            topField.setText("0");
            widthField.setText(Integer.toString(map.dimX()));
            heightField.setText(Integer.toString(map.dimY()));
        }

        mainPanel.add(new JLabel("Left:"));
        mainPanel.add(leftField);
        mainPanel.add(new JLabel("Top:"));
        mainPanel.add(topField);
        mainPanel.add(new JLabel("Width:"));
        mainPanel.add(widthField);
        mainPanel.add(new JLabel("Height:"));
        mainPanel.add(heightField);

        final int result = JOptionPane.showConfirmDialog(
                frame,
                mainPanel,
                "Set selection rectangle",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            final int left = Integer.parseInt(leftField.getText().trim());
            final int top = Integer.parseInt(topField.getText().trim());
            final int width = Integer.parseInt(widthField.getText().trim());
            final int height = Integer.parseInt(heightField.getText().trim());
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Width and height must be non-negative");
            }
            setImageSelection(left, top, width, height);
        } catch (Exception ex) {
            TinySwing.showErrorMessage(frame, ex, "Invalid selection values");
        }
    }

    private PArray applyRescaling(PArray array) {
        double k = getRescaleFactor();
        double b = getBlackOffset();
        return TiffSamples.applyLinearFunction(array, k, -b * k);
    }

    private Matrix<? extends PArray> applyRescaling(Matrix<? extends PArray> matrix) {
        double k = getRescaleFactor();
        double b = getBlackOffset();
        return TiffSamples.applyLinearFunction(matrix, k, -b * k);
    }
}
