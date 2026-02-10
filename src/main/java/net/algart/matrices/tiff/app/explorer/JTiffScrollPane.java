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
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class JTiffScrollPane extends JScrollPane {
    private Point panStart = null;
    private boolean shiftDown = false;

    public JTiffScrollPane(Component view) {
        super(view);
        setWheelScrollingEnabled(false);
        // - default implementation supports vertical scroll only, this is not a good idea
        initPanListener();
    }

    private void initPanListener() {
        JViewport viewport = getViewport();
        Component view = viewport.getView();


        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && shiftDown) {
                    panStart = e.getPoint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    panStart = null;
                    if (!shiftDown) {
                        view.setCursor(Cursor.getDefaultCursor());
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panStart != null) {
                    Point viewPos = viewport.getViewPosition();
                    int dx = panStart.x - e.getX();
                    int dy = panStart.y - e.getY();

                    viewPos.translate(dx, dy);

                    Dimension viewSize = view.getSize();
                    Dimension extentSize = viewport.getExtentSize();
                    viewPos.x = Math.max(0, Math.min(viewPos.x, viewSize.width - extentSize.width));
                    viewPos.y = Math.max(0, Math.min(viewPos.y, viewSize.height - extentSize.height));

                    viewport.setViewPosition(viewPos);
                }
            }
        };

        view.addMouseListener(adapter);
        view.addMouseMotionListener(adapter);

        view.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
                    if (!shiftDown) {
                        shiftDown = true;
                        view.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    }
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
                    shiftDown = false;
                    if (panStart == null) {
                        view.setCursor(Cursor.getDefaultCursor());
                    }
                }
            }
        });
        view.setFocusable(true);
    }
}
