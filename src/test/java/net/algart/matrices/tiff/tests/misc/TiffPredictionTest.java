/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.arrays.JArrays;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.data.TiffPrediction;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

public class TiffPredictionTest {
    // This code implements the same slow algorithm as the original DefaultTiffService.difference
    private static void simpleSubtractPrediction(TiffTile tile) {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bytesPerSample = tile.bytesPerSample().orElseThrow();
        final int len = bytesPerSample * tile.samplesPerPixel();
        final ByteOrder byteOrder = tile.byteOrder();
        final long xSize = tile.getSizeX();
        for (int k = data.length - bytesPerSample; k >= 0; k -= bytesPerSample) {
            if (k / len % xSize == 0) {
                continue;
            }
            int value = (int) JArrays.getBytes8(data, k, bytesPerSample, byteOrder);
            value -= (int) JArrays.getBytes8(data, k - len, bytesPerSample, byteOrder);
            JArrays.setBytes8(data, k, value, bytesPerSample, byteOrder);
        }
    }

    // This code implements the same slow algorithm as the original DefaultTiffService.undifference
    private static void simpleUnsubtractPrediction(TiffTile tile) {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bytesPerSample = tile.bytesPerSample().orElseThrow();
        final int len = bytesPerSample * tile.samplesPerPixel();
        final ByteOrder byteOrder = tile.byteOrder();
        final long xSize = tile.getSizeX();

        for (int k = 0; k <= data.length - bytesPerSample; k += bytesPerSample) {
            if (k / len % xSize == 0) {
                continue;
            }
            int value = (int) JArrays.getBytes8(data, k, bytesPerSample, byteOrder);
            value += (int) JArrays.getBytes8(data, k - len, bytesPerSample, byteOrder);
            JArrays.setBytes8(data, k, value, bytesPerSample, byteOrder);
        }
    }

    public static void main(String[] args) throws TiffException {
        if (args.length < 4) {
            System.out.println("Usage:");
            System.out.println("    " + TiffPredictionTest.class.getName()
                    + " tileSizeX tileSizeY numberOfChannels numberOfTests");
            return;
        }

        final int tileSizeX = Integer.parseInt(args[0]);
        final int tileSizeY = Integer.parseInt(args[1]);
        final int numberOfChannels = Integer.parseInt(args[2]);
        final int numberOfTests = Integer.parseInt(args[3]);

        Random random = new Random(157);
        for (int test = 0; test < numberOfTests; test++) {
            System.out.printf("%nTest #%d...%n", test);
            for (TiffSampleType sampleType : TiffSampleType.values()) {
                System.out.printf("Sample type: %s%n", sampleType);

                TiffIFD ifd = new TiffIFD()
                        .putTileSizes(tileSizeX, tileSizeY)
                        .putPixelInformation(numberOfChannels, sampleType);
                TiffTile tile = new TiffMap(ifd, true).getOrNew(0, 0);
                byte[] data = new byte[tile.getSizeInBytes()];
                for (int k = 0; k < data.length; k++) {
                    data[k] = test == 0 ? (byte) k : (byte) random.nextInt();
                }
                byte[] predicted = data.clone();
                tile.setDecodedData(predicted);
                long t1 = System.nanoTime();
                TiffPrediction.subtractPrediction(tile);
                long t2 = System.nanoTime();
                assert predicted == tile.getDecodedData();

                byte[] simplePredicted = data.clone();
                long t3 = t1, t4 = t1;
                if (tile.bitsPerPixel() > 1 && tile.bitsPerSample() <= 32) {
                    tile.setDecodedData(simplePredicted);
                    t3 = System.nanoTime();
                    simpleSubtractPrediction(tile);
                    t4 = System.nanoTime();
                    assert simplePredicted == tile.getDecodedData();
                    if (!Arrays.equals(predicted, simplePredicted)) {
                        throw new AssertionError("Bug in subtractPrediction");
                    }
                }

                tile.setDecodedData(predicted);
                long t5 = System.nanoTime();
                TiffPrediction.unsubtractPrediction(tile);
                long t6 = System.nanoTime();
                System.out.printf("subtractPrediction:            %.3f ms, %.3f MB/sec%n",
                        (t2 - t1) * 1e-6, data.length / 1048576.0 / ((t2 - t1) * 1e-9));
                System.out.printf("unsubtractPrediction:          %.3f ms, %.3f MB/sec%n",
                        (t6 - t5) * 1e-6, data.length / 1048576.0 / ((t6 - t5) * 1e-9));
                if (!Arrays.equals(data, predicted)) {
                    throw new AssertionError("Bug in unsubtractPrediction");
                }

                if (tile.bitsPerPixel() > 1 && tile.bitsPerSample() <= 32) {
                    tile.setDecodedData(simplePredicted);
                    long t7 = System.nanoTime();
                    simpleUnsubtractPrediction(tile);
                    long t8 = System.nanoTime();
                    System.out.printf("subtractPrediction (simple):   %.3f ms, %.3f MB/sec%n",
                            (t4 - t3) * 1e-6, data.length / 1048576.0 / ((t4 - t3) * 1e-9));
                    System.out.printf("unsubtractPrediction (simple): %.3f ms, %.3f MB/sec%n",
                            (t8 - t7) * 1e-6, data.length / 1048576.0 / ((t8 - t7) * 1e-9));
                    if (!Arrays.equals(data, simplePredicted)) {
                        throw new AssertionError("Bug in simpleUnsubtractPrediction");
                    }
                }
            }
        }
        System.out.println("O'k");
    }
}
