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

package net.algart.matrices.tiff.executable;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.pyramids.SVSDescription;
import net.algart.matrices.tiff.pyramids.SVSMetadata;
import net.algart.matrices.tiff.tags.Tags;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TiffInfo {
    private TiffIFD.StringFormat stringFormat = TiffIFD.StringFormat.NORMAL;
    private int firstIFDIndex = 0;
    private int lastIFDIndex = Integer.MAX_VALUE;
    private boolean disableAppendingForStrictFormats = false;

    private final List<String> ifdInfo = new ArrayList<>();
    private boolean tiff;
    private boolean svs;
    private String prefixInfo;
    private String summaryInfo;
    private String svsInfo;

    public static void main(String[] args) {
        final TiffInfo info = new TiffInfo();
        int startArgIndex = 0;
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
        if (args.length > startArgIndex + 1) {
            info.firstIFDIndex = Integer.parseInt(args[startArgIndex + 1]);
        }
        if (args.length > startArgIndex + 2) {
            info.lastIFDIndex = Integer.parseInt(args[startArgIndex + 2]);
        }
        if (fileName.equals(".")) {
            final File[] files = new File(".").listFiles(TiffInfo::isPossiblyTIFF);
            assert files != null;
            Arrays.sort(files);
            System.out.printf("Testing %d files%n", files.length);
            for (File f : files) {
                info.showTiffInfoAndPrintException(f.toPath());
            }
        } else {
            info.showTiffInfoAndPrintException(Paths.get(fileName));
        }
    }

    public TiffIFD.StringFormat getStringFormat() {
        return stringFormat;
    }

    public TiffInfo setStringFormat(TiffIFD.StringFormat stringFormat) {
        this.stringFormat = stringFormat;
        return this;
    }

    public int getFirstIFDIndex() {
        return firstIFDIndex;
    }

    public TiffInfo setFirstIFDIndex(int firstIFDIndex) {
        this.firstIFDIndex = firstIFDIndex;
        return this;
    }

    public int getLastIFDIndex() {
        return lastIFDIndex;
    }

    public TiffInfo setLastIFDIndex(int lastIFDIndex) {
        this.lastIFDIndex = lastIFDIndex;
        return this;
    }

    public boolean isDisableAppendingForStrictFormats() {
        return disableAppendingForStrictFormats;
    }

    public TiffInfo setDisableAppendingForStrictFormats(boolean disableAppendingForStrictFormats) {
        this.disableAppendingForStrictFormats = disableAppendingForStrictFormats;
        return this;
    }

    public boolean isTiff() {
        return tiff;
    }

    public boolean isSvs() {
        return svs;
    }

    public int numberOfImages() {
        return ifdInfo.size();
    }

    public String ifdInformation(int ifdIndex) {
        return ifdInfo.get(ifdIndex);
    }

    public String prefixInfo() {
        return prefixInfo;
    }

    public String summaryInfo() {
        return summaryInfo;
    }

    public String svsInfo() {
        return svsInfo;
    }

    public void collectTiffInfo(Path tiffFile) throws IOException {
        ifdInfo.clear();
        prefixInfo = "";
        summaryInfo = "";
        svsInfo = "";
        try (TiffReader reader = new TiffReader(tiffFile, TiffOpenMode.ALLOW_NON_TIFF)) {
            if (reader.isTiff() != reader.isValidTiff()) {
                // - impossible with this form of the constructor
                throw new AssertionError();
            }
            this.tiff = reader.isTiff();
            this.svs = false;
            if (!this.tiff) {
                final Exception e = reader.openingException();
                prefixInfo = "%nFile %s: not TIFF%s".formatted(tiffFile,
                        e instanceof TiffException ? "" : "%n  (%s)".formatted(e == null ? "??" : e.getMessage()));
            } else {
                final List<TiffIFD> allIFDs;
                try {
                    allIFDs = reader.allIFDs();
                } catch (IOException e) {
                    summaryInfo = "File %s (%s, %s-endian) cannot be loaded correctly".formatted(
                            tiffFile,
                            reader.isBigTiff() ? "BigTIFF" : "not BigTIFF",
                            reader.isLittleEndian() ? "little" : "big");
                    throw e;
                }
                SVSMetadata svsMetadata = SVSMetadata.of(allIFDs);
                this.svs = svsMetadata.isSVS();
                final int ifdCount = allIFDs.size();
                final int firstIndex = Math.max(this.firstIFDIndex, 0);
                final int lastIndex = Math.min(this.lastIFDIndex, ifdCount - 1);
                prefixInfo = "File %s: %d images, %s, %s-endian, %s".formatted(
                        tiffFile,
                        ifdCount,
                        reader.isBigTiff() ? "BigTIFF" : "not BigTIFF",
                        reader.isLittleEndian() ? "little" : "big",
                        svsMetadata.isSVS() ? "SVS" : "not SVS");
                AtomicLong totalSize = new AtomicLong(reader.sizeOfHeader());
                final long tiffFileLength = reader.stream().length();
                for (int k = firstIndex; k <= lastIndex; k++) {
                    final TiffIFD ifd = allIFDs.get(k);
                    final SVSDescription svsDescription = svsMetadata.description(k);
                    ifdInfo.add(ifdInformation(reader, ifd, svsDescription, k, totalSize));
                    if (!(ifd.containsKey(Tags.STRIP_BYTE_COUNTS) || ifd.containsKey(Tags.TILE_BYTE_COUNTS))) {
                        System.err.printf("WARNING! Invalid IFD #%d in %s: no StripByteCounts/TileByteCounts tag%n",
                                k, tiffFile);
                    }
                    if (!(ifd.containsKey(Tags.STRIP_OFFSETS) || ifd.containsKey(Tags.TILE_OFFSETS))) {
                        System.err.printf("WARNING! Invalid IFD #%d in %s: no StripOffsets/TileOffsets tag%n",
                                k, tiffFile);
                    }
                }
                if (totalSize.get() == tiffFileLength) {
                    summaryInfo = "Total file length %d bytes, it is fully used".formatted(tiffFileLength);
                } else if (totalSize.get() > tiffFileLength) {
                    summaryInfo = ("%d bytes in file used, but the file length is only %d bytes, " +
                            "%d \"extra\" bytes: probably TIFF is not valid? (%s)").formatted(
                            totalSize.get(), tiffFileLength, totalSize.get() - tiffFileLength, tiffFile);
                    // - however, this is possible in some files: for example, in
                    // "signed-integral-8bit.tif" from the TwelveMonkey demo image set,
                    // we have ASCII IFD entry at the end of the file, and the image before it has odd length;
                    // this ASCII offset is not aligned, so we consider that here is 1 extra byte
                } else {
                    summaryInfo = ("%d bytes in file used, %d bytes lost/unknown, the file length %d bytes (%s)")
                            .formatted(totalSize.get(),
                                    tiffFileLength - totalSize.get(), tiffFileLength, tiffFile);
                }
                if (this.svs) {
                    svsInfo = "%s%nSpecial SVS images:%s".formatted(
                            svsMetadata.mainDescription(), svsMetadata.imageSet());
                }
            }
        }
    }

    public String ifdInformation(TiffReader reader, TiffIFD ifd, int ifdIndex) throws IOException {
        return ifdInformation(reader, ifd, null, ifdIndex, null);
    }

    private String ifdInformation(
            TiffReader reader,
            TiffIFD ifd,
            SVSDescription svsDescription,
            int ifdIndex,
            AtomicLong totalSize) throws IOException {
        final Map<String, String> additionalInformation = new LinkedHashMap<>();
        if (svsDescription != null && svsDescription.isSVS()) {
            additionalInformation.put("SVS", svsDescription.toString(stringFormat));
        }
        if (disableAppendingForStrictFormats && stringFormat.isStrict()) {
            return ifd.toString(stringFormat, additionalInformation);
        }
        int ifdCount = reader.numberOfImages();
        StringBuilder sb = new StringBuilder();
        sb.append("IFD #%d/%d:%s%s%n".formatted(
                ifdIndex,
                ifdCount,
                stringFormat.isJson() ? "%n".formatted() : " ",
                ifd.toString(stringFormat, additionalInformation)));
        final long tiffFileLength = reader.stream().length();
        final OptionalLong sizeOfIFDOptional = ifd.sizeOfIFD(tiffFileLength);
        AtomicBoolean imageDataAligned = new AtomicBoolean(false);
        if (sizeOfIFDOptional.isPresent()) {
            final long sizeOfIFD = sizeOfIFDOptional.getAsLong();
            if (totalSize != null) {
                totalSize.addAndGet(sizeOfIFD);
            }
            long sizeOfData = -1;
            try {
                sizeOfData = ifd.sizeOfImageData(tiffFileLength, imageDataAligned);
                final long sizeOfIFDTable = ifd.sizeOfIFDTable();
                if (ifd.isMainIFD() && sizeOfIFDTable != (ifd.isBigTiff() ?
                        16L + 20L * ifd.numberOfEntries() :
                        6L + 12L * ifd.numberOfEntries())) {
                    throw new AssertionError("Invalid sizeOfIFDTable");
                }
                sb.append("%d bytes in the file occupied: %d metadata (%d table + %d external) + %d image data%s"
                        .formatted(
                                sizeOfIFD + sizeOfData,
                                sizeOfIFD, sizeOfIFDTable, sizeOfIFD - sizeOfIFDTable,
                                sizeOfData,
                                imageDataAligned.get() ? " (" + (sizeOfData - 1) + " unaligned)" : ""));
                if (totalSize != null) {
                    totalSize.addAndGet(sizeOfData);
                }
            } catch (TiffException e) {
                sb.append("%d bytes in the file occupied by metadata, ".formatted(sizeOfIFD))
                        .append("but cannot detect the size occupied by image data: ").append(e.getMessage());
            }
        }
        return sb.toString();
    }

    private void showTiffInfoAndPrintException(Path tiffFile) {
        try {
            showTiffInfo(tiffFile);
        } catch (IOException e) {
            System.err.printf("%nFile %s is invalid:%n  %s%n", tiffFile, e.getMessage());
        }
    }

    private void showTiffInfo(Path tiffFile) throws IOException {
        collectTiffInfo(tiffFile);
        System.out.println(prefixInfo);
        for (String ifdInfoLine : ifdInfo) {
            System.out.println(ifdInfoLine);
        }
        System.out.println(summaryInfo);
        if (isSvs()) {
            System.out.println(svsInfo);
        }
        System.out.println();
    }

    private static boolean isPossiblyTIFF(File file) {
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return !(!name.contains(".") || name.endsWith(".txt"));
    }
}
