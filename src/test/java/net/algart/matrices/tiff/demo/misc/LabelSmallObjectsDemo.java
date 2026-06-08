/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2007-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.arrays.*;
import net.algart.io.MatrixIO;
import net.algart.math.functions.RectangularFunc;
import net.algart.matrices.scanning.ConnectedObjectScanner;
import net.algart.matrices.scanning.ConnectivityType;
import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class LabelSmallObjectsDemo {

    private static Matrix<? extends BitArray> removeTooSmall(Matrix<? extends BitArray> objects, long minArea) {
        final Matrix<UpdatableBitArray> result = Matrices.clone(Arrays.SMM, objects).cast(UpdatableBitArray.class);
        ConnectedObjectScanner scanner = ConnectedObjectScanner.getStacklessDepthFirstScanner(
                result, ConnectivityType.STRAIGHT_AND_DIAGONAL);
        scanner.clearAllBySizes(null, result, minArea, Long.MAX_VALUE);
        return result;
    }

    private static Matrix<? extends PArray> buildLabels(Matrix<? extends BitArray> objects) {
        final Matrix<UpdatableIntArray> result = Arrays.SMM.newIntMatrix(objects.dimensions());
        final int[] labels = result.array().jaInt();
        // - probably a reference to internal Java array
        final Matrix<UpdatableBitArray> clone = Matrices.clone(Arrays.SMM, objects).cast(UpdatableBitArray.class);
        ConnectedObjectScanner scanner = ConnectedObjectScanner.getStacklessDepthFirstScanner(
                clone, ConnectivityType.STRAIGHT_AND_DIAGONAL);
        long[] coordinates = new long[objects.dimCount()]; // zero-filled
        class Painter implements ConnectedObjectScanner.ElementVisitor {
            private int currentLabel = 1;

            public void visit(long[] coordinatesInMatrix, long indexInArray) {
                labels[(int) (indexInArray)] = currentLabel;
            }
        }
        Painter painter = new Painter();
        while (scanner.nextUnitBit(coordinates)) {
            scanner.clear(null, painter, coordinates, false);
            painter.currentLabel++;
        }
        return painter.currentLabel <= 65536 ?
                Matrices.asFuncMatrix(RectangularFunc.IDENTITY, ShortArray.class, result) :
                result;
    }

    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 3) {
            System.out.printf("Usage: %s " +
                            "source_image.jpg/png/bmp result.tiff threshold [minArea]%n",
                    LabelSmallObjectsDemo.class.getName());
            System.out.println("Here:");
            System.out.println("  threshold is a value in 0..1 range)");
            System.out.println("  min_area is minimal required number of pixels in the connected object");
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);
        final Path targetFile = Paths.get(args[startArgIndex + 1]);
        final double threshold = Double.parseDouble(args[startArgIndex + 2]);
        final long minArea = startArgIndex + 3 < args.length ? Long.parseLong(args[startArgIndex + 3]) : 0;
        System.out.printf("Loading image %s...%n", sourceFile.toAbsolutePath().normalize());
        final List<Matrix<UpdatablePArray>> source = MatrixIO.readImage(sourceFile);
        final Matrix<? extends PArray> intensity = ColorMatrices.toRGBIntensity(source);
        final Matrix<UpdatableBitArray> binary = Matrix.newBitMatrix(intensity.dimensions());
        final double scaledThreshold = threshold * intensity.maxPossibleValue();
        System.out.printf("Applying threshold %f...%n", scaledThreshold);
        Matrices.applyFunc(
                RectangularFunc.getInstance(0, scaledThreshold, 0, 1),
                binary, intensity);
        Matrix<? extends BitArray> filtered = binary;
        if (minArea > 0) {
            System.out.printf("Scanning image to remove objects < %d pixels...%n", minArea);
            filtered = removeTooSmall(binary, minArea);
        }
        System.out.printf("Scanning image to label objects...%n");
        Matrix<? extends PArray> labels = buildLabels(filtered);
        System.out.printf("Writing TIFF %s...%n", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.CREATE)) {
            writer.setCompressionQuality(0.5);
            writer.newFixedMap(TiffIFD.newTiledIFD(TagCompression.JPEG, source)
                            .putDescription("Source image"))
                    .writeChannels(source);
            writer.newFixedMap(TiffIFD.newTiledIFD(TagCompression.DEFLATE, binary)
                            .putCompression(TagCompression.DEFLATE)
                            .putDescription("Binary image after thresholding by %s (%.3f)"
                                    .formatted(scaledThreshold, threshold)))
                    .writeMatrix(binary);
            // - for 1-channel matrix, we can freely use writeMatrix as well as writeChannels
            if (filtered != binary) {
                writer.newFixedMap(TiffIFD.newTiledIFD(TagCompression.DEFLATE, filtered)
                                .putDescription("Filtered image after removing objects < %d pixels"
                                        .formatted(minArea)))
                        .writeMatrix(filtered);
            }
            writer.newFixedMap(TiffIFD.newTiledIFD(TagCompression.DEFLATE, labels)
                            .putDescription("Filtered image after removing objects < %d pixels"
                                    .formatted(minArea)))
                    .writeMatrix(labels);
        }
        System.out.println("Done");
    }
}
