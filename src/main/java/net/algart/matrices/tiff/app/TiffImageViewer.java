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

import net.algart.arrays.PackedBitArraysPer8;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffReadMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

class TiffImageViewer {
    private static final int MAX_IMAGE_DIM = 16000;
    // 16000 * 16000 * 4 channels RGBA * 16 bit/channel < Integer.MAX_VALUE

    private final TiffExplorer app;
    private final TiffReader reader;
    private final int index;

    private TiffReadMap map = null;
    private int numberOfImages;
    private int dimX;
    private int dimY;
    private BufferedImage bi = null;

    public TiffImageViewer(TiffExplorer app, Path tiffFile, int index) throws IOException {
        this.app = Objects.requireNonNull(app);
        Objects.requireNonNull(tiffFile);
        this.reader = new TiffReaderWithGrid(tiffFile, app.viewTileGrid);
        this.index = index;
    }

    public void dispose() throws IOException {
        this.reader.close();
    }

    public void show() throws IOException {
        openMap();
//        if (!confirm()) {
//            return;
//        }
//        loadImage();
        showWindow();
    }

    private void openMap() throws IOException {
        map = reader.map(index);
        numberOfImages = reader.numberOfImages();
        dimX = map.dimX();
        dimY = map.dimY();
    }

    private boolean confirm() {
        if (dimX > MAX_IMAGE_DIM || dimY > MAX_IMAGE_DIM) {
            dimX = Math.min(dimX, MAX_IMAGE_DIM);
            dimY = Math.min(dimY, MAX_IMAGE_DIM);
            int choice = JOptionPane.showConfirmDialog(
                    app.frame,
                    (
                            "The image is too large to display: %d×%d pixels.%n" +
                                    "We will show only its part %d×%d.%n" +
                                    "Do you want to continue?"
                    ).formatted(map.dimX(), map.dimY(), dimX, dimY),
                    "Large Image Warning",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            return choice == JOptionPane.YES_OPTION;
        }
        return true;
    }

    private void loadImage() throws IOException {
//        bi = map.readBufferedImage(0, 0, dimX, dimY);
    }

    private void showWindow() {
        JFrame imgFrame = new JFrame("TIFF Image #" + index + " from " + numberOfImages +
                " images (" + dimX + "x" + dimY +
                (dimX == map.dimX() && dimY == map.dimY() ?
                        "" :
                        " from " + map.dimX() + "x" + map.dimY()) +
                ")") {
            @Override
            public void dispose() {
                super.dispose();
                try {
                    TiffExplorer.LOG.log(System.Logger.Level.INFO, "Closing " + reader);
                    TiffImageViewer.this.dispose();
                } catch (IOException e) {
                    app.showErrorMessage(e, "Error closing TIFF");
                }
            }
        };
        imgFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        TiffImageViewPanel panel = new TiffImageViewPanel(map);
        imgFrame.add(new JScrollPane(panel));
        imgFrame.pack();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension frameSize = imgFrame.getSize();
        frameSize.width = Math.min(frameSize.width, screenSize.width - 10);
        frameSize.height = Math.min(frameSize.height, screenSize.height - 50);
        // - ensure that scroll bar and other elements will be visible even for very large images
        imgFrame.setSize(frameSize);

        imgFrame.setLocationRelativeTo(app.frame);
        imgFrame.setVisible(true);
    }

    private static class TiffReaderWithGrid extends TiffReader {
        private final boolean viewTileGrid;

        TiffReaderWithGrid(Path tiffFile, boolean viewTileGrid) throws IOException {
            super(tiffFile);
            this.viewTileGrid = viewTileGrid;
        }

        @Override
        public TiffTile readTile(TiffTileIndex tileIndex) throws IOException {
            final TiffTile tile = super.readTile(tileIndex);
            if (viewTileGrid && tile.map().isTiled()) {
                addTileBorder(tile);
            }
            return tile;
        }

        private static void addTileBorder(TiffTile tile) {
            if (!tile.isSeparated()) {
                throw new AssertionError("Tile is not separated");
            }
            byte[] decoded = tile.getDecodedData();
            final int sample = tile.bitsPerSample();
            final int sizeX = tile.getSizeX() * sample;
            final int sizeY = tile.getSizeY();
            final int sizeInBits = sizeX * sizeY;
            for (int c = 0; c < tile.samplesPerPixel(); c++) {
                int disp = c * sizeInBits;
                PackedBitArraysPer8.fillBits(decoded, disp, sizeX, false);
                PackedBitArraysPer8.fillBits(decoded, disp + sizeInBits - sizeX, sizeX, false);
                for (int k = 0; k < sizeY; k++, disp += sizeX) {
                    PackedBitArraysPer8.fillBits(decoded, disp, sample, false);
                    PackedBitArraysPer8.fillBits(decoded, disp + sizeX - sample, sample, false);
                }
            }
        }
    }
}
