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

package net.algart.matrices.tiff.data;

import net.algart.arrays.JArrays;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPhotometricInterpretation;
import net.algart.matrices.tiff.tags.TagRational;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

public class TiffUnpacking {
    private static final boolean OPTIMIZE_SEPARATING_WHOLE_BYTES = true;
    // - should be true for good performance; false value can help while debugging
    private static final boolean THOROUGHLY_TEST_Y_CB_CR_LOOP = false;

    private TiffUnpacking() {
    }

    public static boolean separateUnpackedSamples(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile);
        final int decodedDataLength = tile.getDecodedDataLength();
        final TiffIFD ifd = tile.ifd();

        final AtomicBoolean simpleNonJpegFormat = new AtomicBoolean();
        if (!isSimpleRearrangingBytesEnough(ifd, simpleNonJpegFormat)) {
            return false;
        }
        if (!OPTIMIZE_SEPARATING_WHOLE_BYTES && tile.isInterleaved()) {
            // - if a tile is not interleaved, we MUST use this method:
            // separateBitsAndInvertValues does not "understand" this situation
            return false;
        }

        // We have an equal number N of bits/sample for all samples,
        // N % 8 == 0, N / 8 is 1, 2, 3, 4 or 8;
        // for all other cases isSimpleRearrangingBytesEnough returns false.
        // The only unusual case here is 3 bytes/sample: 24-bit float or 24-bit integer;
        // these cases (as well as 16-bit float) will be processed later in unpackUnusualPrecisions.
        if (decodedDataLength > tile.map().tileSizeInBytes() && !simpleNonJpegFormat.get()) {
            // - Strange situation: JPEG or some extended codec has decoded too large tile.
            // But for "simple" compressions (uncompressed, Deflate) we enable this situation:
            // it helps to create special tests (for example, an image with "fake" too little dimensions).
            // (Note: for LZW compression, in the current version, this situation is impossible, because
            // LZWCodec creates the result array on the base of options.maxBytes field.)
            // In comparison, further adjustNumberOfPixels throws IllegalArgumentException
            // in the same situation, when its argument is false.
            throw new TiffException("Too large decoded TIFF data: " + decodedDataLength +
                    " bytes, its is greater than one " +
                    (tile.map().tilingMode().isTileGrid() ? "tile" : "strip") +
                    " (" + tile.map().tileSizeInBytes() + " bytes); "
                    + "probably TIFF file is corrupted or format is not properly supported");
        }
        tile.adjustNumberOfPixels(true);
        // - Note: decodedDataLength is unpredictable because it is the result of decompression by a codec;
        // in particular, for JPEG compression,
        // the last strip in non-tiled TIFF may be shorter or even larger than a full tile.
        // If cropping boundary tiles is enabled, the actual height of the last strip is reduced
        // (see readEncodedTile method), so larger data is possible (it is a minor format separately).
        // If cropping boundary tiles is disabled, larger data MAY be considered as a format error,
        // because tile sizes are the FULL sizes of tile in the grid (it is checked above independently).
        // We consider this situation as an error in the case of "complex" codecs like JPEG, JPEG-2000 etc.
        // Also note: it is better to rearrange pixels before separating (if necessary),
        // because rearranging interleaved pixels is a little more simple.
        tile.separateSamplesIfNecessary();

        // Note: other 2 functions, unpackUnusualBits and decodeYCbCr, always work with interleaved
        // data and separate data themselves to a tile with correct sizes
        return true;

    }

    // Note: this method may be tested with the images ycbcr-cat.tif and dscf0013.tif
    public static boolean separateYCbCrToRGB(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile);
        final TiffIFD ifd = tile.ifd();
        final byte[] data = tile.getDecodedData();

        if (!ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        checkInterleaved(tile);

        final TiffMap map = tile.map();
        if (map.isPlanarSeparated()) {
            // - there is no simple way to support this exotic situation: Cb/Cr values are saved in other tiles
            throw new UnsupportedTiffFormatException("TIFF YCbCr photometric interpretation is not supported " +
                    "in planar format (separated component planes: TIFF tag PlanarConfiguration=2)");
        }

        final int bits = ifd.tryEqualBitDepth().orElse(-1);
        if (bits != 8) {
            throw new UnsupportedTiffFormatException("Cannot unpack YCbCr TIFF image with " +
                    Arrays.toString(ifd.getBitsPerSample()) +
                    " bits per samples: only [8, 8, 8] variant is supported");
        }

        if (map.tileSamplesPerPixel() != 3) {
            throw new TiffException("TIFF YCbCr photometric interpretation requires 3 channels, but " +
                    "there are " + map.tileSamplesPerPixel() + " channels in SamplesPerPixel TIFF tag");
        }

        final int sizeX = tile.getSizeX();
        final int sizeY = tile.getSizeY();
        final int numberOfPixels = tile.getSizeInPixels();

        final byte[] unpacked = new byte[3 * numberOfPixels];

        // unpack pixels
        // set up YCbCr-specific values
        double lumaRed = 0.299;
        double lumaGreen = 0.587;
        double lumaBlue = 0.114;
        int[] reference = ifd.getIntArray(Tags.REFERENCE_BLACK_WHITE);
        if (reference == null) {
            reference = new int[]{0, 255, 128, 255, 128, 255};
            // - original SCIFIO code used here zero-filled array, this is incorrect
        }
        final int[] subsamplingLog = ifd.getYCbCrSubsamplingLogarithms();
        final TagRational[] coefficients = ifd.getValue(Tags.Y_CB_CR_COEFFICIENTS, TagRational[].class)
                .orElse(new TagRational[0]);
        if (coefficients.length >= 3) {
            lumaRed = coefficients[0].doubleValue();
            lumaGreen = coefficients[1].doubleValue();
            lumaBlue = coefficients[2].doubleValue();
        }
        final double crCoefficient = 2.0 - 2.0 * lumaRed;
        final double cbCoefficient = 2.0 - 2.0 * lumaBlue;
        final double lumaGreenInv = 1.0 / lumaGreen;
        final int subXLog = subsamplingLog[0];
        final int subYLog = subsamplingLog[1];
        final int subXMinus1 = (1 << subXLog) - 1;
        final int blockLog = subXLog + subYLog;
        final int block = 1 << blockLog;
        final int numberOfXBlocks = (sizeX + subXMinus1) >>> subXLog;
        for (int yIndex = 0, i = 0; yIndex < sizeY; yIndex++) {
            final int yBlockIndex = yIndex >>> subYLog;
            final int yAligned = yBlockIndex << subYLog;
            final int lineAligned = yBlockIndex * numberOfXBlocks;
            for (int xIndex = 0; xIndex < sizeX; xIndex++, i++) {
                // unpack non-YCbCr samples
                // unpack YCbCr unpacked; these need special handling, as
                // each of the RGB components depends upon two or more of the YCbCr
                // components
                final int blockIndex = i >>> blockLog; // = i / block
                final int aligned = blockIndex << blockLog;
                final int indexInBlock = i - aligned;
                assert indexInBlock < block;
                final int blockIndexTwice = 2 * blockIndex; // 1 block for Cb + 1 block for Cr
                final int blockStart = aligned + blockIndexTwice;
                final int lumaIndex = blockStart + indexInBlock;
                final int chromaIndex = blockStart + block;
                // Every block contains block Luma (Y) values, 1 Cb value and 1 Cr value, for example (2x2):
                //    YYYYbrYYYYbrYYYYbr...
                //                |"blockStart" position

                if (chromaIndex + 1 >= data.length) {
                    break;
                }

                final int yIndexInBlock = indexInBlock >>> subXLog;
                final int xIndexInBlock = indexInBlock - (yIndexInBlock << subXLog);
                final int resultYIndex = yAligned + yIndexInBlock;
                final int resultXIndex = ((blockIndex - lineAligned) << subXLog) + xIndexInBlock;
                if (THOROUGHLY_TEST_Y_CB_CR_LOOP) {
                    final int subX = 1 << subXLog;
                    final int subY = 1 << subYLog;
                    final int t = i / block;
                    final int pixel = i % block;
                    final long r = (long) subY * (t / numberOfXBlocks) + (pixel / subX);
                    final long c = (long) subX * (t % numberOfXBlocks) + (pixel % subX);
                    // - these formulas were used in the original SCIFIO code
                    assert t / numberOfXBlocks == yBlockIndex;
                    assert t % numberOfXBlocks == blockIndex - lineAligned;
                    assert c == resultXIndex;
                    assert r == resultYIndex;
                }
                if (resultXIndex >= sizeX || resultYIndex >= sizeY) {
                    // - for a case when the sizes are not aligned by subX/subY
                    continue;
                }

                final int resultIndex = resultYIndex * sizeX + resultXIndex;
                final int y = (data[lumaIndex] & 0xff) - reference[0];
                final int cb = (data[chromaIndex] & 0xff) - reference[2];
                final int cr = (data[chromaIndex + 1] & 0xff) - reference[4];

                final double red = cr * crCoefficient + y;
                final double blue = cb * cbCoefficient + y;
                final double green = (y - lumaBlue * blue - lumaRed * red) * lumaGreenInv;

                unpacked[resultIndex] = (byte) toUnsignedByte(red);
                unpacked[numberOfPixels + resultIndex] = (byte) toUnsignedByte(green);
                unpacked[2 * numberOfPixels + resultIndex] = (byte) toUnsignedByte(blue);
            }
            while ((i & subXMinus1) != 0) {
                i++;
                // - horizontal alignment
            }
        }

        tile.setDecodedData(unpacked);
        tile.setInterleaved(false);
        return true;
    }

    public static boolean unpackTiffBitsAndInvertValues(
            TiffTile tile,
            boolean scaleWhenIncreasingBitDepth,
            boolean correctInvertedBrightness)
            throws TiffException {
        Objects.requireNonNull(tile);
        tile.checkDecodedData();
        final TiffIFD ifd = tile.ifd();

        if (OPTIMIZE_SEPARATING_WHOLE_BYTES && isSimpleRearrangingBytesEnough(ifd, null)) {
            return false;
        }
        if (ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        checkInterleaved(tile);
        if (!ifd.isStandardCompression() || ifd.isJpegOrOldJpeg()) {
            throw new IllegalStateException("Corrupted IFD, probably by direct modifications (" +
                    "non-standard/JPEG compression, though it was already checked)");
            // - was checked in isSimpleRearrangingBytesEnough
        }
        final TagPhotometricInterpretation photometricInterpretation = ifd.getPhotometricInterpretation();
        if (photometricInterpretation.isIndexed() ||
                photometricInterpretation == TagPhotometricInterpretation.TRANSPARENCY_MASK) {
            scaleWhenIncreasingBitDepth = false;
        }
        final boolean invertedBrightness = photometricInterpretation.isInvertedBrightness();
        if (tile.sampleType().isFloatingPoint() && OPTIMIZE_SEPARATING_WHOLE_BYTES) {
            // - TIFF with float/double samples must not require bit unpacking or inverting brightness
            throw new TiffException("Invalid TIFF image: floating-point values, compression \"" +
                    ifd.compressionPrettyName() + "\", photometric interpretation \"" +
                    photometricInterpretation.prettyName() + "\", " +
                    Arrays.toString(ifd.getBitsPerSample()) + " bits per sample");
        }
        final boolean invertValues = correctInvertedBrightness && invertedBrightness;

        final int sizeX = tile.getSizeX();
        final int sizeY = tile.getSizeY();
        assert tile.getSizeInPixels() == sizeX * sizeY;
//        debugPrintBits(tile);
        final byte[] source = tile.getDecodedData();
        final byte[] result = new byte[tile.getSizeInBytes()];
        OptionalInt bytesPerSample = tile.bytesPerSample();
        if (tile.isWholeBytes()) {
            unpackWholeBytesAndInvertValues(
                    ifd,
                    result,
                    source,
                    sizeX,
                    sizeY,
                    tile.samplesPerPixel(),
                    bytesPerSample.orElseThrow(),
                    scaleWhenIncreasingBitDepth,
                    invertValues);
        } else {
            assert tile.bitsPerSample() == 1 : ">1 bits per sample for non-whole bytes are not supported: " + tile;
            assert tile.samplesPerPixel() == 1 : ">1 samples per pixel for non-whole bytes are not supported: " + tile;
            extractSingleBitsAndInvertValues(result, source, sizeX, sizeY, invertValues);
        }
        tile.setDecodedData(result);
        tile.setInterleaved(false);
        return true;
    }

    private static boolean isSimpleRearrangingBytesEnough(TiffIFD ifd, AtomicBoolean simpleNonJpegFormat)
            throws TiffException {
        final TagCompression compression = ifd.optCompression().orElse(null);
        final boolean advancedFormat = compression != null &&
                (!compression.isStandard() || compression.isJpegOrOldJpeg());
        if (simpleNonJpegFormat != null) {
            simpleNonJpegFormat.set(!advancedFormat);
        }
        if (advancedFormat) {
            // JPEG codec and all non-standard codecs like JPEG-2000 should perform all necessary
            // bits unpacking or color space corrections themselves
            return true;
        }
        int bits = ifd.tryEqualBitDepthAlignedByBytes().orElse(-1);
        if (bits == -1) {
            return false;
        }
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32 && bits != 64) {
            // - should not occur: the same check is performed in TiffIFD.sampleType(), called while creating TiffMap
            throw new UnsupportedTiffFormatException("Not supported TIFF format: compression \"" +
                    ifd.compressionPrettyName() + "\", " + bits + " bits per every sample");
        }
        if (ifd.getPhotometricInterpretation() == TagPhotometricInterpretation.Y_CB_CR) {
            // - convertYCbCrToRGB function performs necessary repacking itself
            return false;
        }
        return !ifd.isStandardInvertedCompression();
    }

    // Below is almost exact copy of old TiffParser.unpackBytes method:

    /*
    static void unpackBytesLegacy(
            final byte[] samples, final int startIndex,
            final byte[] bytes, final TiffIFD ifd) throws TiffException {
        final boolean planar = ifd.getPlanarConfiguration() == 2;

        final TiffCompression compression = ifd.getCompression();
        TiffPhotometricInterpretation photometricInterpretation = ifd.getPhotometricInterpretation();
        if (compression == TiffCompression.JPEG) {
            photometricInterpretation = TiffPhotometricInterpretation.RGB;
        }

        final int[] bitsPerSample = ifd.getBitsPerSample();
        int nChannels = bitsPerSample.length;

        int sampleCount = (int) (((long) 8 * bytes.length) / bitsPerSample[0]);
        //!! It is a bug! This formula is invalid in the case skipBits!=0
        if (photometricInterpretation == TiffPhotometricInterpretation.Y_CB_CR) sampleCount *= 3;
        if (planar) {
            nChannels = 1;
        } else {
            sampleCount /= nChannels;
        }

//        log.trace("unpacking " + sampleCount + " samples (startIndex=" +
//                startIndex + "; totalBits=" + (nChannels * bitsPerSample[0]) +
//                "; numBytes=" + bytes.length + ")");

        final long imageWidth = ifd.getImageDimX();
        final long imageHeight = ifd.getImageDimY();

        final int bps0 = bitsPerSample[0];
        final int numBytes = ifd.getBytesPerSample()[0];
        final int nSamples = samples.length / (nChannels * numBytes);

        final boolean noDiv8 = bps0 % 8 != 0;
        final boolean bps8 = bps0 == 8;
        final boolean bps16 = bps0 == 16;

        final boolean littleEndian = ifd.isLittleEndian();

        final io.scif.codec.BitBuffer bb = new io.scif.codec.BitBuffer(bytes);

        // Hyper optimisation that takes any 8-bit or 16-bit data, where there
        // is
        // only one channel, the source byte buffer's size is less than or equal
        // to
        // that of the destination buffer and for which no special unpacking is
        // required and performs a simple array copy. Over the course of reading
        // semi-large datasets this can save **billions** of method calls.
        // Wed Aug 5 19:04:59 BST 2009
        // Chris Allan <callan@glencoesoftware.com>
        if ((bps8 || bps16) && bytes.length <= samples.length && nChannels == 1 &&
                photometricInterpretation != TiffPhotometricInterpretation.WHITE_IS_ZERO &&
                photometricInterpretation != TiffPhotometricInterpretation.CMYK &&
                photometricInterpretation != TiffPhotometricInterpretation.Y_CB_CR) {
            System.arraycopy(bytes, 0, samples, 0, bytes.length);
            return;
        }

        long maxValue = (long) Math.pow(2, bps0) - 1;
        if (photometricInterpretation == TiffPhotometricInterpretation.CMYK) maxValue = Integer.MAX_VALUE;

        int skipBits = (int) (8 - ((imageWidth * bps0 * nChannels) % 8));
        if (skipBits == 8 || (bytes.length * 8L < bps0 * (nChannels * imageWidth +
                imageHeight))) {
            skipBits = 0;
        }

        // set up YCbCr-specific values
        float lumaRed = PhotoInterp.LUMA_RED;
        float lumaGreen = PhotoInterp.LUMA_GREEN;
        float lumaBlue = PhotoInterp.LUMA_BLUE;
        int[] reference = ifd.getIntArray(IFD.REFERENCE_BLACK_WHITE);
        if (reference == null) {
            reference = new int[]{0, 0, 0, 0, 0, 0};
        }
        final int[] subsampling = ifd.getIntArray(IFD.Y_CB_CR_SUB_SAMPLING);
        final TiffRational[] coefficients = ifd.optValue(
                TiffIFD.Y_CB_CR_COEFFICIENTS, TiffRational[].class).orElse(null);
        if (coefficients != null) {
            lumaRed = coefficients[0].floatValue();
            lumaGreen = coefficients[1].floatValue();
            lumaBlue = coefficients[2].floatValue();
        }
        final int subX = subsampling == null ? 2 : subsampling[0];
        final int subY = subsampling == null ? 2 : subsampling[1];
        final int block = subX * subY;
        final int nTiles = (int) (imageWidth / subX);

        // unpack pixels
        for (int sample = 0; sample < sampleCount; sample++) {
            final int ndx = startIndex + sample;
            if (ndx >= nSamples) break;

            for (int channel = 0; channel < nChannels; channel++) {
                final int index = numBytes * (sample * nChannels + channel);
                final int outputIndex = (channel * nSamples + ndx) * numBytes;

                // unpack non-YCbCr samples
                if (photometricInterpretation != TiffPhotometricInterpretation.Y_CB_CR) {
                    long value = 0;

                    if (noDiv8) {
                        // bits per sample is not a multiple of 8

                        if ((channel == 0 && photometricInterpretation == TiffPhotometricInterpretation.RGB_PALETTE) ||
                                (photometricInterpretation != TiffPhotometricInterpretation.CFA_ARRAY &&
                                        photometricInterpretation != TiffPhotometricInterpretation.RGB_PALETTE)) {
//                            System.out.println((count++) + "/" + nSamples * nChannels);
                            value = bb.getBits(bps0) & 0xffff;
                            if ((ndx % imageWidth) == imageWidth - 1) {
                                bb.skipBits(skipBits);
                            }
                        }
                    } else {
                        value = Bytes.toLong(bytes, index, numBytes, littleEndian);
                    }

                    if (photometricInterpretation == TiffPhotometricInterpretation.WHITE_IS_ZERO ||
                            photometricInterpretation == TiffPhotometricInterpretation.CMYK) {
                        value = maxValue - value;
                    }

                    if (outputIndex + numBytes <= samples.length) {
                        Bytes.unpack(value, samples, outputIndex, numBytes, littleEndian);
                    }
                } else {
                    // unpack YCbCr samples; these need special handling, as
                    // each of
                    // the RGB components depends upon two or more of the YCbCr
                    // components
                    if (channel == nChannels - 1) {
                        final int lumaIndex = sample + (2 * (sample / block));
                        final int chromaIndex = (sample / block) * (block + 2) + block;

                        if (chromaIndex + 1 >= bytes.length) break;

                        final int tile = ndx / block;
                        final int pixel = ndx % block;
                        final long r = (long) subY * (tile / nTiles) + (pixel / subX);
                        final long c = (long) subX * (tile % nTiles) + (pixel % subX);

                        final int idx = (int) (r * imageWidth + c);

                        if (idx < nSamples) {
                            final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                            final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                            final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                            final int red = (int) (cr * (2 - 2 * lumaRed) + y);
                            final int blue = (int) (cb * (2 - 2 * lumaBlue) + y);
                            final int green = (int) ((y - lumaBlue * blue - lumaRed * red) /
                                    lumaGreen);

                            samples[idx] = (byte) (red & 0xff);
                            samples[nSamples + idx] = (byte) (green & 0xff);
                            samples[2 * nSamples + idx] = (byte) (blue & 0xff);
                        }
                    }
                }
            }
        }
    }
    */

    private static void unpackWholeBytesAndInvertValues(
            TiffIFD ifd,
            byte[] unpacked,
            byte[] source,
            int sizeX,
            int sizeY,
            int samplesPerPixel,
            int bytesPerSample,
            boolean scaleWhenIncreasingBitDepth,
            boolean invertValues) throws TiffException {
        if (bytesPerSample > 4) {
            throw new IllegalStateException("Corrupted IFD, probably by direct modifications (" +
                    bytesPerSample + " bytes/sample in tile, though this was already checked)");
            // - was checked in isSimpleRearrangingBytesEnough
        }
        // The only non-standard bytesPerSample is 3: 17..24-bit integer (but not all bits/sample are 24);
        // we must complete such samples to 24 bits, and they will be processed later in unpackUnusualPrecisions
        final int numberOfPixels = sizeX * sizeY;
        final int[] bitsPerSample = ifd.getBitsPerSample();
        final boolean byteAligned = Arrays.stream(bitsPerSample).noneMatch(bits -> (bits & 7) != 0);
        if (byteAligned
                && !ifd.getPhotometricInterpretation().isInvertedBrightness()
                && OPTIMIZE_SEPARATING_WHOLE_BYTES) {
            throw new IllegalStateException("Corrupted IFD, probably from a parallel thread " +
                    "(BitsPerSample tag is byte-aligned and inversion is not necessary, " +
                    "though it was already checked)");
            // - was checked in isSimpleRearrangingBytesEnough; other case,
            // when we have DIFFERENT number of bytes, must be checked while creating TiffMap
        }
        if (samplesPerPixel > bitsPerSample.length)
            throw new IllegalStateException("Corrupted IFD, probably by direct modifications (" +
                    samplesPerPixel + " samples/pixel is greater than the length of BitsPerSample tag; " +
                    "it is possible only for OLD_JPEG, that was already checked)");
        // - but samplesPerPixel can be =1 for planar-separated tiles

        final ByteOrder byteOrder = ifd.getByteOrder();
        final long[] multipliers = new long[bitsPerSample.length];
        for (int k = 0; k < multipliers.length; k++) {
            multipliers[k] = ((1L << 8 * bytesPerSample) - 1) / ((1L << bitsPerSample[k]) - 1);
            // - note that 2^n-1 is divisible by 2^m-1 when m < n; actually it is less or about 256
        }
        long pos = 0;
        final long length = PackedBitArraysPer8.unpackedLength(source);
        for (int yIndex = 0, i = 0; yIndex < sizeY; yIndex++) {
            for (int xIndex = 0; xIndex < sizeX; xIndex++, i++) {
                for (int s = 0; s < samplesPerPixel; s++) {
                    final int bits = bitsPerSample[s];
                    assert bits <= 32 : "the check \"bytesPerSample > 4\" was not performed!";
                    final long maxValue = (1L << bits) - 1;
                    // - we need a long type because the maximal number of bits here is 32
                    // (but if not ALL bits/sample are 32 - such cases do not require this method)
                    final int outputIndex = (s * numberOfPixels + i) * bytesPerSample;

                    long value;
                    if (byteAligned) {
                        final int index = (i * samplesPerPixel + s) * bytesPerSample;
                        value = JArrays.getBytes8(source, index, bytesPerSample, byteOrder);
                        // - It is strange, but it is a fact:
                        //      for byte-aligned pixels (for example, 16 or 24 bits/sample),
                        // the byte order (little-endian or big-endian) is important;
                        //      for unaligned samples (for example, 12, 18 or 26 bits/sample),
                        // the byte order is ignored and actually is always big-endian,
                        // and also the bit order inside bytes is always big-endian
                        // (after applying possible bit inversion by invertFillOrderIfRequested,
                        // but this inversion is performed in the very beginning,
                        // even before decoding LZW/Deflate etc.)
                    } else {
                        if (pos >= length) {
                            return;
                        }
                        value = PackedBitArraysPer8.getBits64InReverseOrder(source, pos, bits) & 0xFFFFFFFFL;
                        pos += bits;
                        // - unsigned 32-bit value
                    }
                    if (invertValues) {
                        value = maxValue - value;
                    }
                    if (scaleWhenIncreasingBitDepth) {
                        value *= multipliers[s];
                    }
                    if (outputIndex + bytesPerSample > unpacked.length) {
                        throw new AssertionError("Out of range for unpacked data at (" +
                                xIndex + ", " + yIndex + ") inside " + sizeX + "x" + sizeY +
                                ", sample #" + s + ": " + outputIndex + "+" + bytesPerSample + ">" + unpacked.length);
                    }
                    JArrays.setBytes8(unpacked, outputIndex, value, bytesPerSample, byteOrder);
                }
            }
            pos = (pos + 7) & ~7;
            // - skipping bits until the first bit of the next whole byte
//            bitsUnpacker.skipBitsUntilNextByte();
        }
    }

    private static void extractSingleBitsAndInvertValues(
            byte[] unpacked,
            byte[] source,
            int sizeX,
            int sizeY,
            boolean invertValues) {
        final long length = PackedBitArraysPer8.unpackedLength(source);
        final long alignedLine = ((long) sizeX + 7) & ~7;
        // - skipping bits until the first bit of the next whole byte;
        // note that it may be !=sizeX only in non-tiled TIFF (when "tile width" is the width of the whole image)
        long sOffset = 0;
        long tOffset = 0;
        for (int yIndex = 0; yIndex < sizeY; yIndex++, sOffset += alignedLine, tOffset += sizeX) {
            final long actual = Math.min(sizeX, length - sOffset);
            if (actual <= 0) {
                // - source data MAY be too short in the case of invalid/strange TIFF
                break;
            }
            PackedBitArraysPer8.copyBitsFromReverseToNormalOrderNoSync(unpacked, tOffset, source, sOffset, actual);
        }
        if (invertValues) {
            PackedBitArraysPer8.notBits(unpacked, 0, 8L * (long) unpacked.length);
        }
    }

    private static void checkInterleaved(TiffTile tile) {
        if (!tile.isInterleaved()) {
            throw new IllegalArgumentException("Tile data must be interleaved for correct completing " +
                    "to decode " + tile.ifd().compressionPrettyName() + " (separated data are allowed for codecs " +
                    "like JPEG, that must fully decode data themselves, but not for this " +
                    "compression): " + tile);
        }
    }

    private static int toUnsignedByte(double v) {
        return v < 0.0 ? 0 : v > 255.0 ? 255 : (int) Math.round(v);
    }

    private static void debugPrintBits(TiffTile tile) throws TiffException {
        if (tile.index().yIndex() != 0) {
            return;
        }
        final byte[] data = tile.getDecodedData();
        final int sizeX = tile.getSizeX();
        final int[] bitsPerSample = tile.ifd().getBitsPerSample();
        final int samplesPerPixel = tile.samplesPerPixel();
        System.out.printf("%nPacked bits %s:%n", Arrays.toString(bitsPerSample));
        for (int i = 0, bit = 0; i < sizeX; i++) {
            System.out.printf("Pixel #%d: ", i);
            for (int s = 0; s < samplesPerPixel; s++) {
                final int bits = bitsPerSample[s];
                int v = 0;
                for (int j = 0; j < bits; j++, bit++) {
                    final int bitIndex = 7 - bit % 8;
                    int b = (data[bit / 8] >> bitIndex) & 1;
                    System.out.print(b);
                    v |= b << (bits - 1 - j);
                }
                System.out.printf(" = %-6d ", v);
            }
            System.out.println();
        }
    }

}
