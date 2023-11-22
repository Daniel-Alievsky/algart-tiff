/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class TiffInfo {
    boolean strict = false;
    TiffIFD.StringFormat stringFormat = TiffIFD.StringFormat.NORMAL;

    public static void main(String[] args) throws IOException, FormatException {
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
                    "some_tiff_file.tif [firstIFDIndex lastIFDIndex]");
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
                info.showTiffInfo(f.toPath(), firstIFDIndex, lastIFDIndex);
            }
        } else {
            info.showTiffInfo(Paths.get(fileName), firstIFDIndex, lastIFDIndex);
        }
    }

    public void showTiffInfo(Path tiffFile) throws IOException, FormatException {
        showTiffInfo(tiffFile, 0, Integer.MAX_VALUE);
    }

    public void showTiffInfo(Path tiffFile, int firstIFDIndex, int lastIFDIndex)
            throws IOException, FormatException {
        try (TiffReader reader = new TiffReader(tiffFile, false)) {
            if (!reader.isValid()) {
                System.out.printf("%nFile %s: not TIFF%n", tiffFile);
            } else {
                reader.setRequireValidTiff(strict);
                var ifdList = reader.allIFDs();
                final int ifdCount = ifdList.size();
                System.out.printf("%nFile %s: %d IFDs, %s, %s-endian%n",
                        tiffFile,
                        ifdCount,
                        reader.isBigTiff() ? "BigTIFF" : "not BigTIFF",
                        reader.getStream().isLittleEndian() ? "little" : "big");
                for (int k = 0; k < ifdCount; k++) {
                    if (k >= firstIFDIndex && k <= lastIFDIndex) {
                        final TiffIFD ifd = ifdList.get(k);
                        System.out.println(ifdInfo(ifd, k, ifdCount));
                        if (!(ifd.containsKey(IFD.STRIP_BYTE_COUNTS) || ifd.containsKey(IFD.TILE_BYTE_COUNTS))) {
                            throw new IOException("Invalid IFD: does not contain StripByteCounts/TileByteCounts tag");
                        }
                        if (!(ifd.containsKey(IFD.STRIP_OFFSETS) || ifd.containsKey(IFD.TILE_OFFSETS))) {
                            throw new IOException("Invalid IFD: does not contain StripOffsets/TileOffsets tag");
                        }
                    }
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

    public static String extension(String fileName, String defaultExtension) {
        int p = fileName.lastIndexOf('.');
        if (p == -1) {
            return defaultExtension;
        }
        return fileName.substring(p + 1);
    }

    private static boolean isPossiblyTIFF(File file) {
        if (!file.isFile()) {
            return false;
        }
        final String e = extension(file.getName().toLowerCase(), "txt");
        return !e.equals("txt");
    }

}
