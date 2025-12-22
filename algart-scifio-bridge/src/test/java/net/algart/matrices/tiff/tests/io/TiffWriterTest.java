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

package net.algart.matrices.tiff.tests.io;

import io.scif.FormatException;
import io.scif.codec.CodecOptions;
import io.scif.util.FormatTools;
import net.algart.arrays.Matrix;
import net.algart.arrays.PackedBitArrays;
import net.algart.arrays.UpdatablePArray;
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.codecs.JPEG2000Codec;
import net.algart.matrices.tiff.compatibility.TiffParser;
import net.algart.matrices.tiff.compatibility.TiffSaver;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPredictor;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffWriteMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;

public class TiffWriterTest {
    private static final boolean AUTO_INTERLEAVE_SOURCE = true;
    // - must be the same as AUTO_INTERLEAVE_SOURCE internal constants in TiffWriter/TiffWriteMap
    private static final int IMAGE_WIDTH = 1011;
    private static final int IMAGE_HEIGHT = 1051;

    private static void printReaderInfo(TiffWriter writer) {
        System.out.print("Checking file by the reader: ");
        final TiffReader reader;
        try {
            reader = writer.newReader(TiffOpenMode.NO_CHECKS);
        } catch (IOException e) {
            throw new AssertionError("Impossible in NO_CHECKS mode", e);
        }
        try {
            final int n = reader.numberOfImages();
            System.out.printf("%s, %d bytes, %s%n",
                    reader.isValidTiff() ? "valid" : "INVALID: \"" + reader.openingException() + "\"",
                    reader.fileLength(),
                    n == 0 ? "no IFD" : "#0/" + n + ": " + reader.ifd(0));
        } catch (IOException e) {
            // - possible while reading IFD #0
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
        if (args.length > startArgIndex && args[startArgIndex]
                .equalsIgnoreCase("-preserveOldAccurately")) {
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
        boolean alwaysToEnd = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-alwaysToEnd")) {
            alwaysToEnd = true;
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
        Double compressionLevel = null;
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-compressionlevel=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-compressionlevel=".length());
            if (!s.equals("null")) {
                compressionLevel = Double.parseDouble(s);
            }
            startArgIndex++;
        }
        boolean jp2Metadata = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-jp2Metadata")) {
            jp2Metadata = true;
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
            resizable = false;
            // - TiffSaver does not support these features
            compatibility = true;
            useContext = true;
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
//                writer.setWritingForwardAllowed(false);
                writer.setBigTiff(bigTiff);
                if (littleEndian) {
                    writer.setLittleEndian(true);
                }
                writer.setCompressionQuality(quality);
                writer.setLosslessCompressionLevel(compressionLevel);
                if (jp2Metadata) {
                    writer.setCodecOptions(new JPEG2000Codec.JPEG2000Options().setWriteMetadata(true));
                }
//                writer.setSmartFormatCorrection(true);
//                writer.setByteFiller((byte) 0xE0);
                writer.setTileInitializer(TiffWriterTest::customFillEmptyTile);
                writer.setAlwaysWriteToFileEnd(alwaysToEnd);
                writer.setMissingTilesAllowed(allowMissing);
                if (writer instanceof TiffSaver saver) {
                    CodecOptions codecOptions = new CodecOptions();
                    codecOptions.bitsPerSample = 16;
                    saver.setCodecOptions(codecOptions);
                    // - should not have effect
                }
                System.out.printf("%nTest #%d/%d: %s %s%s by %s...%n",
                        test, numberOfTests,
                        existingFile ? "writing to" : "creating",
                        targetFile,
                        context == null ? "" : " (SCIFIO context " + context + ")",
                        writer);
                for (int k = 0; k < numberOfImages; k++) {
                    printReaderInfo(writer); // - may show an invalid file
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
                        ifd.putTileSizes(112, 80);
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
                    switch (k) {
                        case 0 -> ifd.putDescription("TiffWriter test image");
                        case 1 -> ifd.putDescription("Test image\0" +
                                "\u041F\u0440\u0438\u0432\u0435\u0442\0\0" +
                                "\u05E9\u05DC\u05D5\u05DD");
                        // - above we have non-ASCII string (Russian and Hebrew): should be written as UTF-8
                        // ifd.putDescription("Д");
                        // - testing little another writing algorithm (inside a TIFF entry)
                        case 2 -> ifd.put(Tags.IMAGE_DESCRIPTION, new String[]{
                                "String 1", "", "String 2"
                        });
                        case 3 -> ifd.put(Tags.IMAGE_DESCRIPTION,
                                "String1AsBytes\0\0String2AsBytes".getBytes(StandardCharsets.UTF_8));
                        case 4 -> {
                            byte[] bytes = ("String1AsShorts\0\u05E9\u05DC\u05D5\u05DDAsShorts")
                                    .getBytes(StandardCharsets.UTF_8);
                            short[] shorts = new short[bytes.length];
                            for (int i = 0; i < bytes.length; i++) {
                                shorts[i] = (short) (bytes[i] & 0xFF);
                            }
                            ifd.put(Tags.IMAGE_DESCRIPTION, shorts);
                        }
                    }

                    final boolean overwriteExisting = randomAccess && k == 0;
                    if (k == 0) {
                        if (existingFile) {
                            writer.open(!randomAccess);
                        } else {
                            writer.create();
                        }
                    }
                    final TiffWriteMap map;
                    if (overwriteExisting) {
                        // - Ignoring previous IFD. It has no sense for k > 0:
                        // after writing the first IFD (at firstIfdIndex), the new number of IFD
                        // will become firstIfdIndex+1, i.e., there are no more IFDs.
                        // Note: you CANNOT change properties (like color or grayscale) of image #firstIfdIndex,
                        // but the following images will be written with new properties.
                        // Note: it seems that we need to "flush" current writer.getStream(),
                        // but DataHandle has not any analogs of flush() method.
                        final TiffReader reader = writer.reader();
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
                            map.preloadAndStore(x, y, w, h, false);
                        }
                    } else {
                        map = writer.newMap(ifd, resizable);
                    }

                    Object samplesArray = makeSamples(ifdIndex, map.numberOfChannels(), map.sampleType(), w, h);
                    final boolean interleaved = !AUTO_INTERLEAVE_SOURCE
                            || (writer instanceof TiffSaver && samplesArray instanceof byte[] && !planarSeparated);
                    if (interleaved) {
                        samplesArray = map.toInterleavedSamples(
                                (byte[]) samplesArray, map.numberOfChannels(), (long) w * (long) h);
                    }
                    Matrix<UpdatablePArray> matrix = TiffSampleType.asMatrix(
                            samplesArray, w, h, map.numberOfChannels(), interleaved);
                    if (writer instanceof TiffSaver tiffSaver && samplesArray instanceof byte[] bytes) {
                        System.out.println("Writing by TiffSaver.writeImage...");
                        tiffSaver.writeImage(bytes, TiffParser.toScifioIFD(map.ifd(), null),
                                ifdIndex, FormatTools.UINT8,
                                x, y, w, h, k == numberOfImages - 1);
                    } else {
                        writer.writeMatrix(map, matrix, x, y);
                    }
//                    writer.writeJavaArray(map, samplesArray, x, y, w, h); // - alternate way to write this matrix
                    printReaderInfo(writer);
                    if (thoroughTesting) {
                        long length = writer.fileLength();
                        // writer.writeJavaArray(map, samplesArray, x, y, w, h);
                        // - usually not a problem to call twice, but file space will be used twice;
                        // if we have partially filled tiles on existing map, then preserveOldAccurately mode
                        // will not work properly (without 2nd preloadPartiallyOverwrittenTiles)
                        writer.completeWriting(map); // - called inside write, but not a problem to call twice
                        writer.completeWriting(map); // - called inside write, but not a problem to call twice
                        if (writer.fileLength() != length) {
                            throw new AssertionError("File increased!");
                        }
                    }
                    if (test == 1) {
                        if (map.hasUnset()) {
                            List<TiffTile> unset = map.findTiles(TiffTile::hasUnsetArea);
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
                    System.out.printf("%d ready IFDs%n", writer.allUsedIFDOffsets().size());
                }
            }
        }
        System.out.println("Done");
    }

    // Old-style solution: replaced with TiffWriteMap.preloadAndStore
    private static void preloadPartiallyOverwrittenTiles(
            TiffWriter writer,
            int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        final TiffReader reader = writer.newReader(TiffOpenMode.VALID_TIFF);
        final IRectangularArea areaToWrite = IRectangularArea.of(
                fromX, fromY, fromX + sizeX - 1, fromY + sizeY - 1);
        for (TiffTile tile : writer.lastMap().tiles()) {
            if (tile.actualRectangle().intersects(areaToWrite) && !areaToWrite.contains(tile.actualRectangle())) {
                final TiffTile existing = reader.readCachedTile(tile.index());
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
                    int c1 = (y / 32 + 1) % (bandCount + 1) - 1;
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
                    int c1 = (y / 32 + 1) % (bandCount + 1) - 1;
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
                    int c1 = (y / 32 + 1) % (bandCount + 1) - 1;
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
                    final int c = (y / 32 + 1) % bandCount;
                    for (int x = 0; x < dimX; x++, disp++) {
                        channels[disp + c * matrixSize] = 157 * 65536 * (50 * ifdIndex + Math.min(x, 500) + y);
                    }
                }
                return channels;
            }
            case FLOAT -> {
                float[] channels = new float[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < dimY; y++) {
                    final int c = (y / 32 + 1) % bandCount;
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
                    final int c = (y / 32 + 1) % bandCount;
                    for (int x = 0; x < dimX; x++, disp++) {
                        int v = (50 * ifdIndex + Math.min(x, 500) + y) & 0xFF;
                        channels[disp + c * matrixSize] = (float) (0.5 + 1.5 * (v / 256.0 - 0.5));
                    }
                }
                return channels;
            }
        }
        throw new UnsupportedOperationException("Unsupported sampleType = " + sampleType);
    }
}
