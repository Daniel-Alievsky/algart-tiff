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

package net.algart.matrices.tiff.data;

import net.algart.arrays.PackedBitArraysPer8;
import net.algart.arrays.TooLargeArrayException;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPhotometricInterpretation;
import net.algart.matrices.tiff.tags.TagRational;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.util.Bytes;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

public class TiffPacking {
    private static final boolean OPTIMIZE_SEPARATING_WHOLE_BYTES = true;
    // - should be true for good performance; false value can help while debugging
    private static final boolean THOROUGHLY_TEST_Y_CB_CR_LOOP = false;

    private TiffPacking() {
    }

    public static boolean separateUnpackedSamples(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile);
        final TiffIFD ifd = tile.ifd();

        AtomicBoolean simpleLossless = new AtomicBoolean();
        if (!isSimpleRearrangingBytesEnough(ifd, simpleLossless)) {
            return false;
        }
        if (!OPTIMIZE_SEPARATING_WHOLE_BYTES && tile.isInterleaved()) {
            // - if tile is not interleaved, we MUST use this method:
            // separateBitsAndInvertValues does not "understand" this situation
            return false;
        }

        // We have equal number N of bits/sample for all samples,
        // N % 8 == 0, N / 8 is 1, 2, 3, 4 or 8;
        // for all other cases isSimpleRearrangingBytesEnough returns false.
        // The only unusual case here is 3 bytes/sample: 24-bit float or 24-bit integer;
        // these cases (as well as 16-bit float) will be processed later in unpackUnusualPrecisions.
        if (tile.getStoredDataLength() > tile.map().tileSizeInBytes() && !simpleLossless.get()) {
            // - Strange situation: JPEG or some extended codec has decoded to large tile.
            // But for "simple" compressions (uncompressed, Deflate) we enable this situation:
            // it helps to create special tests (for example, an image with "fake" too little dimensions).
            // (Note: for LZW compression, in current version, this situation is impossible, because
            // LZWCodec creates result array on the base of options.maxBytes field.)
            // In comparison, further adjustNumberOfPixels throws IllegalArgumentException
            // in the same situation, when its argument is false.
            throw new TiffException("Too large decoded TIFF data: " + tile.getStoredDataLength() +
                    " bytes, its is greater than one " +
                    (tile.map().getTilingMode().isTileGrid() ? "tile" : "strip") +
                    " (" + tile.map().tileSizeInBytes() + " bytes); "
                    + "probably TIFF file is corrupted or format is not properly supported");
        }
        tile.adjustNumberOfPixels(true);
        // - Note: getStoredDataLength() is unpredictable, because it is the result of decompression by a codec;
        // in particular, for JPEG compression last strip in non-tiled TIFF may be shorter or even larger
        // than a full tile.
        // If cropping boundary tiles is enabled, actual height of the last strip is reduced
        // (see readEncodedTile method), so larger data is possible (it is a minor format separately).
        // If cropping boundary tiles is disabled, larger data MAY be considered as a format error,
        // because tile sizes are the FULL sizes of tile in the grid (it is checked above independently).
        // We consider this situation as an error in a case of "complex" codecs like JPEG, JPEG-2000 etc.
        // Also note: it is better to rearrange pixels before separating (if necessary),
        // because rearranging interleaved pixels is little more simple.
        tile.separateSamplesIfNecessary();

        // Note: other 2 functions, unpackUnusualBits and decodeYCbCr, always work with interleaved
        // data and separate data themselves to a tile with correct sizes
        return true;

    }

    // Note: this method may be tested with the images ycbcr-cat.tif and dscf0013.tif
    public static boolean separateYCbCrToRGB(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile);
        final TiffIFD ifd = tile.ifd();

        if (!ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        checkInterleaved(tile);
        final byte[] data = tile.getDecodedData();

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
                    // - these formulas were used in original SCIFIO code
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
        final TiffIFD ifd = tile.ifd();

        if (OPTIMIZE_SEPARATING_WHOLE_BYTES && isSimpleRearrangingBytesEnough(ifd, null)) {
            return false;
        }
        if (ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        checkInterleaved(tile);
        if (!ifd.isStandardCompression() || ifd.isJpeg()) {
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

    /**
     * Unpacks pixel samples, stored in unusual precisions (2 or 3 bytes/sample), and returns unpacked data
     * (4 bytes/sample). Returns a reference to the source {@code samples} array if IFD does not
     * specify an unusual precision.
     *
     * <p>We call <b>unusual precisions</b> the following two cases:</p>
     * <ol>
     *     <li>every channel is encoded as N-bit integer value, where 17&le;N&le;24, and, so, requires 3 bytes;
     *     </li>
     *     <li>every channel is encoded as 16-bit or 24-bit floating point values.
     *     </li>
     * </ol>
     * <p>However, in the 1st case, this method assumes that N=24: if N=17..23 (not divisible by 8),
     * the bits should be unpacked to whole bytes (3 bytes/sample, N=24) while reading/decoding TIFF tiles,
     * i.e. before using this method.
     * See also {@link TiffMap#bitsPerUnpackedSample()}.</p>
     *
     * @param samples            the source pixel samples.
     * @param ifd                IFD (it is analysed to detect an unusual precision).
     * @param numberOfChannels   number of samples per pixel; note that this value may differ from
     *                           {@link TiffIFD#getSamplesPerPixel() ifd.getSamplesPerPixel()} when this
     *                           method is used for unpacking a single tile, for example, in
     *                           {@link TiffTile#unpackUnusualDecodedData()} method
     *                           (in which case {@code numberOfChannels} can be 1 even for several channels
     *                           when {@link TiffIFD#isPlanarSeparated()} is {@code true}).
     * @param numberOfPixels     number of pixels in {@code samples} array.
     * @param scaleUnsignedInt24 if {@code true} and there is the 1st of the cases above (3-byte integer values),
     *                           this method multiplies all (unsigned) 24-bit integer values by 256
     *                           ({@code value<<8} operator), so that the source samples bits will be placed
     *                           in bits 8..31 of the 4-byte result samples.
     * @return the unpacked samples.
     * @throws TiffException in a case of incorrect IFD.
     * @see TiffTile#unpackUnusualDecodedData()
     */
    public static byte[] unpackUnusualPrecisions(
            final byte[] samples,
            final TiffIFD ifd,
            final int numberOfChannels,
            final long numberOfPixels,
            boolean scaleUnsignedInt24) throws TiffException {
        Objects.requireNonNull(samples, "Null samples");
        Objects.requireNonNull(ifd, "Null IFD");
        TiffIFD.checkNumberOfChannels(numberOfChannels);
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        final int packedBytesPerSample = (ifd.alignedBitDepth() + 7) >>> 3;
        final TiffSampleType sampleType = ifd.sampleType();
        final boolean floatingPoint = sampleType.isFloatingPoint();
        // - actually DOUBLE is not used below
        final int bitsPerSample = ifd.tryEqualBitDepth().orElse(-1);
        final boolean float16 = bitsPerSample == 16 && floatingPoint;
        final boolean float24 = bitsPerSample == 24 && floatingPoint;
        final boolean int24 = packedBytesPerSample == 3 && !float24;
        // If the data were correctly loaded into a tile with usage TiffReader.completeDecoding method, then:
        // 1) they are aligned by 8-bit (by unpackBitsAndInvertValues method);
        // 2) number of bytes per sample may be only 1..4 or 8: other cases are rejected by unpackBitsAndInvertValues.
        // So, we only need to check a case 3 bytes (17..24 bits before correction) and special cases float16/float24.
        if (!float16 && !float24 && !int24) {
            return samples;
        }
        assert packedBytesPerSample <= 4;
        // Following code is necessary in a very rare case, and no sense to seriously optimize it
        final boolean littleEndian = ifd.isLittleEndian();

        final long size;
        if (numberOfPixels > Integer.MAX_VALUE ||
                (size = numberOfPixels * numberOfChannels * 4L) > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Too large number of pixels " + numberOfPixels +
                    " (" + numberOfChannels + " samples/pixel, 4 bytes/sample): it requires > 2 GB to store");
        }
        final int numberOfSamples = (int) (numberOfChannels * numberOfPixels);
        if ((long) numberOfSamples * packedBytesPerSample > samples.length) {
            throw new IllegalArgumentException("Too short samples array byte[" + samples.length +
                    "]: it does not contain " + numberOfPixels + " pixels per " + numberOfChannels +
                    " samples, " + packedBytesPerSample + " bytes/sample");
        }
        final byte[] unpacked = new byte[(int) size];
        if (int24) {
            for (int i = 0, disp = 0; i < numberOfSamples; i++, disp += packedBytesPerSample) {
                // - very rare case, no sense to optimize
                final int value = Bytes.toInt(samples, disp, packedBytesPerSample, littleEndian);
                final long newValue = scaleUnsignedInt24 ? (long) value << 8 : value;
                Bytes.unpack(newValue, unpacked, i * 4, 4, littleEndian);
            }
            return unpacked;
        }

//        final int mantissaBits = float16 ? 10 : 16;
//        final int exponentBits = float16 ? 5 : 7;
        for (int i = 0, disp = 0; i < numberOfSamples; i++, disp += packedBytesPerSample) {
            final int packedValue = Bytes.toInt(samples, disp, packedBytesPerSample, littleEndian);
//            final int valueToCompare = unpackFloatBits(packedValue, mantissaBits, exponentBits);
            final int value = float16 ?
                    unpack16BitFloat((short) packedValue) :
                    unpack24BitFloat(packedValue);
//            if (value != valueToCompare) {
//                System.out.printf("%h %f != %h %f%n", value, Float.intBitsToFloat(value),
//                        valueToCompare, Float.intBitsToFloat(valueToCompare));
//            }
            Bytes.unpack(value, unpacked, i * 4, 4, littleEndian);
        }
        return unpacked;
    }

    public static boolean packTiffBits(TiffTile tile) {
        Objects.requireNonNull(tile);
        if (tile.bitsPerPixel() != 1) {
            return false;
        }
        assert tile.samplesPerPixel() == 1 : "> 1 channel in " + tile;
        // Note: we do not check tile.ifd().getPhotometricInterpretation().isInvertedBrightness(),
        // because WHITE_IS_ZERO photometric interpretation is not supported;
        // in any case, we do not provide any processing of non-standard photometric interpretations
        // for writing any other sample types.
        final byte[] source = tile.getDecodedData();
        final int sizeInBytes = tile.getSizeInBytes();
        if (source.length < sizeInBytes) {
            throw new IllegalArgumentException("Stored data length " + source.length +
                    " does not match tile sizes: " + tile);
        }
        final int sizeX = tile.getSizeX();
        final int sizeY = tile.getSizeY();
        assert tile.getSizeInPixels() == sizeX * sizeY;
        final long alignedLine = ((long) sizeX + 7) & ~7;
        // - skipping bits until the first bit of the next whole byte;
        // note that it may be !=sizeX only in non-tiled TIFF (when "tile width" is the width of whole image)
        final long length = ((long) sizeY * alignedLine) >>> 3;
        if (length > Integer.MAX_VALUE) {
            // - very improbable: (sizeX*sizeY)/8 < 2^31, but (alignedLine*sizeY)/8 >= 2^31
            throw new TooLargeArrayException("Too large requested TIFF binary image " + sizeX + "x" + sizeY +
                    ": cannot fit into 2^31 bytes after aligning each line");
        }
        final byte[] result = new byte[(int) length];
        // - padded by zeros
        long sOffset = 0;
        long tOffset = 0;
        for (int yIndex = 0; yIndex < sizeY; yIndex++, sOffset += sizeX, tOffset += alignedLine) {
            PackedBitArraysPer8.copyBitsFromNormalToReverseOrderNoSync(result, tOffset, source, sOffset, sizeX);
        }
        tile.setPartiallyDecodedData(result);
        return true;
    }

    private static boolean isSimpleRearrangingBytesEnough(TiffIFD ifd, AtomicBoolean simpleLossless)
            throws TiffException {
        final TagCompression compression = ifd.optCompression().orElse(null);
        final boolean advancedFormat = compression != null && (!compression.isStandard() || compression.isJpeg());
        if (simpleLossless != null) {
            simpleLossless.set(!advancedFormat);
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

    // Common prototype, based on SCIFIO code
    private static int unpackFloatBits(int value, int mantissaBits, int exponentBits) {
        final int exponentIncrement = 127 - (pow2(exponentBits - 1) - 1);
        final int power2ExponentBitsMinus1 = pow2(exponentBits) - 1;
        final int packedBitsPerSampleMinus1 = mantissaBits + exponentBits;
        final int sign = value >> packedBitsPerSampleMinus1;
        final int power2MantissaBits = pow2(mantissaBits);
        int exponent = (value >> mantissaBits) & power2ExponentBitsMinus1;
        int mantissa = value & (power2MantissaBits - 1);

        if (exponent == 0) {
            if (mantissa != 0) {
                while ((mantissa & power2MantissaBits) == 0) {
                    mantissa <<= 1;
                    exponent--;
                }
                exponent++;
                mantissa &= power2MantissaBits - 1;
                exponent += exponentIncrement;
            }
        } else if (exponent == power2ExponentBitsMinus1) {
            exponent = 255;
        } else {
            exponent += exponentIncrement;
        }

        mantissa <<= (23 - mantissaBits);

        return (sign << 31) | (exponent << 23) | mantissa;
    }

    private static int unpack24BitFloat(int value) {
        final int mantissaBits = 16;
        final int exponentIncrement = 64;
        final int power2ExponentBitsMinus1 = 127;

        final int sign = value >> 23;
        final int power2MantissaBits = 1 << 16;
        int exponent = (value >> 16) & 127;
        int mantissa = value & 65535;

        if (exponent == 0) {
            if (mantissa != 0) {
                while ((mantissa & power2MantissaBits) == 0) {
                    mantissa <<= 1;
                    exponent--;
                }
                exponent++;
                mantissa &= 65535;
                exponent += exponentIncrement;
            }
        } else if (exponent == power2ExponentBitsMinus1) {
            exponent = 255;
        } else {
            exponent += exponentIncrement;
        }
        mantissa <<= 23 - mantissaBits;

        return (sign << 31) | (exponent << 23) | mantissa;
    }

    // From TwelveMonkey: equivalent code
    private static int unpack16BitFloat(short value) {
        int mantissa = value & 0x03ff;           // 10 bits mantissa
        int exponent = value & 0x7c00;           //  5 bits exponent

        if (exponent == 0x7c00) {               // NaN/Inf
            exponent = 0x3fc00;                 // -> NaN/Inf
        } else if (exponent != 0) {             // Normalized value
            exponent += 0x1c000;                // exp - 15 + 127

            // [ Below is commented addition from TwelveMonkey, that sometimes leads to incorrect results;
            // fixed in https://github.com/haraldk/TwelveMonkeys/issues/865 ]
            // Smooth transition
//            if (mantissa == 0 && exponent > 0x1c400) {
//                return (value & 0x8000) << 16 | exponent << 13 | 0x3ff;
//            }
        } else if (mantissa != 0) {             // && exp == 0 -> subnormal
            exponent = 0x1c400;                 // Make it normal

            do {
                mantissa <<= 1;                 // mantissa * 2
                exponent -= 0x400;              // Decrease exp by 1
            } while ((mantissa & 0x400) == 0);  // while not normal

            mantissa &= 0x3ff;                  // Discard subnormal bit
        }                                       // else +/-0 -> +/-0

        // Combine all parts,  sign << (31 - 15), value << (23 - 10)
        return (value & 0x8000) << 16 | (exponent | mantissa) << 13;
    }

    // Almost exact copy of old TiffParser.unpackBytes method
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

        final boolean littleEndian = ifd.isLittleEndian();
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
                    // - we need long type, because maximal number of bits here is 32
                    // (but if not ALL bits/sample are 32 - such cases do not require this method)
                    final int outputIndex = (s * numberOfPixels + i) * bytesPerSample;

                    long value;
                    if (byteAligned) {
                        final int index = (i * samplesPerPixel + s) * bytesPerSample;
                        value = Bytes.toLong(source, index, bytesPerSample, littleEndian);
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
                    assert outputIndex + bytesPerSample <= unpacked.length;
                    Bytes.unpack(value, unpacked, outputIndex, bytesPerSample, littleEndian);
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
            boolean invertValues) throws TiffException {
        final long length = PackedBitArraysPer8.unpackedLength(source);
        final long alignedLine = ((long) sizeX + 7) & ~7;
        // - skipping bits until the first bit of the next whole byte;
        // note that it may be !=sizeX only in non-tiled TIFF (when "tile width" is the width of whole image)
        long sOffset = 0;
        long tOffset = 0;
        for (int yIndex = 0; yIndex < sizeY; yIndex++, sOffset += alignedLine, tOffset += sizeX) {
            final long actual = Math.min(sizeX, length - sOffset);
            if (actual <= 0) {
                // - source data MAY be too short in a case of invalid/strange TIFF
                break;
            }
            PackedBitArraysPer8.copyBitsFromReverseToNormalOrderNoSync(unpacked, tOffset, source, sOffset, actual);
        }
        if (invertValues) {
            PackedBitArraysPer8.notBits(unpacked, 0, 8L * (long) unpacked.length);
        }
    }

    private static void checkInterleaved(TiffTile tile) throws TiffException {
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

    private static int pow2(int b) {
        return 1 << b;
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