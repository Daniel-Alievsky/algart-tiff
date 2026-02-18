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
    private static final int FRAME_HANDLE_SIZE = 8;


    private final TiffImageViewer viewer;

    private Integer frameFromX = null, frameFromY = null;
    private Integer frameToX = null, frameToY = null;

    private boolean draggingFrame = false;
    private boolean resizingFrame = false;
    private boolean movingFrame = false;

    private int dragOffsetX, dragOffsetY;
    private FrameHandle selectedHandle = null;

    private final Timer dashTimer;
    private float dashPhase = 0;

    public JTiffPanel(TiffImageViewer viewer) {
        this.viewer = Objects.requireNonNull(viewer, "Null viewer");
        reset();

        dashTimer = new Timer(100, e -> {
            dashPhase += 1f;
            if (dashPhase > 16) dashPhase = 0;
            repaintFrame();
        });
        dashTimer.start();

        initFrameMouseListeners();
    }

    public void reset() {
        final TiffReadMap map = viewer.map();
        setPreferredSize(new Dimension(map.dimX(), map.dimY()));
    }

    private void repaintFrame() {
        if (frameFromX != null && frameFromY != null && frameToX != null && frameToY != null) {
            repaint(); // перерисуем компонент полностью, или можно ограниченно
        }
    }
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;

        // draw TIFF fragment
        Rectangle clip = graphics.getClipBounds();
        if (clip != null) {
            clip = new Rectangle(clip);
            BufferedImage bi = viewer.loadFragment(clip);
            if (bi != null) g.drawImage(bi, clip.x, clip.y, null);
            else if (clip.width > 0 && clip.height > 0) {
                g.setPaint(ERROR_PAINT);
                g.fillRect(clip.x, clip.y, clip.width, clip.height);
            }
        }

        // draw selection frame
        if (frameFromX != null && frameToX != null) {
            int x = Math.min(frameFromX, frameToX);
            int y = Math.min(frameFromY, frameToY);
            int w = Math.max(Math.abs(frameToX - frameFromX), 1);
            int h = Math.max(Math.abs(frameToY - frameFromY), 1);

            Stroke oldStroke = g.getStroke();
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(
                    1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 4f}, dashPhase));
            g.drawRect(x, y, w, h);
            g.setStroke(oldStroke);

            // draw handle squares
            int[] xs = {x, x + w / 2, x + w};
            int[] ys = {y, y + h / 2, y + h};
            g.setColor(Color.WHITE);
            for (int cx : xs) for (int cy : ys)
                g.fillRect(cx - FRAME_HANDLE_SIZE /2, cy - FRAME_HANDLE_SIZE /2, FRAME_HANDLE_SIZE, FRAME_HANDLE_SIZE);
            g.setColor(Color.BLACK);
            for (int cx : xs) for (int cy : ys)
                g.drawRect(cx - FRAME_HANDLE_SIZE /2, cy - FRAME_HANDLE_SIZE /2, FRAME_HANDLE_SIZE, FRAME_HANDLE_SIZE);
        }
    }

    private void initFrameMouseListeners() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int mx = e.getX(), my = e.getY();

                FrameHandle handle = getHandleUnder(mx, my);
                if (handle != null) {
                    selectedHandle = handle;
                    resizingFrame = true;
                    movingFrame = draggingFrame = false;
                } else if (isInsideFrame(mx, my)) {
                    movingFrame = true;
                    dragOffsetX = mx - frameFromX;
                    dragOffsetY = my - frameFromY;
                    draggingFrame = resizingFrame = false;
                } else {
                    // start a new frame
                    draggingFrame = true;
                    resizingFrame = movingFrame = false;
                    frameFromX = frameToX = mx;
                    frameFromY = frameToY = my;
                }
            }


            @Override
            public void mouseReleased(MouseEvent e) {
                draggingFrame = resizingFrame = movingFrame = false;
                selectedHandle = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int mx = e.getX(), my = e.getY();

                if (draggingFrame) {
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
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int mx = e.getX(), my = e.getY();
                FrameHandle handle = getHandleUnder(mx, my);
                if (handle != null) setCursor(handle.getCursor());
                else if (isInsideFrame(mx, my)) setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                else setCursor(Cursor.getDefaultCursor());
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private void resizeFrame(FrameHandle handle, int mx, int my) {
        switch (handle) {
            case TOP_LEFT -> { frameFromX = mx; frameFromY = my; }
            case TOP_RIGHT -> { frameToX = mx; frameFromY = my; }
            case BOTTOM_LEFT -> { frameFromX = mx; frameToY = my; }
            case BOTTOM_RIGHT -> { frameToX = mx; frameToY = my; }
            case TOP -> frameFromY = my;
            case BOTTOM -> frameToY = my;
            case LEFT -> frameFromX = mx;
            case RIGHT -> frameToX = mx;
        }
    }

    private FrameHandle getHandleUnder(int x, int y) {
        if (frameFromX == null) return null;
        int fx = Math.min(frameFromX, frameToX), fy = Math.min(frameFromY, frameToY);
        int tx = Math.max(frameFromX, frameToX), ty = Math.max(frameFromY, frameToY);
        int s = FRAME_HANDLE_SIZE;

        Rectangle[] rects = new Rectangle[8];
        rects[0] = new Rectangle(fx - s/2, fy - s/2, s, s);               // TOP_LEFT
        rects[1] = new Rectangle(tx - s/2, fy - s/2, s, s);               // TOP_RIGHT
        rects[2] = new Rectangle(fx - s/2, ty - s/2, s, s);               // BOTTOM_LEFT
        rects[3] = new Rectangle(tx - s/2, ty - s/2, s, s);               // BOTTOM_RIGHT
        rects[4] = new Rectangle(fx + (tx - fx)/2 - s/2, fy - s/2, s, s); // TOP
        rects[5] = new Rectangle(fx + (tx - fx)/2 - s/2, ty - s/2, s, s); // BOTTOM
        rects[6] = new Rectangle(fx - s/2, fy + (ty - fy)/2 - s/2, s, s); // LEFT
        rects[7] = new Rectangle(tx - s/2, fy + (ty - fy)/2 - s/2, s, s); // RIGHT

        FrameHandle[] handles = FrameHandle.values();
        for (int i = 0; i < rects.length; i++)
            if (rects[i].contains(x, y)) return handles[i];
        return null;
    }

    private boolean isInsideFrame(int x, int y) {
        if (frameFromX == null) return false;
        int fx = Math.min(frameFromX, frameToX);
        int fy = Math.min(frameFromY, frameToY);
        int tx = Math.max(frameFromX, frameToX);
        int ty = Math.max(frameFromY, frameToY);
        return x >= fx && x <= tx && y >= fy && y <= ty;
    }

    private enum FrameHandle {
        TOP_LEFT(Cursor.NW_RESIZE_CURSOR),
        TOP_RIGHT(Cursor.NE_RESIZE_CURSOR),
        BOTTOM_LEFT(Cursor.SW_RESIZE_CURSOR),
        BOTTOM_RIGHT(Cursor.SE_RESIZE_CURSOR),
        TOP(Cursor.N_RESIZE_CURSOR),
        BOTTOM(Cursor.S_RESIZE_CURSOR),
        LEFT(Cursor.W_RESIZE_CURSOR),
        RIGHT(Cursor.E_RESIZE_CURSOR);

        private final int cursorType;
        FrameHandle(int type) { this.cursorType = type; }
        Cursor getCursor() { return Cursor.getPredefinedCursor(cursorType); }
    }

    private static TexturePaint createChessPaint(int size, Color c1, Color c2) {
        BufferedImage bi = new BufferedImage(size*2, size*2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(c1); g.fillRect(0, 0, size*2, size*2);
        g.setColor(c2); g.fillRect(size, 0, size, size); g.fillRect(0, size, size, size);
        g.dispose();
        return new TexturePaint(bi, new Rectangle(0,0,size*2,size*2));
    }
}
