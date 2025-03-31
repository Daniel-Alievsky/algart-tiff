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

package net.algart.matrices.tiff.executable;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tags.Tags;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class TiffInfo {
    boolean strict = false;
    TiffIFD.StringFormat stringFormat = TiffIFD.StringFormat.NORMAL;

    public static void main(String[] args) {
        TiffInfo info = new TiffInfo();
        int startArgIndex = 0;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-strict")) {
            info.strict = true;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-detailed")) {
            info.stringFormat = TiffIFD.StringFormat.DETAILED;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-json")) {
            info.stringFormat = TiffIFD.StringFormat.JSON;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffInfo.class.getName() + " [-strict] [-detailed|-json] " +
                    "some_tiff_file.tiff [firstIFDIndex lastIFDIndex]");
            return;
        }
        final String fileName = args[startArgIndex];
        final int firstIFDIndex = args.length > startArgIndex + 1 ? Integer.parseInt(args[startArgIndex + 1]) : 0;
        final int lastIFDIndex = args.length > startArgIndex + 2 ?
                Integer.parseInt(args[startArgIndex + 2]) :
                Integer.MAX_VALUE;
        if (fileName.equals(".")) {
            final File[] files = new File(".").listFiles(TiffInfo::isPossiblyTIFF);
            assert files != null;
            Arrays.sort(files);
            System.out.printf("Testing %d files%n", files.length);
            for (File f : files) {
                info.showTiffInfoAndPrintException(f.toPath(), firstIFDIndex, lastIFDIndex);
            }
        } else {
            info.showTiffInfoAndPrintException(Paths.get(fileName), firstIFDIndex, lastIFDIndex);
        }
    }

    public void showTiffInfo(Path tiffFile) throws IOException {
        showTiffInfo(tiffFile, 0, Integer.MAX_VALUE);
    }

    private void showTiffInfoAndPrintException(Path tiffFile, int firstIFDIndex, int lastIFDIndex) {
        try {
            showTiffInfo(tiffFile, firstIFDIndex, lastIFDIndex);
        } catch (IOException e) {
            System.err.printf("%nFile %s is invalid: %s%n", tiffFile, e.getMessage());
        }
    }

    private void showTiffInfo(Path tiffFile, int firstIFDIndex, int lastIFDIndex) throws IOException {
        try (TiffReader reader = new TiffReader(tiffFile, false)) {
            if (!reader.isValid()) {
                System.out.printf("%nFile %s: not TIFF%n", tiffFile);
            } else {
                reader.setRequireValidTiff(strict);
                var ifdList = reader.allIFDs();
                final int ifdCount = ifdList.size();
                firstIFDIndex = Math.max(firstIFDIndex, 0);
                lastIFDIndex = Math.min(lastIFDIndex, ifdCount - 1);
                System.out.printf("%nFile %s: %d images, %s, %s-endian%n",
                        tiffFile,
                        ifdCount,
                        reader.isBigTiff() ? "BigTIFF" : "not BigTIFF",
                        reader.isLittleEndian() ? "little" : "big");
                long size = reader.sizeOfHeader();
                for (int k = firstIFDIndex; k <= lastIFDIndex; k++) {
                    final TiffIFD ifd = ifdList.get(k);
                    System.out.print(ifdInfo(ifd, k, ifdCount));
                    final long sizeOfMetadata = ifd.sizeOfMetadata();
                    final long sizeOfData = ifd.sizeOfData();
                    if (sizeOfMetadata > 0) {
                        System.out.printf("%d bytes in file occupied (%d metadata + %d image data)%n",
                                sizeOfMetadata + sizeOfData, sizeOfMetadata, sizeOfData);
                        size += sizeOfMetadata + sizeOfData;
                    }
                    System.out.println();
                    if (!(ifd.containsKey(Tags.STRIP_BYTE_COUNTS) || ifd.containsKey(Tags.TILE_BYTE_COUNTS))) {
                        throw new TiffException("Invalid IFD: doesn't contain StripByteCounts/TileByteCounts tag");
                    }
                    if (!(ifd.containsKey(Tags.STRIP_OFFSETS) || ifd.containsKey(Tags.TILE_OFFSETS))) {
                        throw new TiffException("Invalid IFD: doesn't contain StripOffsets/TileOffsets tag");
                    }
                }
                final long fileLength = reader.stream().length();
                if (size == fileLength) {
                    System.out.printf("Total file length %d bytes is fully used%n", fileLength);
                } else if (size > fileLength) {
                    System.out.printf("%d bytes in file used, but total file length is only %d bytes: " +
                                    "probably TIFF is corrupted!%n",
                            size, fileLength);
                } else {
                    System.out.printf("%d bytes in file used, %d bytes lost/unknown, total file length %d bytes%n",
                            size, fileLength - size, fileLength);
                }
            }
            System.out.println();
        }
    }

    public String ifdInfo(TiffIFD ifd, int ifdIndex, int ifdCount) {
        return "IFD #%d/%d:%s%s%n".formatted(
                ifdIndex,
                ifdCount,
                stringFormat.isJson() ? "%n".formatted() : " ",
                ifd.toString(stringFormat));
    }

    private static boolean isPossiblyTIFF(File file) {
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return !(!name.contains(".") || name.endsWith(".txt"));
    }
}
