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

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

public class ClipboardTools {
    public static void copyImageToClipboard(BufferedImage image) {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[]{DataFlavor.imageFlavor};
                    }

                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return DataFlavor.imageFlavor.equals(flavor);
                    }

                    @SuppressWarnings("NullableProblems")
                    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                        if (!isDataFlavorSupported(flavor)) {
                            throw new UnsupportedFlavorException(flavor);
                        }
                        return image;
                    }
                }, null);
    }

    public static BufferedImage loadImageFromClipboard() throws IOException, UnsupportedFlavorException {
        final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            final Object data = clipboard.getData(DataFlavor.imageFlavor);
            if (data instanceof BufferedImage bi) {
                return bi;
            } else if (data instanceof Image image) {
                BufferedImage bi = new BufferedImage(image.getWidth(null), image.getHeight(null),
                        BufferedImage.TYPE_INT_ARGB);
                //TODO!! Log
                //TODO!! detect monochrome?
                Graphics2D g = bi.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
                return bi;
            }
        }
        return null;
    }
}
