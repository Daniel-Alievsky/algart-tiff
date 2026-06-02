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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MakeAndPrintTiffPixelsTest {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tif bit|unit8|int8|uint16|int16|uint32|int32|float|double " +
                            "width height value-to-fill%n",
                    MakeAndPrintTiffPixelsTest.class.getName());
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final TiffSampleType sampleType = TiffSampleType.valueOf(args[startArgIndex++].toUpperCase());
        final int dimX = Integer.parseInt(args[startArgIndex++]);
        final int dimY = Integer.parseInt(args[startArgIndex++]);
        final double filler = Double.parseDouble(args[startArgIndex]);
        Object values = makeCircleSamples(sampleType, dimX, dimY, filler);
        try (TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.CREATE)) {
            writer.newFixedMap(TiffIFD.newStrippedIFD()
                    .putImageDimensions(dimX, dimY)
                    .putPixelInformation(1, sampleType));
            //TODO!!
        }


    }

    private static Object makeCircleSamples(
            TiffSampleType type,
            int dimX,
            int dimY,
            double filler) {
        final int matrixSize = dimX * dimY;
        switch (type) {
            case BIT -> {
                boolean[] samples = new boolean[matrixSize];
                    for (int y = 0, disp = 0; y < dimY; y++) {
                        for (int x = 0; x < dimX; x++, disp++) {
                            samples[disp] = patternValue(x, y, dimX, dimY, filler, false) > 0.5;
                        }
                    }
                return samples;
            }
            case UINT8, INT8 -> {
                byte[] samples = new byte[matrixSize];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    for (int x = 0; x < dimX; x++, disp++) {
                            samples[disp] = (byte) (patternValue(x, y, dimX, dimY, filler, type.isSigned()) * 255.0);
                        }
                    }
                return samples;
            }
            case UINT16, INT16 -> {
                short[] samples = new short[matrixSize];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        samples[disp] = (byte) (patternValue(x, y, dimX, dimY, filler, type.isSigned()) * 65535.0);
                    }
                }
                return samples;
            }
            case INT32, UINT32 -> {
                int[] samples = new int[matrixSize];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        samples[disp] = (byte) (patternValue(x, y, dimX, dimY, filler, type.isSigned()) * 0xFFFFFFFFL);
                    }
                }
                return samples;

            }
            case FLOAT -> {
                float[] samples = new float[matrixSize];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        samples[disp] = (float) patternValue(x, y, dimX, dimY, filler, false);
                    }
                }
                return samples;
            }
            case DOUBLE -> {
                double[] samples = new double[matrixSize];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        samples[disp] = patternValue(x, y, dimX, dimY, filler, false);
                    }
                }
                return samples;

            }
            default -> throw new UnsupportedOperationException("Unsupported type = " + type);
        }
    }

    private static double patternValue(
            long x,
            long y,
            long dimX,
            long dimY,
            double value,
            boolean signed) {
        x -= dimX / 2;
        y -= dimY / 2;
        double r = Math.sqrt(x * x + y * y);
        double max = 0.7 * Math.max(dimX / 2, dimY / 2);
        double result = value * (1.0 - r / max);
        return signed ? result : Math.max(result, 0.0);
    }
}
