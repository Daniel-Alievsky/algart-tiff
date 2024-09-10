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

package net.algart.matrices.tiff.tests.io;

import io.scif.FormatException;
import io.scif.codec.CodecOptions;
import io.scif.util.FormatTools;
import net.algart.arrays.Matrix;
import net.algart.arrays.PackedBitArrays;
import net.algart.arrays.UpdatablePArray;
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.compatibility.TiffParser;
import net.algart.matrices.tiff.compatibility.TiffSaver;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPredictor;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;

public class TiffWriterTest {
    private final static int IMAGE_WIDTH = 1011;
    private final static int IMAGE_HEIGHT = 1051;

    private static void printReaderInfo(TiffWriter writer) throws IOException {
        System.out.print("Checking file by the reader: ");
        try {
            final TiffReader reader = writer.newReaderOfThisFile(false);
            final int n = reader.numberOfIFDs();
            System.out.printf("%s, %s%n",
                    reader.isValid() ? "valid" : "INVALID: " + reader.openingException(),
                    n == 0 ? "no IFD" : "#0/" + n + ": " + reader.ifd(0));
        } catch (IOException e) {
            e.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean useContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-useContext")) {
            useContext = true;
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
        boolean compatibility = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-compatibility")) {
            jpegRGB = false;
            resizable = false;
            // - TiffSaver does not support these features
            compatibility = true;
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
            if (compatibility && !existingFile) {
                Files.deleteIfExists(targetFile);
            }
            try (final Context context = !useContext ? null : TiffReader.newSCIFIOContext();
                 final TiffWriter writer = compatibility ?
                         new TiffSaver(context, targetFile.toString()) :
                         new TiffWriter(targetFile)) {
                writer.setContext(context);
//                 TiffWriter writer = new TiffSaver(context, targetFile.toString())) {
//                writer.setEnforceUseExternalCodec(true);
                if (interleaveOutside && sampleType.bitsPerSample() == 8) {
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
                writer.setPreferRGB(jpegRGB);
//                writer.setSmartIFDCorrection(true);
//                writer.setByteFiller((byte) 0xE0);
                writer.setTileInitializer(TiffWriterTest::customFillEmptyTile);
                writer.setMissingTilesAllowed(allowMissing);
                if (writer instanceof TiffSaver saver) {
                    CodecOptions codecOptions = new CodecOptions();
                    codecOptions.bitsPerSample = 16;
                    saver.setCodecOptions(codecOptions);
                    // - should not have effect
                }
                System.out.printf("%nTest #%d/%d: %s %s%s...%n",
                        test, numberOfTests,
                        existingFile ? "writing to" : "creating",
                        targetFile,
                        context == null ? "" : " (SCIFIO context " + context + ")");
                for (int k = 0; k < numberOfImages; k++) {
//                    printReaderInfo(writer); // - should show invalid file
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
                        ifd.putStripSize(500);
                        if (longTags) {
                            ifd.put(Tags.ROWS_PER_STRIP, 500L);
                        }
                    }
                    try {
                        ifd.putCompression(compression == null ? null : TagCompression.valueOf(compression));
                    } catch (IllegalArgumentException e) {
                        ifd.putCompressionCode(Integer.parseInt(compression));
                    }
//                    if (jpegRGB) {
                    // - alternative for setJpegInPhotometricRGB
//                        ifd.putPhotometricInterpretation(PhotoInterp.RGB);
//                    }
                    ifd.putPlanarSeparated(planarSeparated);
                    if (predict) {
                        ifd.putPredictor(TagPredictor.HORIZONTAL);
                        // - unusual mode: no special putXxx method;
                        // should not be used for compressions besides LZW/DEFLATE
                    }
                    if (reverseBits) {
                        ifd.put(Tags.FILL_ORDER, TiffIFD.FILL_ORDER_REVERSED);
                        // - unusual mode: no special putXxx method
                    }
                    ifd.putPixelInformation(numberOfChannels, sampleType);
                    ifd.putImageDescription("TiffWriter test image");
                    final boolean overwriteExisting = randomAccess && k == 0;
                    if (k == 0) {
                        if (existingFile) {
                            writer.open(!randomAccess);
                        } else {
                            writer.create();
                        }
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
                        final TiffReader reader = writer.newReaderOfThisFile(false);
                        ifd = reader.readSingleIFD(ifdIndex);
                        ifd.setFileOffsetForWriting(ifd.getFileOffsetForReading());
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
                    final boolean interleaved = !writer.isAutoInterleaveSource() && map.alignedBitsPerSample() == 8;
                    if (interleaved) {
                        samplesArray = map.toInterleavedSamples(
                                (byte[]) samplesArray, map.numberOfChannels(), (long) w * (long) h);
                    }
                    Matrix<UpdatablePArray> matrix = TiffSampleType.asMatrix(
                            samplesArray, w, h, map.numberOfChannels(), interleaved);
                    if (writer instanceof TiffSaver saver && samplesArray instanceof byte[] bytes) {
                        saver.writeImage(bytes, TiffParser.toScifioIFD(map.ifd(), null),
                                ifdIndex, FormatTools.UINT8,
                                x, y, w, h, k == numberOfImages - 1);
                    } else {
                        writer.writeMatrix(map, matrix, x, y);
                    }
//                    writer.writeJavaArray(map, samplesArray, x, y, w, h); // - alternate way to write this matrix
                    printReaderInfo(writer);
                    if (thoroughTesting) {
                        long length = writer.stream().length();
                        // writer.writeJavaArray(map, samplesArray, x, y, w, h);
                        // - usually not a problem to call twice, but file space will be used twice;
                        // if we have partially filled tiles on existing map, then preserveOldAccurately mode
                        // will not work properly (without 2nd preloadPartiallyOverwrittenTiles)
                        writer.complete(map); // - called inside write, but not a problem to call twice
                        writer.complete(map); // - called inside write, but not a problem to call twice
                        if (writer.stream().length() != length) {
                            throw new AssertionError("File increased!");
                        }
                    }
                    if (test == 1) {
                        if (map.hasUnset()) {
                            List<TiffTile> unset = map.tiles().stream().filter(TiffTile::hasUnset).toList();
                            System.out.printf(
                                    "  Image #%d: %d tiles, %d are not completely filled%n",
                                    k, map.numberOfTiles(), unset.size());
                            for (TiffTile tile : unset) {
                                Collection<IRectangularArea> unsetArea = tile.getUnsetArea();
                                System.out.printf("      %s (%d area%s)%n", tile, unsetArea.size(),
                                        unsetArea.size() == 1 ? ": " + unsetArea.iterator().next() : "s");
                            }
                        } else {
                            System.out.printf("All %d tiles are completely filled%n", map.numberOfTiles());
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
        final TiffReader reader = new TiffReader(writer.stream(), true);
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
        Arrays.fill(decoded, tiffTile.isBinary() ? 0 : tiffTile.bitsPerSample() == 8 ? (byte) 0xE0 : (byte) 0xFF);
        OptionalInt bytesPerPixel = tiffTile.bytesPerPixel();
        if (bytesPerPixel.isEmpty()) {
            return;
        }
        tiffTile.setInterleaved(true);
        final int pixel = bytesPerPixel.getAsInt();
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

    private static Object makeSamples(int ifdIndex, int bandCount, TiffSampleType sampleType, int dimX, int dimY) {
        final int matrixSize = dimX * dimY;
        switch (sampleType) {
            case BIT -> {
                boolean[] channels = new boolean[matrixSize * bandCount];
                for (int y = 0; y < dimY; y++) {
                    int c1 = (y / 32) % (bandCount + 1) - 1;
                    int c2 = c1;
                    if (c1 == -1) {
                        c1 = 0;
                        c2 = bandCount - 1;
                    }
                    for (int c = c1; c <= c2; c++) {
                        for (int x = 0, disp = y * dimX; x < dimX; x++, disp++) {
                            channels[disp + c * matrixSize] = (byte) (50 * ifdIndex + Math.min(x, 500) + y) < 0;
                        }
                    }
                }
                return PackedBitArrays.packBits(channels, 0, channels.length);
            }
            case UINT8, INT8 -> {
                byte[] channels = new byte[matrixSize * bandCount];
                for (int y = 0; y < dimY; y++) {
                    int c1 = (y / 32) % (bandCount + 1) - 1;
                    int c2 = c1;
                    if (c1 == -1) {
                        c1 = 0;
                        c2 = bandCount - 1;
                    }
                    for (int c = c1; c <= c2; c++) {
                        for (int x = 0, disp = y * dimX; x < dimX; x++, disp++) {
                            channels[disp + c * matrixSize] = (byte) (50 * ifdIndex + Math.min(x, 500) + y);
                        }
                    }
                }
                return channels;
            }
            case UINT16, INT16 -> {
                short[] channels = new short[matrixSize * bandCount];
                for (int y = 0; y < dimY; y++) {
                    int c1 = (y / 32) % (bandCount + 1) - 1;
                    int c2 = c1;
                    if (c1 == -1) {
                        c1 = 0;
                        c2 = bandCount - 1;
                    }
                    for (int c = c1; c <= c2; c++) {
                        for (int x = 0, disp = y * dimX; x < dimX; x++, disp++) {
                            int v = 157 * (50 * ifdIndex + Math.min(x, 500) + y);
                            if (c1 != c2) {
                                v &= ~255; // - providing strong difference between little-endian and big-endian
                            }
                            channels[disp + c * matrixSize] = (short) v;
                        }
                    }
                }
                return channels;
            }
            case INT32, UINT32 -> {
                int[] channels = new int[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < dimX; x++, disp++) {
                        channels[disp + c * matrixSize] = 157 * 65536 * (50 * ifdIndex + Math.min(x, 500)  + y);
                    }
                }
                return channels;
            }
            case FLOAT -> {
                float[] channels = new float[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < dimX; x++, disp++) {
                        int v = (50 * ifdIndex + Math.min(x, 500) + y) & 0xFF;
                        channels[disp + c * matrixSize] = (float) (0.5 + 1.5 * (v / 256.0 - 0.5));
                    }
                }
                return channels;
            }
            case DOUBLE -> {
                double[] channels = new double[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < dimX; x++, disp++) {
                        int v = (50 * ifdIndex + Math.min(x, 500)  + y) & 0xFF;
                        channels[disp + c * matrixSize] = (float) (0.5 + 1.5 * (v / 256.0 - 0.5));
                    }
                }
                return channels;
            }
        }
        throw new UnsupportedOperationException("Unsupported sampleType = " + sampleType);
    }
}
