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

package net.algart.matrices.tiff.app;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

import net.algart.matrices.tiff.tiles.TiffReadMap;

class TiffImageViewPanel extends JComponent {
    static final System.Logger LOG = System.getLogger(TiffImageViewPanel.class.getName());
    private final TiffReadMap map;

    public TiffImageViewPanel(TiffReadMap map) {
        this.map = map;
        setPreferredSize(new Dimension(map.dimX(), map.dimY()));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;

        Rectangle clip = g.getClipBounds();
        int fromX = clip.x;
        int fromY = clip.y;
        int toX = clip.x + clip.width;
        int toY = clip.y + clip.height;

        toX = Math.min(toX, map.dimX());
        toY = Math.min(toY, map.dimY());

        try {
            BufferedImage fragment = map.readBufferedImage(fromX, fromY, toX - fromX, toY - fromY);
            LOG.log(System.Logger.Level.DEBUG, "Drawing fragment %dx%d starting at (%d,%d)"
                    .formatted(toX - fromX, toY - fromY, fromX, fromY));
            g.drawImage(fragment, fromX, fromY, null);
        } catch (IOException e) {
            e.printStackTrace();
            g.setColor(Color.RED);
            g.fillRect(fromX, fromY, toX - fromX, toY - fromY);
        }
    }
}
