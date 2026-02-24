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

import net.algart.matrices.tiff.tiles.TiffReadMap;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Objects;

class JTiffPanel extends JComponent {
    private static final Paint TRANSPARENCY_PAINT = createChessPaint(
            8,
            new Color(255, 255, 255),
            new Color(200, 200, 200));
    private static final Paint ERROR_PAINT = createChessPaint(
            8,
            new Color(255, 0, 0),
            new Color(255, 128, 128));

    private enum FrameHandle {
        TOP_LEFT(Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)),
        TOP_RIGHT(Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)),
        BOTTOM_LEFT(Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)),
        BOTTOM_RIGHT(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)),
        TOP(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)),
        BOTTOM(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)),
        LEFT(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)),
        RIGHT(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));

        final Cursor cursor;

        FrameHandle(Cursor cursor) {
            this.cursor = cursor;
        }

        public Rectangle handleBounds(Rectangle frame, int s) {
            final int fromX = frame.x;
            final int fromY = frame.y;
            final int toX = frame.x + frame.width;
            final int toY = frame.y + frame.height;
            return switch (this) {
                case TOP_LEFT -> new Rectangle(fromX - s / 2, fromY - s / 2, s, s);
                case TOP_RIGHT -> new Rectangle(toX - s / 2, fromY - s / 2, s, s);
                case BOTTOM_LEFT -> new Rectangle(fromX - s / 2, toY - s / 2, s, s);
                case BOTTOM_RIGHT -> new Rectangle(toX - s / 2, toY - s / 2, s, s);
                case TOP -> new Rectangle(fromX + frame.width / 2 - s / 2, fromY - s / 2, s, s);
                case BOTTOM -> new Rectangle(fromX + frame.width / 2 - s / 2, toY - s / 2, s, s);
                case LEFT -> new Rectangle(fromX - s / 2, fromY + frame.height / 2 - s / 2, s, s);
                case RIGHT -> new Rectangle(toX - s / 2, fromY + frame.height / 2 - s / 2, s, s);
            };
        }
    }

    private static final int FRAME_HANDLE_SIZE = 8;
    private static final int CREATING_NEW_FRAME_MINIMAL_SHIFT = 5;

    private static final System.Logger LOG = System.getLogger(JTiffPanel.class.getName());

    private final TiffImageViewer viewer;
    private final TiffReadMap map;
    private int canvasDimX;
    private int canvasDimY;
    private double zoom = 1.0;

    private int fromX = -1;
    private int fromY = -1;
    private int toX = -1;
    private int toY = -1;
    private boolean selected = false;

    private boolean mouseHandleEnabled = true;
    private boolean creatingNewFrame = false;
    private boolean resizingFrame = false;
    private boolean movingFrame = false;

    private int dragOffsetX, dragOffsetY;
    private FrameHandle selectedHandle = null;

    private final Timer dashTimer;
    private int dashPhase = 0;

    public JTiffPanel(TiffImageViewer viewer) {
        this.viewer = Objects.requireNonNull(viewer, "Null viewer");
        this.map = viewer.map();
        this.canvasDimX = map.dimX();
        this.canvasDimY = map.dimY();
        reset();

        dashTimer = new Timer(300, e -> {
            dashPhase = (dashPhase + 1) % 16;
            if (selected) {
                repaint(); // перерисуем компонент полностью, или можно ограниченно
            }
        });
        dashTimer.start();

        initFrameMouseListeners();
    }

    public void disposeResources() {
        dashTimer.stop();
    }

    public void reset() {
        setPreferredSize(new Dimension(canvasDimX, canvasDimY));
    }

    public double getZoom() {
        return zoom;
    }

    public JTiffPanel setZoom(double zoom) {
        if (zoom <= 0.0) {
            throw new IllegalArgumentException("Negative zoom: " + zoom);
        }
        if (zoom != this.zoom) {
            long canvasDimX = Math.round(map.dimX() * zoom);
            long canvasDimY = Math.round(map.dimY() * zoom);
            if (canvasDimX > Integer.MAX_VALUE || canvasDimY > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Too big zoom: the zoomed image will be " + canvasDimX + "x" + canvasDimY);
            }
            this.zoom = zoom;
            this.canvasDimX = (int) canvasDimX;
            this.canvasDimY = (int) canvasDimY;
            removeSelection();
            reset();
            revalidate();
            repaint();
        }
        return this;
    }

    public int getCanvasDimX() {
        return canvasDimX;
    }

    public int getCanvasDimY() {
        return canvasDimY;
    }

    public void removeSelection() {
        this.selected = false;
        repaint();
        viewer.showNormalStatus();
    }

    public boolean isSelected() {
        return selected;
    }

    public boolean hasNonEmptySelection() {
        return selected && fromX != toX && fromY != toY;
    }

    public Rectangle getImageSelection() {
        return getSelection(zoom);
    }

    public Rectangle getCanvasSelection() {
        return getSelection(1.0);
    }

    public void setSelectionAll() {
        setCanvasSelection(0, 0, canvasDimX, canvasDimY);
    }

    public void setImageSelection(Rectangle r) {
        Objects.requireNonNull(r, "Null rectangle");
        setImageSelection(r.x, r.y, (long) r.x + (long) r.width, (long) r.y + (long) r.height);
    }

    public void setImageSelection(long fromX, long fromY, long toX, long toY) {
        final long canvasFromX = (long) Math.floor(fromX * zoom);
        final long canvasFromY = (long) Math.floor(fromY * zoom);
        final long canvasToX = (long) Math.ceil(toX * zoom);
        final long canvasToY = (long) Math.ceil(toY * zoom);
        setCanvasSelection(canvasFromX, canvasFromY, canvasToX, canvasToY);
    }

    public void setCanvasSelection(long fromX, long fromY, long toX, long toY) {
        if (this.selected && fromX == this.fromX && fromY == this.fromY && toX == this.toX && toY == this.toY) {
            return;
        }
        if (toX < fromX || toY < fromY) {
            this.selected = false;
        } else {
            this.selected = true;
            this.fromX = Math.clamp(fromX, 0, canvasDimX);
            this.fromY = Math.clamp(fromY, 0, canvasDimY);
            this.toX = Math.clamp(toX, 0, canvasDimX);
            this.toY = Math.clamp(toY, 0, canvasDimY);
        }
        repaint();
        viewer.showNormalStatus();
    }

    private void normalizeNegativeSelection() {
        if (fromX > toX) {
            int temp = fromX;
            fromX = toX;
            toX = temp;
        }
        if (fromY > toY) {
            int temp = fromY;
            fromY = toY;
            toY = temp;
        }
    }

    private void adjustSelectionToZoomedPixels() {
        if (zoom > 1.0) {
            setImageSelection(getImageSelection());
        }
    }

    public void setMouseHandleEnabled(boolean mouseHandleEnabled) {
        this.mouseHandleEnabled = mouseHandleEnabled;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;

        Rectangle clip = graphics.getClipBounds();
        if (clip != null) {
            clip = cropRectangleByCanvas(clip);
            BufferedImage bi = viewer.reloadFragment(
                    clip.x, clip.y, clip.x + clip.width, clip.y + clip.height, zoom);
            if (bi != null) {
                g.setPaint(TRANSPARENCY_PAINT);
                g.fillRect(clip.x, clip.y, clip.width, clip.height);
                g.drawImage(bi, clip.x, clip.y, null);
            } else if (clip.width > 0 && clip.height > 0) {
                g.setPaint(ERROR_PAINT);
                g.fillRect(clip.x, clip.y, clip.width, clip.height);
            }
        }
        drawSelectionBorder(g);
    }

    private void drawSelectionBorder(Graphics2D g) {
        if (selected) {
            final int fromX = Math.min(this.fromX, toX);
            final int fromY = Math.min(this.fromY, toY);
            final int sizeX = Math.max(Math.abs(toX - this.fromX), 2);
            final int sizeY = Math.max(Math.abs(toY - this.fromY), 2);

            Stroke oldStroke = g.getStroke();
            g.setStroke(new BasicStroke(
                    1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 4f}, (float) dashPhase));
            g.setColor(Color.BLACK);
            g.drawRect(fromX, fromY, sizeX - 1, sizeY - 1);
            // - actually this method draws an internal boundary of the rectangle sizeX * sizeY pixels
            if (sizeX > 2 && sizeY > 2) {
                g.setColor(Color.WHITE);
                g.drawRect(fromX + 1, fromY + 1, sizeX - 3, sizeY - 3);
            }
            g.setStroke(oldStroke);

            final int[] xPositions = {fromX, fromX + sizeX / 2, fromX + sizeX};
            final int[] yPositions = {fromY, fromY + sizeY / 2, fromY + sizeY};
            for (int cx : xPositions) {
                for (int cy : yPositions) {
                    drawFrameHandle(g, cx, cy);
                }
            }
        }
    }

    private void initFrameMouseListeners() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!mouseHandleEnabled || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                final int mx = e.getX();
                final int my = e.getY();

                normalizeNegativeSelection();
                FrameHandle handle = getHandleUnder(mx, my);
                if (handle != null) {
                    selectedHandle = handle;
                    resizingFrame = true;
                    movingFrame = false;
                    creatingNewFrame = false;
                    LOG.log(System.Logger.Level.DEBUG, "Resizing frame");
                } else if (isInsideFrame(mx, my)) {
                    selectedHandle = null;
                    movingFrame = true;
                    dragOffsetX = mx - fromX;
                    dragOffsetY = my - fromY;
                    creatingNewFrame = false;
                    resizingFrame = false;
                    LOG.log(System.Logger.Level.DEBUG, "Dragging frame");
                } else {
                    selectedHandle = null;
                    creatingNewFrame = true;
                    selected = true;
                    resizingFrame = false;
                    movingFrame = false;
                    fromX = toX = mx;
                    fromY = toY = my;
                    LOG.log(System.Logger.Level.DEBUG, "Creating new frame");
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (creatingNewFrame &&
                        Math.abs(toX - fromX) < CREATING_NEW_FRAME_MINIMAL_SHIFT &&
                        Math.abs(toY - fromY) < CREATING_NEW_FRAME_MINIMAL_SHIFT) {
                    LOG.log(System.Logger.Level.DEBUG, "Removing frame");
                    removeSelection();
                }
                normalizeNegativeSelection();
                adjustSelectionToZoomedPixels();
                creatingNewFrame = false;
                resizingFrame = false;
                movingFrame = false;
                selectedHandle = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                final int mx = e.getX();
                final int my = e.getY();
                if (creatingNewFrame) {
                    toX = Math.clamp(mx, 0, canvasDimX);
                    toY = Math.clamp(my, 0, canvasDimY);
                } else if (resizingFrame && selectedHandle != null) {
                    resizeFrame(selectedHandle, mx, my);
                } else if (movingFrame) {
                    final int sizeX = toX - fromX;
                    final int sizeY = toY - fromY;
                    fromX = Math.clamp(mx - dragOffsetX, 0, Math.max(0, canvasDimX - sizeX));
                    fromY = Math.clamp(my - dragOffsetY, 0, Math.max(0, canvasDimY - sizeY));
                    toX = fromX + sizeX;
                    toY = fromY + sizeY;
                }
                viewer.showNormalStatus();
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (!mouseHandleEnabled) {
                    return;
                }
                final int mx = e.getX();
                final int my = e.getY();
                FrameHandle handle = getHandleUnder(mx, my);
                if (handle != null) {
                    setCursor(handle.cursor);
                } else if (isInsideFrame(mx, my)) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else {
                    setCursor(Cursor.getDefaultCursor());
                }
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private void resizeFrame(FrameHandle handle, int mx, int my) {
        mx = Math.clamp(mx, 0, canvasDimX);
        my = Math.clamp(my, 0, canvasDimY);
        switch (handle) {
            case TOP_LEFT -> {
                fromX = mx;
                fromY = my;
            }
            case TOP_RIGHT -> {
                toX = mx;
                fromY = my;
            }
            case BOTTOM_LEFT -> {
                fromX = mx;
                toY = my;
            }
            case BOTTOM_RIGHT -> {
                toX = mx;
                toY = my;
            }
            case TOP -> fromY = my;
            case BOTTOM -> toY = my;
            case LEFT -> fromX = mx;
            case RIGHT -> toX = mx;
        }
    }

    private FrameHandle getHandleUnder(int x, int y) {
        final Rectangle frame = getCanvasSelection();
        if (frame == null) {
            return null;
        }
        for (FrameHandle handle : FrameHandle.values()) {
            if (handle.handleBounds(frame, FRAME_HANDLE_SIZE).contains(x, y)) {
                return handle;
            }
        }
        return null;
    }

    private boolean isInsideFrame(int x, int y) {
        if (!this.selected) {
            return false;
        }
        final int fromX = Math.min(this.fromX, this.toX);
        final int fromY = Math.min(this.fromY, this.toY);
        final int toX = Math.max(this.fromX, this.toX);
        final int toY = Math.max(this.fromY, this.toY);
        return x >= fromX && x < toX && y >= fromY && y < toY;
    }

    private static void drawFrameHandle(Graphics2D g, int cx, int cy) {
        g.setColor(Color.WHITE);
        final int half = FRAME_HANDLE_SIZE / 2;
        g.fillRect(cx - half, cy - half, FRAME_HANDLE_SIZE, FRAME_HANDLE_SIZE);
        g.setColor(Color.BLACK);
        g.drawRect(cx - half, cy - half, FRAME_HANDLE_SIZE, FRAME_HANDLE_SIZE);
    }

    private Rectangle cropRectangleByCanvas(Rectangle rectangle) {
        Rectangle r = new Rectangle(rectangle);
        r.x = Math.clamp(r.x, 0, canvasDimX);
        r.y = Math.clamp(r.y, 0, canvasDimY);
        r.width = Math.clamp(r.width, 0, canvasDimX - r.x);
        r.height = Math.clamp(r.height, 0, canvasDimY - r.y);
        return r;
    }

    private Rectangle getSelection(double zoom) {
        if (selected) {
            int fromX = Math.min(this.fromX, this.toX);
            int fromY = Math.min(this.fromY, this.toY);
            int toX = Math.max(this.fromX, this.toX);
            int toY = Math.max(this.fromY, this.toY);
            if (zoom != 1.0) {
                fromX = Math.clamp((long) Math.floor(fromX / zoom), 0, map.dimX());
                fromY = Math.clamp((long) Math.floor(fromY / zoom), 0, map.dimY());
                toX = Math.clamp((long) Math.floor(toX / zoom), 0, map.dimX());
                toY = Math.clamp((long) Math.floor(toY / zoom), 0, map.dimY());
                // Math.floor for both fromX and toX: empty selection is transformed to empty rectangle
            }
            return new Rectangle(fromX, fromY, toX - fromX, toY - fromY);
        } else {
            return null;
        }
    }

    private static TexturePaint createChessPaint(int size, Color c1, Color c2) {
        BufferedImage bi = new BufferedImage(size * 2, size * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(c1);
        g.fillRect(0, 0, size * 2, size * 2);
        g.setColor(c2);
        g.fillRect(size, 0, size, size);
        g.fillRect(0, size, size, size);
        g.dispose();
        return new TexturePaint(bi, new Rectangle(0, 0, size * 2, size * 2));
    }
}
