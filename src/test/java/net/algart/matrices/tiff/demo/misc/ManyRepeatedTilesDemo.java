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

package net.algart.matrices.tiff.demo.misc;

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class ManyRepeatedTilesDemo {
    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        boolean append = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-append")) {
            append = true;
            startArgIndex++;
        }
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        boolean color = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-color")) {
            color = true;
            startArgIndex++;
        }
        boolean gradient = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-gradient")) {
            gradient = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 4) {
            System.out.println("Usage:");
            System.out.printf("    %s [-append] [-bigTiff] [-color] [-gradient] " +
                            "target.tiff x-repetitions y-repetitions 0xXXXXXX " +
                            "bit|unit8|int8|uint16|int16|uint32|int32|float|double [compression]%n",
                    ManyRepeatedTilesDemo.class.getName());
            System.out.println("0xXXXXXX is the color to fill the TIFF");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int xCount = Integer.parseInt(args[startArgIndex++]);
        final int yCount = Integer.parseInt(args[startArgIndex++]);
        final int numberOfChannels = color ? 3 : 1;
        final Color colorValue = Color.decode(args[startArgIndex++]);
        final TiffSampleType sampleType = TiffSampleType.valueOf(args[startArgIndex++].toUpperCase());
        final TagCompression compression = startArgIndex < args.length ?
                TagCompression.valueOf(args[startArgIndex]) : TagCompression.NONE;
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setBigTiff(bigTiff);
            writer.create(append);
            final TiffIFD ifd = TiffIFD.newTiledIFD()
                    .putTileSizes(256, 256)
                    .putPixelInformation(numberOfChannels, sampleType)
                    .putCompression(compression);
            final int tileSizeX = ifd.getTileSizeX();
            final int tileSizeY = ifd.getTileSizeY();
            ifd.putImageDimensions((long) tileSizeX * xCount, (long) tileSizeY * yCount);
            final TiffWriteMap map = writer.newFixedMap(ifd);
            System.out.printf("%s TIFF %s %dx%d: %d tiles %dx%d...%n",
                    append ? "Appending" : "Writing", targetFile, map.dimX(), map.dimY(),
                    (long) xCount * (long) yCount, tileSizeX, tileSizeY);

            long t1 = System.nanoTime();
            if (gradient) {
                map.writeBlankRepeatingTile(m -> makePatternSamples(m, sampleType, colorValue));
            } else {
                map.writeBlank(colorValue);
            }
            long t2 = System.nanoTime();
            System.out.printf(Locale.US, "All tiles (duplicates) written in %.3f ms%n",
                    (t2 - t1) * 1e-6);
        }
        System.out.println("Done");
    }

    private static void makePatternSamples(
            Matrix<UpdatablePArray> matrix,
            TiffSampleType type,
            Color color) {
        int numberOfChannels = matrix.dim32(2);
        int dimX = matrix.dimX32();
        int dimY = matrix.dimY32();
        final int matrixSize = dimX * dimY;
        switch (type) {
            case BIT -> {
                boolean[] channels = new boolean[matrixSize * numberOfChannels];
                for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                    for (int y = 0; y < dimY; y++) {
                        for (int x = 0; x < dimX; x++, disp++) {
                            channels[disp] = patternValue(color, c, x, y, dimX, dimY) > 0.5;
                        }
                    }
                }
                matrix.array().setData(0, channels);
            }
            case UINT8, INT8 -> {
                byte[] channels = new byte[matrixSize * numberOfChannels];
                for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                    for (int y = 0; y < dimY; y++) {
                        for (int x = 0; x < dimX; x++, disp++) {
                            channels[disp] = (byte) (patternValue(color, c, x, y, dimX, dimY) * 255.0);
                        }
                    }
                }
                matrix.array().setData(0, channels);
            }
            case UINT16, INT16 -> {
                short[] channels = new short[matrixSize * numberOfChannels];
                for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                    for (int y = 0; y < dimY; y++) {
                        for (int x = 0; x < dimX; x++, disp++) {
                            channels[disp] = (short) (patternValue(color, c, x, y, dimX, dimY) * 65535.0);
                        }
                    }
                }
                matrix.array().setData(0, channels);
            }
            case INT32, UINT32 -> {
                int[] channels = new int[matrixSize * numberOfChannels];
                for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                    for (int y = 0; y < dimY; y++) {
                        for (int x = 0; x < dimX; x++, disp++) {
                            channels[disp] = (int) (patternValue(color, c, x, y, dimX, dimY) * 0xFFFFFFFFL);
                        }
                    }
                }
                matrix.array().setData(0, channels);
            }
            case FLOAT -> {
                float[] channels = new float[matrixSize * numberOfChannels];
                for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                    for (int y = 0; y < dimY; y++) {
                        for (int x = 0; x < dimX; x++, disp++) {
                            channels[disp] = (float) patternValue(color, c, x, y, dimX, dimY);
                        }
                    }
                }
                matrix.array().setData(0, channels);
            }
            case DOUBLE -> {
                double[] channels = new double[matrixSize * numberOfChannels];
                for (int c = 0, disp = 0; c < numberOfChannels; c++) {
                    for (int y = 0; y < dimY; y++) {
                        for (int x = 0; x < dimX; x++, disp++) {
                            channels[disp] = patternValue(color, c, x, y, dimX, dimY);
                        }
                    }
                }
                matrix.array().setData(0, channels);
            }
            default -> throw new UnsupportedOperationException("Unsupported type = " + type);
        }
    }

    private static double patternValue(
            Color color,
            int channel,
            long x,
            long y,
            long dimX,
            long dimY) {
        double value = switch (channel) {
            case 0 -> color.getRed() / 255.0;
            case 1 -> color.getGreen() / 255.0;
            default -> color.getBlue() / 255.0;
        };
        return value * (double) (x * x + y * y) / (double) (dimX * dimX + dimY * dimY);
    }
}
