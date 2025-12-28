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
import net.algart.matrices.tiff.TiffImageKind;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.pyramids.TiffPyramidMetadata;
import net.algart.matrices.tiff.tags.SvsDescription;
import net.algart.matrices.tiff.tags.TagCompression;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MakeSvs {
    Path targetFile = null;
    Path baseFile = null;
    Path labelFile = null;
    Path macroFile = null;
    ByteOrder byteOrder = null;
    Boolean bigTiff = null;
    TagCompression compression = TagCompression.JPEG;
    Double quality = null;
    int numberOfLayers = 3;
    int scaleRatio = 4;
    int maxThumbnailSize = 1024;
    double pixelSize = 0.5;

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
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-compression=")) {
            final String s = args[startArgIndex].substring("-compression=".length());
            make.compression = TagCompression.fromName(s)
                    .or(() -> TagCompression.fromCode(s))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown compression name/code: " + s));
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-quality=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-quality=".length());
            if (!s.equals("null")) {
                make.quality = Double.parseDouble(s);
            }
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-ratio=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-ratio=".length());
            make.scaleRatio = Integer.parseInt(s);
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-pixel=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-pixel=".length());
            make.pixelSize = Double.parseDouble(s);
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.printf("Usage:%n    %s [-le|-be] " +
                            "[-bigTiff] [-compression=JPEG|JPEG_RGB] [-quality=xxx] [=ratio=2|4] " +
                            "[pixel=xxx] " +
                            "target.svs number-of-layers source.jpg/png/bmp/tif [label.png macro.png] %n",
                    MakeSvs.class.getSimpleName());
            return;
        }
        make.targetFile = Paths.get(args[startArgIndex++]);
        make.numberOfLayers = Integer.parseInt(args[startArgIndex++]);
        make.baseFile = Paths.get(args[startArgIndex]);
        if (args.length > startArgIndex + 1) {
            make.labelFile = Paths.get(args[startArgIndex + 1]);
        }
        if (args.length > startArgIndex + 2) {
            make.macroFile = Paths.get(args[startArgIndex + 2]);
        }
        make.makeSvs();
    }

    public void makeSvs() throws IOException {
        System.out.printf("Reading %s...%n", baseFile);
        long t1 = System.nanoTime();
        final List<? extends Matrix<? extends PArray>> image = MatrixIO.readImage(baseFile);

        List<? extends Matrix<? extends PArray>> label = null;
        List<? extends Matrix<? extends PArray>> macro = null;
        if (labelFile != null) {
            System.out.printf("Reading %s...%n", labelFile);
            label = MatrixIO.readImage(labelFile);
        }
        if (macroFile != null) {
            System.out.printf("Reading %s...%n", macroFile);
            macro = MatrixIO.readImage(macroFile);
        }

        System.out.printf("Building %s, base compression %s (\"%s\")...%n",
                targetFile, compression, compression.prettyName());
        long t2 = System.nanoTime();
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
            if (quality != null) {
                writer.setCompressionQuality(quality);
            }
            writer.create();
            long baseImageDimX = image.getFirst().dimX();
            System.out.printf("Writing image #0 %dx%d...%n",
                    baseImageDimX, image.getFirst().dimY());
            TiffIFD firstIFD = addSvsImage(writer, image, null, 0, TiffImageKind.BASE);
            var thumbnail = scaleThumbnail(image, maxThumbnailSize);
            System.out.printf("Writing thumbnail %dx%d...%n",
                    thumbnail.getFirst().dimX(), thumbnail.getFirst().dimY());
            addSvsImage(writer, thumbnail, firstIFD, 1, TiffImageKind.THUMBNAIL);
            var layer = image;
            for (int i = 1; i < numberOfLayers; i++) {
                layer = downscale(layer, scaleRatio);
                System.out.printf("Writing image #%d %dx%d...%n",
                        i, layer.getFirst().dimX(), layer.getFirst().dimY());
                addSvsImage(writer, layer, firstIFD, i + 1, TiffImageKind.ORDINARY);
            }
            if (label != null) {
                System.out.printf("Writing label %dx%d...%n", label.getFirst().dimX(), label.getFirst().dimY());
                addSvsImage(writer, label, firstIFD, numberOfLayers + 1, TiffImageKind.LABEL);
            }
            if (macro != null) {
                System.out.printf("Writing macro %dx%d...%n", macro.getFirst().dimX(), macro.getFirst().dimY());
                addSvsImage(writer, macro, firstIFD, numberOfLayers + 2, TiffImageKind.MACRO);
            }
        }
        long t3 = System.nanoTime();
        System.out.printf(Locale.US,
                "Building SVS finished: %.3f seconds reading, %.3f seconds writing TIFF.%n",
                (t2 - t1) * 1e-9, (t3 - t2) * 1e-9);
    }

    private TiffIFD addSvsImage(
            TiffWriter writer,
            List<? extends Matrix<? extends PArray>> image,
            TiffIFD firstIFD,
            int index,
            TiffImageKind kind) throws IOException {
        final TiffIFD ifd = writer.newIFD(true)
                .putChannelsInformation(image)
                .putCompression(this.compression)
                .setGlobalIndex(index);
        TiffPyramidMetadata.correctForSpecialKinds(ifd, kind);
        SvsDescription.Builder builder = new SvsDescription.Builder();
        builder.applicationSuffix("(test)");
        // builder.autoGeneratedSummary(false);
        if (kind == TiffImageKind.BASE || kind == TiffImageKind.THUMBNAIL) {
            builder
                    .pixelSize(pixelSize)
                    .dateTime(LocalDateTime.now());
        }
        if (firstIFD != null) {
            builder.updateFrom(firstIFD);
            // - update information about the base layer
        }
        builder.updateFrom(ifd);
        if (this.quality != null) {
            builder.quality((int) Math.round(this.quality * 100));
        }
        ifd.putDescription(builder.build(kind));
        final var map = writer.newFixedMap(ifd);
        map.writeChannels(image);
        return ifd;
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
