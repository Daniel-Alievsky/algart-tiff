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

    public JTiffPanel(TiffImageViewer viewer) {
        this.viewer = Objects.requireNonNull(viewer, "Null viewer");
        reset();
    }

    public void reset() {
        final TiffReadMap map = viewer.map();
        setPreferredSize(new Dimension(map.dimX(), map.dimY()));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;
        Rectangle clip = graphics.getClipBounds();
        if (clip == null) {
            return;
        }
        clip = new Rectangle(clip);
        // - loadFragment changes the rectangle
        final BufferedImage bi = viewer.loadFragment(clip);
        if (bi != null) {
            g.drawImage(bi, clip.x, clip.y, null);
        } else if (clip.width > 0 && clip.height > 0) {
            g.setPaint(ERROR_PAINT);
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
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
