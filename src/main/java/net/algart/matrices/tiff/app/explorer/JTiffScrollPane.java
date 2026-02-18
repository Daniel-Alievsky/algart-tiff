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
import java.awt.event.*;

class JTiffScrollPane extends JScrollPane {
    private final JTiffPanel tiffPanel;

    private Point panStart = null;
    private boolean shiftDown = false;

    public JTiffScrollPane(JTiffPanel tiffPanel) {
        super(tiffPanel);
        this.tiffPanel = tiffPanel;
        setWheelScrollingEnabled(false);
        // - default implementation supports vertical scroll only, this is not a good idea
        initPanListener();
    }

    private void enterDraggingMode() {
        if (!shiftDown) {
            shiftDown = true;
            tiffPanel.setMouseHandleEnabled(false);
            tiffPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
    }

    private void leaveDraggingMode() {
        shiftDown = false;
        tiffPanel.setMouseHandleEnabled(true);
        if (panStart == null) {
            tiffPanel.setCursor(Cursor.getDefaultCursor());
        }
    }

    private void initPanListener() {
        JViewport viewport = getViewport();

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
                        tiffPanel.setCursor(Cursor.getDefaultCursor());
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

                    Dimension viewSize = tiffPanel.getSize();
                    Dimension extentSize = viewport.getExtentSize();
                    viewPos.x = Math.max(0, Math.min(viewPos.x, viewSize.width - extentSize.width));
                    viewPos.y = Math.max(0, Math.min(viewPos.y, viewSize.height - extentSize.height));

                    viewport.setViewPosition(viewPos);
                }
            }
        };

        tiffPanel.addMouseListener(adapter);
        tiffPanel.addMouseMotionListener(adapter);

        tiffPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
                    enterDraggingMode();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
                    leaveDraggingMode();
                }
            }
        });
        tiffPanel.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                leaveDraggingMode();
            }
        });
        tiffPanel.setFocusable(true);
        // - necessary to allow tiffPanel.addKeyListener;
        // the possible call:
        //      SwingUtilities.invokeLater(() -> tiffPanel.requestFocusInWindow());
        // but it is unnecessary, because tiffPanel is the only focusable element in the frame
    }
}
