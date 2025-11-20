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

package net.algart.matrices.tiff.demo.io;

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.codecs.TiffCodec;
import net.algart.matrices.tiff.tiles.TiffReadMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class TiffCustomCodecDemo {
    public static final int MY_GZIP_COMPRESSION_CODE = 40157;

    private static final System.Logger LOG = System.getLogger(TiffCustomCodecDemo.class.getName());
    private static final System.Logger.Level LOG_LEVEl = System.Logger.Level.INFO;

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.printf("    %s source.jpg/png/bmp target.tiff test.jpg/png/bmp%n",
                    TiffCustomCodecDemo.class.getName());
            return;
        }
        testMyCodec();

        final Path sourceFile = Paths.get(args[0]);
        final Path tiffFile = Paths.get(args[1]);
        final Path testFile = Paths.get(args[2]);

        System.out.printf("%nReading %s...%n", sourceFile);
        List<Matrix<UpdatablePArray>> image = MatrixIO.readImage(sourceFile);
        System.out.printf("Writing TIFF %s...%n%n", tiffFile);
        try (TiffWriter writer = new TiffWriter(tiffFile, TiffCreateMode.CREATE) {
            @Override
            protected Optional<byte[]> encodeByExternalCodec(
                    TiffTile tile, byte[] decodedData, TiffCodec.Options options) throws TiffException {
                return tile.compressionCode() == MY_GZIP_COMPRESSION_CODE ?
                        Optional.of(myEncode(tile.getDecodedData())) :
                        Optional.empty();
            }
        }) {
            final TiffIFD ifd = writer.newIFD();
            ifd.putChannelsInformation(image);
            ifd.putCompressionCode(MY_GZIP_COMPRESSION_CODE);
            // ifd.putCompression(TagCompression.DEFLATE); // - uncomment to compare sizes
            final var map = writer.newFixedMap(ifd);
            map.writeChannels(image);
        }
        System.out.printf("%nReading TIFF %s...%n%n", tiffFile);
        try (TiffReader reader = new TiffReader(tiffFile) {
            @Override
            protected Optional<byte[]> decodeByExternalCodec(
                    TiffTile tile, byte[] encodedData, TiffCodec.Options options) throws TiffException {
                return tile.compressionCode() == MY_GZIP_COMPRESSION_CODE ?
                        Optional.of(myDecode(tile.getEncodedData())) :
                        Optional.empty();
            }
        }) {
            // reader.setContext(TiffTools.newSCIFIOContext()); // - throws exception without dependence on SCIFIO
            // reader.setInterleaveResults(true); // - slows down reading (unnecessary interleaving+separating)
            final TiffReadMap map = reader.newMap(0);
            image = reader.readChannels(map);
        }
        System.out.printf("%nWriting %s for comparison with original file...%n", testFile);
        MatrixIO.writeImage(testFile, image);

        System.out.println("Done");
    }

    private static byte[] myEncode(byte[] data) throws TiffException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(data);
            }
        } catch (IOException e) {
            throw new TiffException(e);
        }
        byte[] result = output.toByteArray();
        LOG.log(LOG_LEVEl, data.length + " bytes compressed to " + result.length);
        return result;
    }

    private static byte[] myDecode(byte[] data) throws TiffException {
        byte[] result;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            result = gzip.readAllBytes();
        } catch (IOException e) {
            throw new TiffException(e);
        }
        LOG.log(LOG_LEVEl, data.length + " bytes decompressed to " + result.length);
        return result;
    }

    private static void testMyCodec() throws TiffException {
        byte[] original = "Hello World!".repeat(1000).getBytes();
        byte[] encoded = myEncode(original);
        byte[] decoded = myDecode(encoded);
        if (!Arrays.equals(decoded, original)) {
            throw new AssertionError();
        }
    }
}
