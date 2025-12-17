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

import net.algart.matrices.tiff.TiffCopier;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class TiffCopy {
    boolean repack = false;
    boolean smart = false;
    ByteOrder byteOrder = null;
    Boolean bigTiff = null;
    Double quality = null;

    private long lastProgressTime = Integer.MIN_VALUE;

    public static void main(String[] args) throws IOException {
        TiffCopy copy = new TiffCopy();
        int startArgIndex = 0;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-repack")) {
            copy.repack = true;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-smart")) {
            copy.smart = true;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-le")) {
            copy.byteOrder = ByteOrder.LITTLE_ENDIAN;
            startArgIndex++;
        } else if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-be")) {
            copy.byteOrder = ByteOrder.BIG_ENDIAN;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            copy.bigTiff = true;
            startArgIndex++;
        } else if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noBigTiff")) {
            copy.bigTiff = false;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-quality")) {
            startArgIndex++;
            final String s = args[startArgIndex].toLowerCase();
            if (!s.equals("null")) {
                copy.quality = Double.parseDouble(s);
            }
            startArgIndex++;
        }

        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("   [-repack] [-smart] [-le|-be] [-bigTiff|-noBigTIFF] [-quality xxx] " +
                            "%s source.tiff target.tiff%n",
                    TiffCopy.class.getName());
            System.out.println("""
                    The source TIFF file is completely parsed, and its content is copied to the target file\
                    in the optimal way: IFD table first, then image data.
                    -repack option forces decompression and compression of TIFF image data even in the case
                    when direct tile-per-tile (or strip-per-strip) copying is possible.
                    -le and -be options allows specifying the target file byte order; by default,\
                    the source byte order is preserved.
                    -smart option allows copying some file formats that are not supported for writing\
                    (like 16-bit float values);
                    they will be repacked into the "closest" supported format.
                    """);
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex]);
        copy.copy(sourceFile, targetFile);
    }

    public void copy(Path sourceFile, Path targetFile) throws IOException {
        final TiffCopier copier = new TiffCopier();
        copier.setDirectCopy(!repack);
        copier.setProgressUpdater(this::updateProgress);

        System.out.printf("Copying %s to %s%s%s%s%s...%n",
                sourceFile,
                targetFile,
                repack ? " with recompression" : "",
                quality == null ? "" : " (quality " + quality + ")",
                byteOrder == null ? "" : byteOrder == ByteOrder.LITTLE_ENDIAN ? ", little-endian" : ", big-endian",
                smart ? ", smart mode" : "");
        final long t1 = System.nanoTime();
        try (TiffReader reader = new TiffReader(sourceFile);
             TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setFormatLike(reader);
            if (byteOrder != null) {
                writer.setByteOrder(byteOrder);
            }
            if (bigTiff != null) {
                writer.setBigTiff(bigTiff);
            }
            writer.setSmartFormatCorrection(smart);
            if (quality != null) {
                writer.setCompressionQuality(quality);
            }
            writer.create();
            copier.copyAllImages(writer, reader);
        }
        final long t2 = System.nanoTime();
        System.out.printf(Locale.US, "Copying finished in %.3f seconds%n",  (t2 - t1) * 1e-9);
    }

    private void updateProgress(TiffCopier.ProgressInformation p) {
        long t = System.currentTimeMillis();
        if (t - lastProgressTime > 200 || p.isLastTileCopied()) {
            System.out.printf("\rImage %d/%d, tile %d/%d (%s)%s",
                    p.imageIndex() + 1, p.imageCount(),
                    p.tileIndex() + 1, p.tileCount(),
                    p.copier().actuallyDirectCopy() ? "direct" : "repacking",
                    p.isLastTileCopied() ? "" : "...");
            if (p.isLastTileCopied()) {
                System.out.println();
                // - so, we guarantee that the line cannot become shorter without println()
            }
            lastProgressTime = t;
        }
    }
}
