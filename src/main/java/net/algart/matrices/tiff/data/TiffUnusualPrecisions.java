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
import net.algart.arrays.TooLargeArrayException;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.nio.ByteOrder;
import java.util.Objects;

public class TiffUnusualPrecisions {
    private TiffUnusualPrecisions() {
    }


    /**
     * Unpacks pixel samples, stored in unusual precisions (2 or 3 bytes/sample), and returns unpacked data
     * (4 bytes/sample). Returns a reference to the source {@code samples} array if IFD does not
     * specify unusual precision.
     *
     * <p>We call <b>unusual precisions</b> the following two cases:</p>
     * <ol>
     *     <li>every channel is encoded as an N-bit integer value, where 17&le;N&le;24, and, so, requires 3 bytes;
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
     * @param ifd                IFD (it is analyzed to detect unusual precision).
     * @param numberOfChannels   number of samples per pixel; note that this value may differ from
     *                           {@link TiffIFD#getSamplesPerPixel() ifd.getSamplesPerPixel()} when this
     *                           method is used for unpacking a single tile, for example, in
     *                           {@link TiffTile#getUnpackedData(boolean)} method
     *                           (in which case {@code numberOfChannels} can be 1 even for several channels
     *                           when {@link TiffIFD#isPlanarSeparated()} is {@code true}).
     * @param numberOfPixels     number of pixels in {@code samples} array.
     * @param scaleUnsignedInt24 if {@code true} and there is the 1st of the cases above (3-byte integer values),
     *                           this method multiplies all (unsigned) 24-bit integer values by 256
     *                           ({@code value<<8} operator), so that the source samples bits will be placed
     *                           in bits 8..31 of the 4-byte result samples.
     * @return the unpacked samples.
     * @throws TiffException in the case of incorrect IFD.
     * @see TiffTile#getUnpackedData(boolean)
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
        final ByteOrder byteOrder = ifd.getByteOrder();

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
                final long value = JArrays.getBytes8(samples, disp, packedBytesPerSample, byteOrder);
                final long newValue = scaleUnsignedInt24 ? value << 8 : value;
                JArrays.setBytes8(unpacked, i * 4, newValue, 4, byteOrder);
            }
            return unpacked;
        }

//        final int mantissaBits = float16 ? 10 : 16;
//        final int exponentBits = float16 ? 5 : 7;
        for (int i = 0, disp = 0; i < numberOfSamples; i++, disp += packedBytesPerSample) {
            final int packedValue = (int) JArrays.getBytes8(samples, disp, packedBytesPerSample, byteOrder);
//            final int valueToCompare = unpackFloatBits(packedValue, mantissaBits, exponentBits);
            final int value = float16 ?
                    unpackFloat16Bit((short) packedValue) :
                    unpackFloat24Bit(packedValue);
//            if (value != valueToCompare) {
//                System.out.printf("%h %f != %h %f%n", value, Float.intBitsToFloat(value),
//                        valueToCompare, Float.intBitsToFloat(valueToCompare));
//            }
            JArrays.setBytes8(unpacked, i * 4, value, 4, byteOrder);
        }
        return unpacked;
    }

    // Common prototype, based on SCIFIO code
    private static int unpackFloat(int value, int mantissaBits, int exponentBits) {
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

    private static int unpackFloat24Bit(int value) {
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
    private static int unpackFloat16Bit(short value) {
        int mantissa = value & 0x03ff;           // 10 bits mantissa
        int exponent = value & 0x7c00;           //  5 bits exponent

        if (exponent == 0x7c00) {               // NaN/Inf
            exponent = 0x3fc00;                 // -> NaN/Inf
        } else if (exponent != 0) {             // Normalized value
            exponent += 0x1c000;                // exp - 15 + 127

            // [ Below is commented addition from the TwelveMonkey, that sometimes leads to incorrect results;
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

    private static int pow2(int b) {
        return 1 << b;
    }
}
