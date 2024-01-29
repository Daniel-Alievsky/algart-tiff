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

package net.algart.matrices.tiff.tests;

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class TiffWriterTest {
    private final static int IMAGE_WIDTH = 1011;
    private final static int IMAGE_HEIGHT = 1051;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
        boolean resizable = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-resizable")) {
            resizable = true;
            startArgIndex++;
        }
        boolean interleaveOutside = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-interleaveOutside")) {
            interleaveOutside = true;
            startArgIndex++;
        }
        boolean append = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-append")) {
            append = true;
            startArgIndex++;
        }
        boolean randomAccess = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-randomAccess")) {
            randomAccess = true;
            startArgIndex++;
        }
        boolean preserveOld = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-preserveOld")) {
            preserveOld = true;
            startArgIndex++;
        }
        boolean preserveOldAccurately = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-preserveOldAccurately")) {
            preserveOldAccurately = preserveOld = true;
            startArgIndex++;
        }
        boolean littleEndian = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-littleEndian")) {
            littleEndian = true;
            startArgIndex++;
        }
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        boolean longTags = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-longTags")) {
            longTags = true;
            startArgIndex++;
        }
        boolean allowMissing = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-allowMissing")) {
            allowMissing = true;
            startArgIndex++;
        }
        boolean color = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-color")) {
            color = true;
            startArgIndex++;
        }
        Double quality = null;
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-quality=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-quality=".length());
            if (!s.equals("null")) {
                quality = Double.parseDouble(s);
            }
            startArgIndex++;
        }
        boolean jpegRGB = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-jpegRGB")) {
            jpegRGB = true;
            startArgIndex++;
        }
        boolean singleStrip = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-singleStrip")) {
            singleStrip = true;
            startArgIndex++;
        }
        boolean tiled = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-tiled")) {
            tiled = true;
            startArgIndex++;
        }
        boolean planarSeparated = false;
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().matches("-(planarseparated|ps)")) {
            planarSeparated = true;
            startArgIndex++;
        }
        boolean predict = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-predict")) {
            predict = true;
            startArgIndex++;
        }
        boolean reverseBits = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-reverseBits")) {
            reverseBits = true;
            startArgIndex++;
        }
        boolean thoroughTesting = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-thoroughTesting")) {
            thoroughTesting = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriterTest.class.getName() +
                    " [-resizable] [-append] [-bigTiff] [-color] [-jpegRGB] [-quality=N] [-singleStrip] " +
                    "[-tiled] [-planarSeparated] " +
                    "target.tiff unit8|int8|uint16|int16|uint32|int32|float|double [number_of_images [compression]]" +
                    "[x y width height [number_of_tests [1st_IFD_index_to_overwrite]]");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final TiffSampleType sampleType = TiffSampleType.valueOf(args[startArgIndex].toUpperCase());

        final int numberOfImages = ++startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 1;
        final String compression = ++startArgIndex < args.length ? args[startArgIndex] : null;
        final int x = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        final int y = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        int w = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        int h = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        if (w < 0) {
            w = IMAGE_WIDTH - x;
        }
        if (h < 0) {
            h = IMAGE_HEIGHT - y;
        }
        final int numberOfTests = ++startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 1;
        final int firstIfdIndex = ++startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 0;
        final int numberOfChannels = color ? 3 : 1;
        if (planarSeparated) {
            // - we must not interleave data at all
            interleaveOutside = false;
        }
        final boolean existingFile = append || randomAccess;

        System.out.printf("%d images %s, %d total test%n",
                numberOfImages,
                randomAccess ? "from " + firstIfdIndex : "sequentially",
                numberOfTests);

        for (int test = 1; test <= numberOfTests; test++) {
            try (final Context context = noContext ? null : TiffTools.newSCIFIOContext();
                 final TiffWriter writer = new TiffWriter(targetFile, !existingFile)) {
                writer.setContext(context);
//                 TiffWriter writer = new TiffSaver(context, targetFile.toString())) {
//                writer.setEnforceUseExternalCodec(true);
                if (interleaveOutside && sampleType.bytesPerSample() == 1) {
                    writer.setAutoInterleaveSource(false);
                }
//                writer.setWritingForwardAllowed(false);
                writer.setBigTiff(bigTiff);
                if (littleEndian) {
                    writer.setLittleEndian(true);
                }
                if (quality != null) {
                    writer.setQuality(quality);
                }
                writer.setJpegInPhotometricRGB(jpegRGB);
//                writer.setPredefinedPhotoInterpretation(PhotoInterp.Y_CB_CR);
//                writer.setByteFiller((byte) 0xE0);
                writer.setTileInitializer(TiffWriterTest::customFillEmptyTile);
                writer.setMissingTilesAllowed(allowMissing);
                System.out.printf("%nTest #%d/%d: %s %s...%n",
                        test, numberOfTests,
                        existingFile ? "writing to" : "creating", targetFile);
                for (int k = 0; k < numberOfImages; k++) {
                    final int ifdIndex = firstIfdIndex + k;
                    TiffIFD ifd = new TiffIFD();
                    if (!resizable) {
                        ifd.putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
                    }
                    if (longTags) {
                        ifd.put(Tags.IMAGE_WIDTH, (long) IMAGE_WIDTH);
                        ifd.put(Tags.IMAGE_LENGTH, (long) IMAGE_HEIGHT);
                    }
                    // ifd.put(TiffIFD.JPEG_TABLES, new byte[]{1, 2, 3, 4, 5});
                    // - some invalid field: must not affect non-JPEG formats
                    if (tiled) {
                        ifd.putTileSizes(112, 64);
                        if (longTags) {
                            ifd.put(Tags.TILE_WIDTH, (long) ifd.getTileSizeX());
                            ifd.put(Tags.TILE_LENGTH, (long) ifd.getTileSizeY());
                        }
                    } else if (!singleStrip) {
                        ifd.putStripSize(100);
                        if (longTags) {
                            ifd.put(Tags.ROWS_PER_STRIP, 100L);
                        }
                    }
                    try {
                        ifd.putCompression(compression == null ? null : TagCompression.valueOf(compression));
                    } catch (IllegalArgumentException e) {
                        ifd.putCompression(Integer.parseInt(compression));
                    }
//                    if (jpegRGB) {
                        // - alternative for setJpegInPhotometricRGB
//                        ifd.putPhotometricInterpretation(PhotoInterp.RGB);
//                    }
                    ifd.putPlanarSeparated(planarSeparated);
                    if (predict) {
                        ifd.put(Tags.PREDICTOR, TiffIFD.PREDICTOR_HORIZONTAL);
                        // - unusual mode: no special putXxx method;
                        // should not be used for compressions besides LZW/DEFLATE
                    }
                    if (reverseBits) {
                        ifd.put(Tags.FILL_ORDER, TiffIFD.FILL_ORDER_REVERSED);
                        // - unusual mode: no special putXxx method
                    }
                    ifd.putPixelInformation(numberOfChannels, sampleType);
                    final boolean overwriteExisting = randomAccess && k == 0;
                    if (k == 0) {
                        writer.open(!randomAccess);
                    }
                    final TiffMap map;
                    if (overwriteExisting) {
                        // - Ignoring previous IFD. It has no sense for k > 0:
                        // after writing first IFD (at firstIfdIndex), new number of IFD
                        // will become firstIfdIndex+1, i.e. there is no more IFDs.
                        // Note: you CANNOT change properties (like color or grayscale) of image #firstIfdIndex,
                        // but following images will be written with new properties.
                        // Note: it seems that we need to "flush" current writer.getStream(),
                        // but DataHandle has not any analogs of flush() method.
                        try (TiffReader reader = new TiffReader(targetFile, false)) {
                            ifd = reader.readSingleIFD(ifdIndex);
                            ifd.setFileOffsetForWriting(ifd.getFileOffsetForReading());
                        }
                    }
                    if (overwriteExisting && preserveOld) {
                        boolean breakOldChain = numberOfImages > 1;
                        // - if we add more than 1 image, they break the existing chain
                        // (not necessary, it is just a choice for this demo)
                        if (breakOldChain) {
                            // - note: in this case the space in the previous file will be lost!
                            ifd.setLastIFD();
                        }
                        map = writer.existingMap(ifd);
                        if (preserveOldAccurately) {
                            preloadPartiallyOverwrittenTiles(writer, map, x, y, w, h);
                        }
                    } else {
                        map = writer.newMap(ifd, resizable);
                    }

                    Object samplesArray = makeSamples(ifdIndex, map.numberOfChannels(), map.sampleType(), w, h);
                    final boolean interleaved = interleaveOutside && map.bytesPerSample() == 1;
                    if (interleaved) {
                        samplesArray = TiffTools.toInterleavedSamples(
                                (byte[]) samplesArray, map.numberOfChannels(), 1, w * h);
                    }
                    Matrix<UpdatablePArray> matrix = TiffTools.asMatrix(
                            samplesArray, w, h, map.numberOfChannels(), interleaved);
                    writer.writeMatrix(map, matrix);
//                    writer.writeJavaArray(map, samplesArray, x, y, w, h); // - alternate way to write this matrix
                    if (thoroughTesting) {
                        long length = writer.getStream().length();
                        // writer.writeJavaArray(map, samplesArray, x, y, w, h);
                        // - usually not a problem to call twice, but file space will be used twice;
                        // if we have partially filled tiles on existing map, then preserveOldAccurately mode
                        // will not work properly (without 2nd preloadPartiallyOverwrittenTiles)
                        writer.complete(map); // - called inside write, but not a problem to call twice
                        writer.complete(map); // - called inside write, but not a problem to call twice
                        if (writer.getStream().length() != length) {
                            throw new AssertionError("File increased!");
                        }
                    }
                    if (test == 1) {
                        if (map.hasUnset()) {
                            List<TiffTile> unset = map.tiles().stream().filter(TiffTile::hasUnset).toList();
                            List<TiffTile> partial = unset.stream().filter(TiffTile::hasStoredDataFileOffset).toList();
                            System.out.printf(
                                    "  Image #%d: %d tiles, %d are not completely filled, %d are partially filled%n",
                                    k, map.size(), unset.size(), partial.size());
                            for (TiffTile tile : partial) {
                                Collection<IRectangularArea> unsetArea = tile.getUnsetArea();
                                System.out.printf("      %s (%d area%s)%n", tile, unsetArea.size(),
                                        unsetArea.size() == 1 ? ": " + unsetArea.iterator().next() : "s");
                            }
                        } else {
                            System.out.printf("All %d tiles are completely filled%n", map.size());
                        }
                    }
                    System.out.printf("%d ready IFDs%n", writer.numberOfIFDs());
                }
            }
        }
        System.out.println("Done");
    }

    private static void preloadPartiallyOverwrittenTiles(
            TiffWriter writer,
            TiffMap map,
            int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        final TiffReader reader = new TiffReader(writer.getStream());
        final IRectangularArea areaToWrite = IRectangularArea.valueOf(
                fromX, fromY, fromX + sizeX - 1, fromY + sizeY - 1);
        for (TiffTile tile : map.tiles()) {
            if (tile.rectangle().intersects(areaToWrite) && !areaToWrite.contains(tile.rectangle())) {
                final TiffTile existing = reader.readTile(tile.index());
                tile.setDecodedData(existing.getDecodedData());
            }
        }
    }

    private static void customFillEmptyTile(TiffTile tiffTile) {
        byte[] decoded = tiffTile.getDecodedData();
        Arrays.fill(decoded, tiffTile.bytesPerSample() == 1 ? (byte) 0xE0 : (byte) 0xFF);
        tiffTile.setInterleaved(true);
        final int pixel = tiffTile.bytesPerPixel();
        final int sizeX = tiffTile.getSizeX() * pixel;
        final int sizeY = tiffTile.getSizeY();
        Arrays.fill(decoded, 0, sizeX, (byte) 0);
        Arrays.fill(decoded, decoded.length - sizeX, decoded.length, (byte) 0);
        for (int k = 0; k < sizeY; k++) {
            Arrays.fill(decoded, k * sizeX, k * sizeX + pixel, (byte) 0);
            Arrays.fill(decoded, (k + 1) * sizeX - pixel, (k + 1) * sizeX, (byte) 0);
        }
        tiffTile.separateSamples();
    }

    private static Object makeSamples(int ifdIndex, int bandCount, TiffSampleType sampleType, int xSize, int ySize) {
        final int matrixSize = xSize * ySize;
        switch (sampleType) {
            case UINT8, INT8 -> {
                byte[] channels = new byte[matrixSize * bandCount];
                for (int y = 0; y < ySize; y++) {
                    int c1 = (y / 32) % (bandCount + 1) - 1;
                    int c2 = c1;
                    if (c1 == -1) {
                        c1 = 0;
                        c2 = bandCount - 1;
                    }
                    for (int c = c1; c <= c2; c++) {
                        for (int x = 0, disp = y * xSize; x < xSize; x++, disp++) {
                            channels[disp + c * matrixSize] = (byte) (50 * ifdIndex + x + y);
                        }
                    }
                }
                return channels;
            }
            case UINT16, INT16 -> {
                short[] channels = new short[matrixSize * bandCount];
                for (int y = 0; y < ySize; y++) {
                    int c1 = (y / 32) % (bandCount + 1) - 1;
                    int c2 = c1;
                    if (c1 == -1) {
                        c1 = 0;
                        c2 = bandCount - 1;
                    }
                    for (int c = c1; c <= c2; c++) {
                        for (int x = 0, disp = y * xSize; x < xSize; x++, disp++) {
                            channels[disp + c * matrixSize] = (short) (157 * (50 * ifdIndex + x + y));
                        }
                    }
                }
                return channels;
            }
            case INT32, UINT32 -> {
                int[] channels = new int[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        channels[disp + c * matrixSize] = 157 * 65536 * (50 * ifdIndex + x + y);
                    }
                }
                return channels;
            }
            case FLOAT -> {
                float[] channels = new float[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        int v = (50 * ifdIndex + x + y) & 0xFF;
                        channels[disp + c * matrixSize] = (float) (0.5 + 1.5 * (v / 256.0 - 0.5));
                    }
                }
                return channels;
            }
            case DOUBLE -> {
                double[] channels = new double[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        int v = (50 * ifdIndex + x + y) & 0xFF;
                        channels[disp + c * matrixSize] = (float) (0.5 + 1.5 * (v / 256.0 - 0.5));
                    }
                }
                return channels;
            }
        }
        throw new UnsupportedOperationException("Unsupported sampleType = " + sampleType);
    }
}
