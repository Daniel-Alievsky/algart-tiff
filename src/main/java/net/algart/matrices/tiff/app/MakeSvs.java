/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.SvsDescription;
import net.algart.matrices.tiff.tags.TagCompression;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MakeSvs {
    int pyramidScaleRatio = 4;
    int maxThumbnailSize = 1024;
    double pixelSize = 1.0;
    long t1 = System.nanoTime();
    ByteOrder byteOrder = null;
    Boolean bigTiff = null;
    Double quality = null;
    int numberOfLayers = 3;

    private long lastProgressTime = Integer.MIN_VALUE;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        final MakeSvs make = new MakeSvs();
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-le")) {
            make.byteOrder = ByteOrder.LITTLE_ENDIAN;
            startArgIndex++;
        } else if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-be")) {
            make.byteOrder = ByteOrder.BIG_ENDIAN;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            make.bigTiff = true;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-quality=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-quality=".length());
            if (!s.equals("null")) {
                make.quality = Double.parseDouble(s);
            }
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.printf("Usage:%n    %s [-le|-be] " +
                            "[-bigTiff] [-quality=xxx] " +
                            "jpg/png/bmp target.svs number-of-layers%n",
                    MakeSvs.class.getSimpleName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex++]);
        make.numberOfLayers = Integer.parseInt(args[startArgIndex]);
        make.makeSvs(sourceFile, targetFile);
    }

    public void makeSvs(Path sourceFile, Path targetFile) throws IOException {
        System.out.printf("Reading %s...%n", sourceFile);
        final List<? extends Matrix<? extends PArray>> image = MatrixIO.readImage(sourceFile);

        System.out.printf("Writing %s...%n", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            if (byteOrder != null) {
                writer.setByteOrder(byteOrder);
            }
            if (bigTiff != null) {
                writer.setBigTiff(bigTiff);
            }
            if (quality != null) {
                writer.setCompressionQuality(quality);
            }
            writer.create();
            System.out.printf("Writing image #0 %dx%d...%n",
                    image.getFirst().dimX(), image.getFirst().dimY());
            addSvsImage(writer, image, false);
            var thumbnail = scaleThumbnail(image, maxThumbnailSize);
            System.out.printf("Writing thumbnail %dx%d...%n",
                    thumbnail.getFirst().dimX(), thumbnail.getFirst().dimY());
            addSvsImage(writer, thumbnail, true);
            var layer = image;
            for (int i = 1; i < numberOfLayers; i++) {
                layer = downscale(layer, pyramidScaleRatio);
                System.out.printf("Writing image #%d %dx%d...%n",
                        i, layer.getFirst().dimX(), layer.getFirst().dimY());
                addSvsImage(writer, layer, false);
            }
        }
        System.out.println("Done");
    }

    private void addSvsImage(
            TiffWriter writer,
            List<? extends Matrix<? extends PArray>> image,
            boolean thumbnail) throws IOException {
        final TiffIFD ifd = writer.newIFD()
                .putChannelsInformation(image)
                .putCompression(TagCompression.JPEG);
        SvsDescription.Builder svsBuilder = new SvsDescription.Builder();
        if (!thumbnail) {
            ifd.defaultTileSizes();
            svsBuilder.pixelSize(pixelSize)
                    .dateTime(LocalDateTime.now());
        }
        svsBuilder.importFromIFD(ifd, true);
        ifd.putDescription(svsBuilder.build());
        final var map = writer.newFixedMap(ifd);
        map.writeChannels(image);
    }

    private static List<? extends Matrix<? extends PArray>> scaleThumbnail(
            List<? extends Matrix<? extends PArray>> channels,
            int maxThumbnailSize) {
        final long[] dimensions = channels.getFirst().dimensions();
        if (dimensions.length != 2) {
            throw new IllegalArgumentException("This method works only with 2D matrices");
        }
        long maxDimension = Math.max(dimensions[0], dimensions[1]);
        if (maxDimension <= maxThumbnailSize) {
            return channels;
        }
        return resize(channels,
                Math.round(dimensions[0] * (double) maxThumbnailSize / (double) maxDimension),
                Math.round(dimensions[1] * (double) maxThumbnailSize / (double) maxDimension));
    }

    private static List<Matrix<? extends PArray>> downscale(
            List<? extends Matrix<? extends PArray>> channels,
            int scaleRatio) {
        final long[] dimensions = channels.getFirst().dimensions();
        if (dimensions.length != 2) {
            throw new IllegalArgumentException("This method works only with 2D matrices");
        }
        dimensions[0] /= scaleRatio;
        dimensions[1] /= scaleRatio;
        return resize(channels, dimensions);
    }

    private static List<Matrix<? extends PArray>> resize(
            List<? extends Matrix<? extends PArray>> channels,
            long... dimensions) {
        final List<Matrix<? extends PArray>> result = new ArrayList<>(channels.size());
        for (Matrix<? extends PArray> m : channels) {
            result.add(Matrices.asResized(Matrices.ResizingMethod.AVERAGING, m, dimensions).clone());
        }
        return result;
    }
}
