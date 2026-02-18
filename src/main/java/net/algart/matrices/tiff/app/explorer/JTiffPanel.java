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

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

import net.algart.matrices.tiff.tiles.TiffReadMap;

class JTiffPanel extends JComponent {
    private static final Paint ERROR_PAINT = createChessPaint(
            8,
            new Color(255, 0, 0),
            new Color(255, 128, 128));

    private final TiffImageViewer viewer;

    // --- поля для рамки выделения ---
    private Integer frameFromX = null;
    private Integer frameFromY = null;
    private Integer frameToX = null;
    private Integer frameToY = null;

    private boolean draggingFrame = false;   // рисуем новую рамку
    private boolean resizingFrame = false;   // тянем за угол/сторону
    private int dragOffsetX, dragOffsetY;    // для перемещения/редактирования

    // таймер для «бегущего» пунктирного контура
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

        // --- отрисовка TIFF-фрагмента ---
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

        // --- отрисовка рамки ---
        if (frameFromX != null && frameFromY != null && frameToX != null && frameToY != null) {
            int x = Math.min(frameFromX, frameToX);
            int y = Math.min(frameFromY, frameToY);
            int w = Math.abs(frameToX - frameFromX);
            int h = Math.abs(frameToY - frameFromY);

            Stroke oldStroke = g.getStroke();
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(
                    1f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,
                    10f,
                    new float[]{4f, 4f},
                    dashPhase
            ));
            g.drawRect(x, y, w, h);
            g.setStroke(oldStroke);

            // --- реперные точки ---
            int[] xs = {x, x + w / 2, x + w};
            int[] ys = {y, y + h / 2, y + h};
            g.setColor(Color.WHITE);
            for (int cx : xs) {
                for (int cy : ys) {
                    g.fillRect(cx - 3, cy - 3, 6, 6);
                }
            }
            g.setColor(Color.BLACK);
            for (int cx : xs) {
                for (int cy : ys) {
                    g.drawRect(cx - 3, cy - 3, 6, 6);
                }
            }
        }
    }

    private void initFrameMouseListeners() {
        MouseAdapter adapter = new MouseAdapter() {
            private int startX, startY;

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int mx = e.getX();
                    int my = e.getY();
                    if (isOverHandle(mx, my)) {
                        resizingFrame = true;
                        dragOffsetX = mx - frameToX;
                        dragOffsetY = my - frameToY;
                    } else {
                        draggingFrame = true;
                        startX = frameFromX = mx;
                        startY = frameFromY = my;
                        frameToX = mx;
                        frameToY = my;
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int mx = e.getX();
                int my = e.getY();
                if (draggingFrame) {
                    frameToX = mx;
                    frameToY = my;
                    repaintFrame();
                } else if (resizingFrame) {
                    frameToX = mx - dragOffsetX;
                    frameToY = my - dragOffsetY;
                    repaintFrame();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                draggingFrame = false;
                resizingFrame = false;
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    // проверяем, попали ли мы на реперную точку
    private boolean isOverHandle(int x, int y) {
        if (frameFromX == null) return false;
        int fx = Math.min(frameFromX, frameToX);
        int fy = Math.min(frameFromY, frameToY);
        int w = Math.abs(frameToX - frameFromX);
        int h = Math.abs(frameToY - frameFromY);
        int[] xs = {fx, fx + w / 2, fx + w};
        int[] ys = {fy, fy + h / 2, fy + h};
        for (int cx : xs) {
            for (int cy : ys) {
                Rectangle r = new Rectangle(cx - 3, cy - 3, 6, 6);
                if (r.contains(x, y)) return true;
            }
        }
        return false;
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
