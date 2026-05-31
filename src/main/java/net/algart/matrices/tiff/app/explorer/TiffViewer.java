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

import net.algart.arrays.JArrays;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

class TiffViewer {
    enum PixelValueFormat {
        NONE("None"),
        DECIMAL("Decimal"),
        HEXADECIMAL("Hexadecimal"),
        NORMALIZED("Normalized (0..1)"),;

        private final String caption;

        PixelValueFormat(String caption) {
            this.caption = caption;
        }

        public String caption() {
            return caption;
        }

        public boolean isSuitable(TiffSampleType sampleType) {
            if (sampleType == TiffSampleType.BIT) {
                return this != NORMALIZED;
            } else if (sampleType.isFloatingPoint()) {
                return this == NONE || this == DECIMAL;
            } else {
                return true;
            }
        }
    }

    static final String DEFAULT_PIXEL_COORDINATES = pixelCoordinatesToString(0, 0);
    static final String DEFAULT_PIXEL_VALUE = "";;
    static final String DEFAULT_STATUS =
            "Use mouse drag to select a rectangle, or SHIFT-drag to move the image";
    static final boolean DEFAULT_COLOR_CORRECTION = true;
    private static final long CACHING_MEMORY = 256 * 1048576L;
    // - 256MB = 16 * 16M is enough to store several user screens even with the zoom 1:4
    private static final boolean PRELOAD_LITTLE_AREA_WHILE_OPENING = true;
    // - should be true; you may clear this flag to debug possible I/O errors during the drawing process

    private static final System.Logger LOG = System.getLogger(TiffViewer.class.getName());

    private final TiffReaderWithGrid reader;
    private final Path path;
    private final int ifdIndex;

    private PixelValueFormat pixelValueFormat = PixelValueFormat.NONE;

    private TiffReadMap map = null;

    private double lastImageZoom = 0.0;
    private Rectangle lastImageRectangle = null;
    private BufferedImage lastImage = null;
    private Throwable exception = null;

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
        this.reader.setRemoveExtraChannelsIf5OrMoreForBufferedImage(true);
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

    public PixelValueFormat getPixelValueFormat() {
        return pixelValueFormat;
    }

    public TiffViewer setPixelValueFormat(PixelValueFormat pixelValueFormat) {
        this.pixelValueFormat = Objects.requireNonNull(pixelValueFormat, "Null pixelValueFormat");
        return this;
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

    public void showSelection() {
        final Rectangle r = getSelection();
        final String status = r == null ?
                DEFAULT_STATUS :
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
            SwingUtilities.invokeLater(() -> {
                frame.statusPixelCoordinatesLabel().setText(pixelCoordinates);
                setPixelValue(x, y);
            });
            lastPixelX = x;
            lastPixelY = y;
        }
    }

    public void showPixelInformation(int x, int y, double zoom) {
        showPixelInformation(Math.round(x / zoom), Math.round(y / zoom));
    }

    public void showLastPixelValue() {
        SwingUtilities.invokeLater(() -> setPixelValue(lastPixelX, lastPixelY));
    }

    public void showError(String status) {
        setStatus(status, true);
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
            frame.resetImage();
            frame.tiffPanel().reset();
            frame.tiffPanel().repaint();
        }
    }

    public Rectangle getSelection() {
        return frame.tiffPanel().getImageSelection();
    }

    public void setSelection(int left, int top, int width, int height) {
        frame.tiffPanel().setImageSelection(
                left,
                top,
                (long) left + (long) width,
                (long) top + (long) height);
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
            try {
                original = readImage(r);
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
                showError(exception.getMessage());
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
            showSelection();
        }
        return lastImage;
    }

    public BufferedImage readEntireImage() throws IOException {
        return map.readBufferedImage();
    }

    public BufferedImage readSelectedImage() throws IOException {
        Rectangle rectangle = getSelection();
        return rectangle == null ? null : readImage(rectangle);
    }

    public BufferedImage readImage(Rectangle viewport) throws IOException {
        if (viewport.width <= 0 || viewport.height <= 0) {
            return null;
        }
        return map.readBufferedImage(viewport.x, viewport.y, viewport.width, viewport.height);
    }

    public void invalidateCache() throws IOException {
        reader.clearCache();
        lastImageRectangle = null;
        lastImage = null;
        exception = null;
    }

    public boolean isColorCorrection() {
        return reader.isColorCorrection();
    }

    public static String pixelCoordinatesToString(long x, long y) {
        return "Pixel %d, %d".formatted(x, y);
    }

    public String pixelValueToString(Object channelsArray, TiffSampleType sampleType) {
        if (channelsArray == null) {
            return "";
        }
        //TODO!! process NORMALIZED
        int n = map.numberOfChannels();
        String s = sampleType.javaArrayToString(channelsArray, Math.min(n, 8),
                pixelValueFormat == PixelValueFormat.HEXADECIMAL,
                "%.1f",
                pixelValueFormat == PixelValueFormat.HEXADECIMAL ? " " : ", ");
        return "(" + (n <= 8 || s.endsWith("...") ? s : s + "...")  + ")";
    }

    private void createGUI() {
        frame = new JTiffViewerFrame(this);
    }

    private void setStatus(String status, boolean error) {
        Objects.requireNonNull(status);
        if (error != lastErrorFlag || !status.equals(lastStatus)) {
            SwingUtilities.invokeLater(() -> {
                final JLabel label = frame.statusSelectionLabel();
                label.setForeground(error ? TiffExplorer.ERROR_COLOR : TiffExplorer.COMMON_COLOR);
                label.setText(status);
            });
            lastStatus = status;
            lastErrorFlag = error;
        }
    }

    private void setPixelValue(long x, long y) {
        Object channels = null;
        boolean error = false;
        if (pixelValueFormat != PixelValueFormat.NONE && x >= 0 && y >= 0 && x < map.dimX() && y < map.dimY()) {
            try {
                channels = map.readJavaArray((int) x, (int) y, 1, 1);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Error while reading a pixel from " + map.streamName() +
                        ": " + exception.getMessage(), exception);
                error = true;
                showError(exception.getMessage());
            }
        }
        final String pixelValue = error ? "ERROR" : pixelValueToString(channels, map.sampleType());
        if (!pixelValue.equals(lastPixelValue)) {
            frame.statusPixelValueLabel().setText(pixelValue);
            lastPixelValue = pixelValue;
        }
    }

    private void openMap() throws IOException {
        map = reader.map(ifdIndex);
        if (PRELOAD_LITTLE_AREA_WHILE_OPENING) {
            map.readSampleBytes(0, 0, 64, 64);
        }
    }

    void showSetSelectionDialog() {
        final JPanel panel = new JPanel(new GridLayout(4, 2, 5, 5));
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

        panel.add(new JLabel("Left:"));
        panel.add(leftField);
        panel.add(new JLabel("Top:"));
        panel.add(topField);
        panel.add(new JLabel("Width:"));
        panel.add(widthField);
        panel.add(new JLabel("Height:"));
        panel.add(heightField);

        final int result = JOptionPane.showConfirmDialog(
                frame,
                panel,
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
            setSelection(left, top, width, height);
        } catch (Exception ex) {
            TinySwing.showErrorMessage(frame, ex, "Invalid selection values");
        }
    }
}
