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

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.codecs.TiffCodec;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

public class TiffCustomCodecDemo {
    private static int MY_COMPRESSION = 40157;

    private static byte[] myEncode(byte[] data) {
        return data;
    }

    private static byte[] myDecode(byte[] data) {
        return data;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffCustomCodecDemo.class.getName() +
                    " source.jpg/png/bmp target.tiff test.jpg/png/bmp");
            return;
        }
        final Path sourceFile = Paths.get(args[0]);
        final Path tiffFile = Paths.get(args[1]);
        final Path testFile = Paths.get(args[2]);

        System.out.println("Reading " + sourceFile + "...");
        List<Matrix<UpdatablePArray>> image = MatrixIO.readImage(sourceFile);
        System.out.println("Writing TIFF " + tiffFile + "...");
        try (TiffWriter writer = new TiffWriter(tiffFile, true) {
            @Override
            protected Optional<byte[]> encodeByExternalCodec(
                    TiffTile tile, byte[] decodedData, TiffCodec.Options options) throws TiffException {
                return tile.ifd().getCompressionCode() == MY_COMPRESSION ?
                        Optional.of(myEncode(tile.getDecodedData())) :
                        Optional.empty();
            }
        }) {
            // writer.setAutoInterleaveSource(false); // - leads to throwing exception
            TiffIFD ifd = writer.newIFD();
            ifd.putChannelsInformation(image);
            ifd.putCompressionCode(MY_COMPRESSION);
            TiffMap map = writer.newFixedMap(ifd);
            writer.writeChannels(map, image);
        }
        System.out.println("Reading TIFF " + tiffFile + "...");
        try (TiffReader reader = new TiffReader(tiffFile) {
            @Override
            protected Optional<byte[]> decodeByExternalCodec(
                    TiffTile tile, byte[] encodedData, TiffCodec.Options options) throws TiffException {
                return tile.ifd().getCompressionCode() == MY_COMPRESSION ?
                        Optional.of(myDecode(tile.getEncodedData())) :
                        Optional.empty();
            }
        }) {
            // reader.setContext(TiffTools.newSCIFIOContext()); // - throws exception without dependence on SCIFIO
            // reader.setInterleaveResults(true); // - slows down reading (unnecessary interleaving+separating)
            image = reader.readChannels(0);
        }
        System.out.println("Writing " + testFile + "...");
        MatrixIO.writeImage(testFile, image);

        System.out.println("Done");
    }
}
