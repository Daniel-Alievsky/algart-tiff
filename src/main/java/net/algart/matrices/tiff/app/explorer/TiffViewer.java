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

import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

class TiffViewer {
    static final String DEFAULT_STATUS =
            "Use mouse drag to select a rectangle, or SHIFT-drag to move the image";
    private static final long CACHING_MEMORY = 256 * 1048576L;
    // - 256MB = 16 * 16M is enough to store several user screens even with the zoom 1:4
    private static final boolean PRELOAD_LITTLE_AREA_WHILE_OPENING = true;
    // - should be true; you may clear this flag to debug possible I/O errors during the drawing process

    private static final System.Logger LOG = System.getLogger(TiffViewer.class.getName());

    private static final Color COMMON_COLOR = Color.BLACK;
    private static final Color ERROR_COLOR = Color.RED;

    private final TiffReaderWithGrid reader;
    private final int ifdIndex;

    private TiffReadMap map = null;

    private double lastImageZoom = 0.0;
    private Rectangle lastImageRectangle = null;
    private BufferedImage lastImage = null;
    private Throwable exception = null;

    private String lastStatus = DEFAULT_STATUS;
    private boolean lastErrorFlag = false;

    private JTiffFrame frame;

    public TiffViewer(Path tiffFile, int ifdIndex) throws IOException {
        Objects.requireNonNull(tiffFile);
        this.reader = new TiffReaderWithGrid(tiffFile);
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

    public TiffReader reader() {
        return reader;
    }


    public int ifdIndex() {
        return ifdIndex;
    }

    public TiffReadMap map() {
        return map;
    }

    public void show() throws IOException {
        openMap();
        createGUI();
    }

    public void showNormalStatus() {
        final Rectangle r = getSelection();
        final String status = r == null ?
                DEFAULT_STATUS :
                r.width == 0 || r.height == 0 ?
                        "%dx%d: top-left (%d,%d)".formatted(
                                r.width, r.height, r.x, r.y) :
                        "%dx%d: top-left (%d,%d), bottom-right (%d,%d)".formatted(
                                r.width, r.height, r.x, r.y, r.x + r.width - 1, r.y + r.height - 1);
        showStatus(status);
    }

    public void showStatus(String status) {
        setStatus(status, false);
    }

    public void showError(String status) {
        setStatus(status, true);
    }

    public void reload() throws IOException {
        try {
            resetCache();
            openMap();
            resetCache();
        } finally {
            frame.resetTitle();
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
                LOG.log(System.Logger.Level.ERROR, "Error while reading " + map.streamName() +
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
            showNormalStatus();
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
        return viewport.width > 0 && viewport.height > 0 ?
                map.readBufferedImage(viewport.x, viewport.y, viewport.width, viewport.height) :
                null;
    }

    public void resetCache() {
        reader.resetCache();
        lastImageRectangle = null;
        lastImage = null;
        exception = null;
    }

    void setTileGridVisibility(boolean visible) {
        if (visible != reader.isViewTileGrid()) {
            reader.setViewTileGrid(visible);
            resetCache();
        }
    }

    void setTileGridThickness(int tileGridThickness) {
        reader.setTileGridThickness(tileGridThickness);
        resetCache();
    }

    private void createGUI() {
        frame = new JTiffFrame(this);
    }

    private void setStatus(String status, boolean error) {
        Objects.requireNonNull(status);
        if (error != lastErrorFlag || !status.equals(lastStatus)) {
            SwingUtilities.invokeLater(() -> {
                final JLabel statusLabel = frame.statusLabel();
                statusLabel.setForeground(error ? ERROR_COLOR : COMMON_COLOR);
                statusLabel.setText(status);
            });
            lastStatus = status;
            lastErrorFlag = error;
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
            showErrorMessage(ex, "Invalid selection values");
        }
    }

    private void showErrorMessage(Throwable e, String title) {
        TiffExplorer.showErrorMessage(frame, e, title);
    }

    private static final class MenuUpdater implements MenuListener {
        private final Runnable updater;

        private MenuUpdater(Runnable updater) {
            this.updater = updater;
        }

        @Override
        public void menuSelected(MenuEvent e) {
            updater.run();
        }

        @Override
        public void menuDeselected(MenuEvent e) {

        }

        @Override
        public void menuCanceled(MenuEvent e) {
        }
    }
}
