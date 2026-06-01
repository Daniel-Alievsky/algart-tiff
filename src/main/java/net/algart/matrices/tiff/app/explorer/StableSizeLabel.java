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

class StableSizeLabel extends JLabel {
    private int reservedWidth = 0;
    private int reservedHeight = 0;

    public StableSizeLabel(String text) {
        super(text);
    }

    public void resetReservedSize() {
        reservedWidth = 0;
        reservedHeight = 0;
    }

    public void reserveCurrentSize() {
        Dimension dimension = super.getPreferredSize();
        reservedWidth = dimension.width;
        reservedHeight = dimension.height;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension dimension = new Dimension(super.getPreferredSize());
        dimension.width = Math.max(dimension.width, reservedWidth);
        dimension.height = Math.max(dimension.height, reservedHeight);
        return dimension;
    }
}
