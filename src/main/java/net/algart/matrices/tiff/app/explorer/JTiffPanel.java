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

    private int frameFromX = -1;
    private int frameFromY = -1;
    private int frameToX = -1;
    private int frameToY = -1;
    private boolean frameExists = false;

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
        reset();

        dashTimer = new Timer(300, e -> {
            dashPhase = (dashPhase + 1) % 16;
            if (frameExists) {
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
        final TiffReadMap map = viewer.map();
        setPreferredSize(new Dimension(map.dimX(), map.dimY()));
    }

    public void removeFrame() {
        frameExists = false;
        repaint();
        viewer.showNormalStatus();
    }

    public void setFrame(int fromX, int fromY, int toX, int toY) {
        frameExists = true;
        frameFromX = fromX;
        frameFromY = fromY;
        frameToX = toX;
        frameToY = toY;
        repaint();
        viewer.showNormalStatus();
    }

    public void setMouseHandleEnabled(boolean mouseHandleEnabled) {
        this.mouseHandleEnabled = mouseHandleEnabled;
    }

    public Rectangle currentFrame() {
        if (frameExists) {
            final int fromX = Math.min(frameFromX, frameToX);
            final int fromY = Math.min(frameFromY, frameToY);
            final int toX = Math.max(frameFromX, frameToX);
            final int toY = Math.max(frameFromY, frameToY);
            return new Rectangle(fromX, fromY, toX - fromX, toY - fromY);
        } else {
            return null;
        }
    }

    private void normalizeNegativeFrame() {
        if (frameFromX > frameToX) {
            int temp = frameFromX;
            frameFromX = frameToX;
            frameToX = temp;
        }
        if (frameFromY > frameToY) {
            int temp = frameFromY;
            frameFromY = frameToY;
            frameToY = temp;
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;

        Rectangle clip = graphics.getClipBounds();
        if (clip != null) {
            clip = new Rectangle(clip);
            BufferedImage bi = viewer.loadFragment(clip);
            if (bi != null) {
                g.drawImage(bi, clip.x, clip.y, null);
            } else if (clip.width > 0 && clip.height > 0) {
                g.setPaint(ERROR_PAINT);
                g.fillRect(clip.x, clip.y, clip.width, clip.height);
            }
        }
        drawFrame(g);
    }

    private void drawFrame(Graphics2D g) {
        if (frameExists) {
            int x = Math.min(frameFromX, frameToX);
            int y = Math.min(frameFromY, frameToY);
            int w = Math.max(Math.abs(frameToX - frameFromX), 1);
            int h = Math.max(Math.abs(frameToY - frameFromY), 1);

            Stroke oldStroke = g.getStroke();
            g.setStroke(new BasicStroke(
                    1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 4f}, (float) dashPhase));
            g.setColor(Color.BLACK);
            g.drawRect(x, y, w, h);
            g.setColor(Color.WHITE);
            g.drawRect(x + 1, y + 1, w, h);
            g.setStroke(oldStroke);

            final int[] xs = {x, x + w / 2, x + w};
            final int[] ys = {y, y + h / 2, y + h};
            for (int cx : xs) {
                for (int cy : ys) {
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

                normalizeNegativeFrame();
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
                    dragOffsetX = mx - frameFromX;
                    dragOffsetY = my - frameFromY;
                    creatingNewFrame = false;
                    resizingFrame = false;
                    LOG.log(System.Logger.Level.DEBUG, "Dragging frame");
                } else {
                    selectedHandle = null;
                    creatingNewFrame = true;
                    frameExists = true;
                    resizingFrame = movingFrame = false;
                    frameFromX = frameToX = mx;
                    frameFromY = frameToY = my;
                    LOG.log(System.Logger.Level.DEBUG, "Creating new frame");
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (creatingNewFrame &&
                        Math.abs(frameToX - frameFromX) < CREATING_NEW_FRAME_MINIMAL_SHIFT &&
                        Math.abs(frameToY - frameFromY) < CREATING_NEW_FRAME_MINIMAL_SHIFT) {
                    LOG.log(System.Logger.Level.DEBUG, "Removing frame");
                    removeFrame();
                }
                normalizeNegativeFrame();
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
                int mx = e.getX(), my = e.getY();

                if (creatingNewFrame) {
                    frameToX = mx;
                    frameToY = my;
                } else if (resizingFrame && selectedHandle != null) {
                    resizeFrame(selectedHandle, mx, my);
                } else if (movingFrame) {
                    int w = frameToX - frameFromX;
                    int h = frameToY - frameFromY;
                    frameFromX = mx - dragOffsetX;
                    frameFromY = my - dragOffsetY;
                    frameToX = frameFromX + w;
                    frameToY = frameFromY + h;
                }
                viewer.showNormalStatus();
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (!mouseHandleEnabled) {
                    return;
                }
                final int mx = e.getX(), my = e.getY();
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
        switch (handle) {
            case TOP_LEFT -> {
                frameFromX = mx;
                frameFromY = my;
            }
            case TOP_RIGHT -> {
                frameToX = mx;
                frameFromY = my;
            }
            case BOTTOM_LEFT -> {
                frameFromX = mx;
                frameToY = my;
            }
            case BOTTOM_RIGHT -> {
                frameToX = mx;
                frameToY = my;
            }
            case TOP -> frameFromY = my;
            case BOTTOM -> frameToY = my;
            case LEFT -> frameFromX = mx;
            case RIGHT -> frameToX = mx;
        }
    }

    private FrameHandle getHandleUnder(int x, int y) {
        final Rectangle frame = currentFrame();
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
        if (!frameExists) {
            return false;
        }
        int fx = Math.min(frameFromX, frameToX);
        int fy = Math.min(frameFromY, frameToY);
        int tx = Math.max(frameFromX, frameToX);
        int ty = Math.max(frameFromY, frameToY);
        return x >= fx && x <= tx && y >= fy && y <= ty;
    }

    private static void drawFrameHandle(Graphics2D g, int cx, int cy) {
        g.setColor(Color.WHITE);
        final int half = FRAME_HANDLE_SIZE / 2;
        g.fillRect(cx - half, cy - half, FRAME_HANDLE_SIZE, FRAME_HANDLE_SIZE);
        g.setColor(Color.BLACK);
        g.drawRect(cx - half, cy - half, FRAME_HANDLE_SIZE, FRAME_HANDLE_SIZE);
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
