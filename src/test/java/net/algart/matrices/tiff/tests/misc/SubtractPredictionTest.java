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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.TiffTools;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.util.Bytes;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

public class SubtractPredictionTest {
    // This code implements the same slow algorithm as the original DefaultTiffService.difference
    private static void simpleSubtractPrediction(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bytesPerSample = tile.bytesPerSample().orElseThrow();
        final int len = bytesPerSample * tile.samplesPerPixel();
        final boolean little = tile.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        final long xSize = tile.getSizeX();
        for (int k = data.length - bytesPerSample; k >= 0; k -= bytesPerSample) {
            if (k / len % xSize == 0) {
                continue;
            }
            int value = Bytes.toInt(data, k, bytesPerSample, little);
            value -= Bytes.toInt(data, k - len, bytesPerSample, little);
            Bytes.unpack(value, data, k, bytesPerSample, little);
        }
    }

    // This code implements the same slow algorithm as the original DefaultTiffService.undifference
    private static void simpleUnsubtractPrediction(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bytesPerSample = tile.bytesPerSample().orElseThrow();
        final int len = bytesPerSample * tile.samplesPerPixel();
        final boolean little = tile.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        final long xSize = tile.getSizeX();

        for (int k = 0; k <= data.length - bytesPerSample; k += bytesPerSample) {
            if (k / len % xSize == 0) {
                continue;
            }
            int value = Bytes.toInt(data, k, bytesPerSample, little);
            value += Bytes.toInt(data, k - len, bytesPerSample, little);
            Bytes.unpack(value, data, k, bytesPerSample, little);
        }
    }

    public static void main(String[] args) throws TiffException {
        if (args.length < 5) {
            System.out.println("Usage:");
            System.out.println("    " + SubtractPredictionTest.class.getName()
                    + " tileSizeX tileSizeY numberOfChannels uint8|uint16|uint32 numberOfTests");
            return;
        }

        final int tileSizeX = Integer.parseInt(args[0]);
        final int tileSizeY = Integer.parseInt(args[1]);
        final int numberOfChannels = Integer.parseInt(args[2]);
        final TiffSampleType sampleType = TiffSampleType.valueOf(args[3].toUpperCase());
        final int numberOfTests = Integer.parseInt(args[4]);
        TiffIFD ifd = new TiffIFD()
                .putTileSizes(tileSizeX, tileSizeY)
                .putPixelInformation(numberOfChannels, sampleType);
        TiffTile tile = TiffMap.newResizable(ifd).getOrNew(0, 0);

        Random random = new Random(157);
        for (int test = 0; test < numberOfTests; test++) {
            System.out.printf("%nTest #%d...%n", test);
            byte[] data = new byte[tile.getSizeInBytes()];
            for (int k = 0; k < data.length; k++) {
                data[k] = (byte) random.nextInt();
            }
            byte[] predicted = data.clone();
            tile.setDecodedData(predicted);
            long t1 = System.nanoTime();
            TiffTools.subtractPrediction(tile);
            long t2 = System.nanoTime();
            System.out.printf("subtractPrediction:            %.3f ms, %.3f MB/sec%n",
                    (t2 - t1) * 1e-6, data.length / 1048576.0 / ((t2 - t1) * 1e-9));
            assert predicted == tile.getDecodedData();

            byte[] simplePredicted = data.clone();
            tile.setDecodedData(simplePredicted);
            t1 = System.nanoTime();
            simpleSubtractPrediction(tile);
            t2 = System.nanoTime();
            System.out.printf("subtractPrediction (simple):   %.3f ms, %.3f MB/sec%n",
                    (t2 - t1) * 1e-6, data.length / 1048576.0 / ((t2 - t1) * 1e-9));
            assert simplePredicted == tile.getDecodedData();
            if (!Arrays.equals(predicted, simplePredicted)) {
                throw new AssertionError("Bug in subtractPrediction");
            }

            tile.setDecodedData(predicted);
            t1 = System.nanoTime();
            TiffTools.unsubtractPrediction(tile);
            t2 = System.nanoTime();
            System.out.printf("unsubtractPrediction:          %.3f ms, %.3f MB/sec%n",
                    (t2 - t1) * 1e-6, data.length / 1048576.0 / ((t2 - t1) * 1e-9));
            if (!Arrays.equals(data, predicted)) {
                throw new AssertionError("Bug in unsubtractPrediction");
            }

            tile.setDecodedData(simplePredicted);
            t1 = System.nanoTime();
            simpleUnsubtractPrediction(tile);
            t2 = System.nanoTime();
            System.out.printf("unsubtractPrediction (simple): %.3f ms, %.3f MB/sec%n",
                    (t2 - t1) * 1e-6, data.length / 1048576.0 / ((t2 - t1) * 1e-9));
            if (!Arrays.equals(data, simplePredicted)) {
                throw new AssertionError("Bug in simpleUnsubtractPrediction");
            }

        }
        System.out.println("O'k");
    }
}
