/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.matrices.tiff.codecs;

import net.algart.arrays.Arrays;
import net.algart.arrays.PArray;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.tags.TagCompression;

import java.util.Objects;

/**
 * Codec for SGI LogL and LogLuv compressed TIFF images (Compression 34676 and 34677).
 *
 * <p>Decompression algorithms and lookup tables in this class are ported and adapted from:</p>
 * <ul>
 *   <li><b>MIPAV</b> (Medical Image Processing, Analysis and Visualization),
 *       developed by NIH CIT (authors: Matthew J. McAuliffe, Ph.D., William Gandler).</li>
 *   <li><b>LibTIFF</b> ({@code tif_luv.c}, {@code tif_luv.h}, {@code uvcode.h}),
 *       originally created by Sam Leffler, Silicon Graphics, Inc., and Greg Ward.</li>
 * </ul>
 */

public class LogLuvCodec implements TiffCodec {
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * Portions of this class are derived from MIPAV (NIH CIT) and LibTIFF.
     *
     * MIPAV License (NIH CIT): Permission is granted free of charge to use, copy,
     * modify, and distribute this software. Designed for research purposes only;
     * clinical applications are neither recommended nor advised.
     * Provided "AS IS", without warranty of any kind.
     *
     * LibTIFF License: Copyright (c) 1988-1997 Sam Leffler, 1991-1997 SGI.
     * Permission to use, copy, modify, and distribute for any purpose is granted
     * provided that copyright notices and this permission notice appear in all copies.
     */

    private static final double UVSCALE = 410.0;
    private static final double M_LN2 = 0.69314718055994530942;
    private static final double U_NEU = 0.210526316;
    private static final double V_NEU = 0.473684211;
    private static final float[] LOG_LUV_24_USTART = new float[]{
            0.247663f, 0.243779f, 0.241684f, 0.237874f, 0.235906f, 0.232153f,
            0.228352f, 0.226259f, 0.222371f, 0.220410f, 0.214710f, 0.212714f, 0.210721f, 0.204976f, 0.202986f,
            0.199245f, 0.195525f, 0.193560f, 0.189878f, 0.186216f, 0.186216f, 0.182592f, 0.179003f, 0.175466f,
            0.172001f, 0.172001f, 0.168612f, 0.168612f, 0.163575f, 0.158642f, 0.158642f, 0.158642f, 0.153815f,
            0.153815f, 0.149097f, 0.149097f, 0.142746f, 0.142746f, 0.142746f, 0.138270f, 0.138270f, 0.138270f,
            0.132166f, 0.132166f, 0.126204f, 0.126204f, 0.126204f, 0.120381f, 0.120381f, 0.120381f, 0.120381f,
            0.112962f, 0.112962f, 0.112962f, 0.107450f, 0.107450f, 0.107450f, 0.107450f, 0.100343f, 0.100343f,
            0.100343f, 0.095126f, 0.095126f, 0.095126f, 0.095126f, 0.088276f, 0.088276f, 0.088276f, 0.088276f,
            0.081523f, 0.081523f, 0.081523f, 0.081523f, 0.074861f, 0.074861f, 0.074861f, 0.074861f, 0.068290f,
            0.068290f, 0.068290f, 0.068290f, 0.063573f, 0.063573f, 0.063573f, 0.063573f, 0.057219f, 0.057219f,
            0.057219f, 0.057219f, 0.050985f, 0.050985f, 0.050985f, 0.050985f, 0.050985f, 0.044859f, 0.044859f,
            0.044859f, 0.044859f, 0.040571f, 0.040571f, 0.040571f, 0.040571f, 0.036339f, 0.036339f, 0.036339f,
            0.036339f, 0.032139f, 0.032139f, 0.032139f, 0.032139f, 0.027947f, 0.027947f, 0.027947f, 0.023739f,
            0.023739f, 0.023739f, 0.023739f, 0.019504f, 0.019504f, 0.019504f, 0.016976f, 0.016976f, 0.016976f,
            0.016976f, 0.012639f, 0.012639f, 0.012639f, 0.009991f, 0.009991f, 0.009991f, 0.009016f, 0.009016f,
            0.009016f, 0.006217f, 0.006217f, 0.005097f, 0.005097f, 0.005097f, 0.003909f, 0.003909f, 0.002340f,
            0.002389f, 0.001068f, 0.001653f, 0.000717f, 0.001614f, 0.000270f, 0.000484f, 0.001103f, 0.001242f,
            0.001188f, 0.001011f, 0.000709f, 0.000301f, 0.002416f, 0.003251f, 0.003246f, 0.004141f, 0.005963f,
            0.008839f, 0.010490f, 0.016994f, 0.023659f};
    private static final int[] LOG_LUV_24_NCUM = new int[]{
            0, 4, 10, 17, 26, 36, 48, 62, 77, 94, 112, 133, 155, 178, 204, 231, 260, 291,
            323, 357, 393, 429, 467, 507, 549, 593, 637, 683, 729, 778, 830, 882, 934, 989, 1044, 1102, 1160, 1222,
            1284, 1346, 1411, 1476, 1541, 1610, 1679, 1752, 1825, 1898, 1975, 2052, 2129, 2206, 2288, 2370, 2452,
            2538, 2624, 2710, 2796, 2887, 2978, 3069, 3164, 3259, 3354, 3449, 3549, 3649, 3749, 3849, 3954, 4059,
            4164, 4269, 4379, 4489, 4599, 4709, 4824, 4939, 5054, 5169, 5288, 5407, 5526, 5645, 5769, 5893, 6017,
            6141, 6270, 6399, 6528, 6657, 6786, 6920, 7054, 7188, 7322, 7460, 7598, 7736, 7874, 8016, 8158, 8300,
            8442, 8588, 8734, 8880, 9026, 9176, 9326, 9476, 9630, 9784, 9938, 10092, 10250, 10408, 10566, 10727,
            10888, 11049, 11210, 11375, 11540, 11705, 11873, 12041, 12209, 12379, 12549, 12719, 12892, 13065,
            13240, 13415, 13590, 13767, 13944, 14121, 14291, 14455, 14612, 14762, 14905, 15041, 15170, 15293,
            15408, 15517, 15620, 15717, 15806, 15888, 15964, 16033, 16095, 16150, 16197, 16237, 16268};
    private static final int UV_NDIVS = 16289;
    private static final float UV_VSTART = 0.016940f;
    private static final float UV_SQSIZ = 0.003500f;
    private static final int UV_NVS = 163;

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        throw new UnsupportedTiffFormatException("SGI LogL / LogLuv compression is not supported");
    }

    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final TiffIFD ifd = options.getIfd();
        Objects.requireNonNull(ifd, "IFD is not set in the options");

//         System.out.println("!!! " + options.getPhotometric());
        final TiffSampleType sampleType = options.getSampleType();
        final int bytesPerSample = sampleType.bytesPerSample().orElseThrow(() ->
                new UnsupportedTiffFormatException("Sample type " + sampleType.prettyName() +
                        " is not supported for LogL/LogLuv compression"));
        final int bitsPerSample = options.optRawEqualBitsPerSample().orElse(-1);
        if (!(bitsPerSample == 8 || bitsPerSample == 16 || (sampleType.isFloatingPoint() && bitsPerSample == 32))) {
            throw new UnsupportedTiffFormatException("Non-standard " +
                    java.util.Arrays.toString(ifd.getBitsPerSample()) + " bits per sample (" +
                    sampleType.prettyName() +
                    ") is not supported for LogL/LogLuv compression: must be 8-bit, 16-bit or 32-bit float");
        }
        float[] floats = decodeLogLuvFloats(data, options);
        final int resultLength = floats.length * bytesPerSample;
        final PArray array = Arrays.asPrecision(PArray.as(floats), sampleType.elementType());
        byte[] result = new byte[resultLength];
        Arrays.toBytes(result, array, options.getByteOrder());
        return result;
    }

    private static float[] decodeLogLuvFloats(byte[] data, Options options) throws UnsupportedTiffFormatException {
        final int dimX = options.getWidth();
        final int dimY = options.getHeight();
        final int samplesPerPixel = options.getSamplesPerPixel();
        float[] result = new float[dimX * dimY * samplesPerPixel];
        final TagCompression compression = options.getCompression();
        switch (compression) {
            case SGI_LOG -> {
                switch (samplesPerPixel) {
                    case 1 -> {
                        decodeLogL16(result, data, dimX, dimY);
                        return result;
                    }
                    case 3 -> {
                        decodeLogLuv32(result, data, dimX, dimY);
                        return result;
                    }
                }
            }
            case SGI_LOG24 -> {
                if (samplesPerPixel == 3) {
                    decodeLogLuv24(result, data, dimX, dimY);
                    return result;
                }
            }
        }
        throw new UnsupportedTiffFormatException("Compression \"" + compression +
                "\" for " + samplesPerPixel + " channels is not supported for LogL/LogLuv compression");
    }

    public static void decodeLogL16(float[] dataOut, byte[] dataIn, int dimX, int dimY) {
        int bytesToRead = dataIn.length;
        int inPosition = 0;
        final byte[] dataTemp = new byte[2 * dimY * dimX];
        /* RLE decompression into 2 byte planes (high byte m=0, low byte m=1) */
        for (int row = 0; row < dimY; row++) {
            for (int m = 0; m < 2; m++) {
                for (int i = 0; i < dimX && bytesToRead > 0; ) {
                    int rc;
                    if ((dataIn[inPosition] & 0xff) >= 128) { // run
                        rc = (dataIn[inPosition++] & 0xff) + (2 - 128);
                        byte by = dataIn[inPosition++];
                        bytesToRead -= 2;
                        while ((rc-- > 0) && (i < dimX)) {
                            dataTemp[2 * row * dimX + 2 * i + m] = by;
                            i++;
                        }
                    } else { // non-run
                        rc = dataIn[inPosition++] & 0xff;
                        while ((--bytesToRead > 0) && (rc-- > 0) && (i < dimX)) {
                            dataTemp[2 * row * dimX + 2 * i + m] = dataIn[inPosition++];
                            i++;
                        }
                    }
                }
            }
        }

        /* Convert 16-bit LogL integers to luminance floats */
        final int size = dimX * dimY;
        for (int i = 0; i < size; i++) {
            int logLum = (((dataTemp[2 * i] << 8) & 0xff00) | (dataTemp[2 * i + 1] & 0xff));
            boolean negative = (logLum & 0x8000) != 0;
            int le = logLum & 0x7fff;
            if (le == 0 || negative) {
                dataOut[i] = 0.0f;
            } else {
                double lum = Math.exp(M_LN2 / 256.0 * (le + 0.5) - M_LN2 * 64.0);
                dataOut[i] = lum <= 0.0 ? 0.0f : (float) Math.sqrt(lum);
            }
        }
    }

    public static void decodeLogLuv32(float[] dataOut, byte[] dataIn, int dimX, int dimY) {
        int bytesToRead = dataIn.length;
        int inPosition = 0;
        final byte[] dataTemp = new byte[4 * dimY * dimX];

        /* get each byte string */
        for (int row = 0; row < dimY; row++) {
            for (int m = 0; m < 4; m++) {
                for (int i = 0; i < dimX && bytesToRead > 0; ) {
                    int rc;
                    if ((dataIn[inPosition] & 0xff) >= 128) { // run
                        rc = (dataIn[inPosition++] & 0xff) + (2 - 128);
                        byte by = dataIn[inPosition++];
                        bytesToRead -= 2;
                        while ((rc-- > 0) && (i < dimX)) {
                            dataTemp[4 * row * dimX + 4 * i + m] = by;
                            i++;
                        }
                    } // if (dataIn[inPosition] >= 128)
                    else { // non-run
                        rc = dataIn[inPosition++] & 0xff;
                        while ((--bytesToRead > 0) && (rc-- > 0) && (i < dimX)) {
                            dataTemp[4 * row * dimX + 4 * i + m] = dataIn[inPosition++];
                            i++;
                        }
                    } // else non-run
                }
            }
        }
        final double[] xyz = new double[3];
        final int size = dimX * dimY;
        for (int i = 0; i < size; i++) {
            int logLum = (((dataTemp[4 * i] << 8) & 0xff00) | (dataTemp[4 * i + 1] & 0xff));
            final int disp = 3 * i;
            if (((logLum & 0x8000) != 0) || (logLum == 0)) {
                // Don't allow negative luminance
                dataOut[disp] = 0;
                dataOut[disp + 1] = 0;
                dataOut[disp + 2] = 0;
            } else {
                int le = logLum & 0x7fff;
                double lum = Math.exp(M_LN2 / 256.0 * (le + 0.5) - M_LN2 * 64.0);
                double u = 1. / UVSCALE * ((dataTemp[4 * i + 2] & 0xff) + 0.5);
                double v = 1. / UVSCALE * ((dataTemp[4 * i + 3] & 0xff) + 0.5);
                double s = 1. / (6.0 * u - 16.0 * v + 12.0);
                double x = 9.0 * u * s;
                double y = 4.0 * v * s;
                xyz[0] = x / y * lum;
                xyz[1] = lum;
                xyz[2] = (1.0 - x - y) / y * lum;
                /* assume CCIR-709 primaries */
                double r = 2.690 * xyz[0] + -1.276 * xyz[1] + -0.414 * xyz[2];
                double g = -1.022 * xyz[0] + 1.978 * xyz[1] + 0.044 * xyz[2];
                double b = 0.061 * xyz[0] + -0.224 * xyz[1] + 1.163 * xyz[2];
                /* assume 2.0 gamma for speed */
                /* could use integer sqrt approx., but this is probably faster */
                dataOut[disp]     = r <= 0.0 ? 0.0f : (float) Math.sqrt(r);
                dataOut[disp + 1] = g <= 0.0 ? 0.0f : (float) Math.sqrt(g);
                dataOut[disp + 2] = b <= 0.0 ? 0.0f : (float) Math.sqrt(b);
            }
        }
    }

    public static void decodeLogLuv24(float[] dataOut, byte[] dataIn, int dimX, int dimY) {
        final double[] xyz = new double[3];
        for (int row = 0; row < dimY; row++) {
            for (int i = 0; i < dimX; i++) {
                final int disp = 3 * row * dimX + 3 * i;
                int tp = ((dataIn[disp] << 16) & 0xff0000)
                        | ((dataIn[disp + 1] << 8) & 0xff00)
                        | (dataIn[disp + 2] & 0xff);
                int p10 = (tp >> 14 & 0x3ff);
                // Compute luminance from 10-bit LogL
                if (p10 == 0) {
                    dataOut[disp] = 0;
                    dataOut[disp + 1] = 0;
                    dataOut[disp + 2] = 0;
                } else {
                    double lum = Math.exp(M_LN2 / 64.0 * (p10 + 0.5) - M_LN2 * 12.0);
                    if (lum <= 0.0) {
                        dataOut[disp] = 0;
                        dataOut[disp + 1] = 0;
                        dataOut[disp + 2] = 0;
                    } else {
                        // Decode color
                        int ce = tp & 0x3fff;
                        double v;
                        double u;
                        if (ce >= UV_NDIVS) {
                            u = U_NEU;
                            v = V_NEU;
                        } else {
                            // binary search
                            int lower = 0;
                            int upper = UV_NVS;
                            int vi;
                            int ui;
                            while (upper - lower > 1) {
                                vi = (lower + upper) >> 1;
                                ui = ce - LOG_LUV_24_NCUM[vi];
                                if (ui > 0) {
                                    lower = vi;
                                } else if (ui < 0) {
                                    upper = vi;
                                } else {
                                    lower = vi;
                                    break;
                                }
                            } // while (upper - lower > 1)
                            vi = lower;
                            ui = ce - LOG_LUV_24_NCUM[vi];
                            u = LOG_LUV_24_USTART[vi] + (ui + 0.5) * UV_SQSIZ;
                            v = UV_VSTART + (vi + .5) * UV_SQSIZ;
                        } // else binary search
                        double s = 1.0 / (6.0 * u - 16.0 * v + 12.0);
                        double x = 9.0 * u * s;
                        double y = 4.0 * v * s;
                        // Convert to XYZ
                        xyz[0] = x / y * lum;
                        xyz[1] = lum;
                        xyz[2] = (1.0 - x - y) / y * lum;
                        /* assume CCIR-709 primaries */
                        double r = 2.690 * xyz[0] + -1.276 * xyz[1] + -0.414 * xyz[2];
                        double g = -1.022 * xyz[0] + 1.978 * xyz[1] + 0.044 * xyz[2];
                        double b = 0.061 * xyz[0] + -0.224 * xyz[1] + 1.163 * xyz[2];
                        /* assume 2.0 gamma for speed */
                        /* could use integer sqrt approx., but this is probably faster */
                        dataOut[disp]     = r <= 0.0 ? 0.0f : (float) Math.sqrt(r);
                        dataOut[disp + 1] = g <= 0.0 ? 0.0f : (float) Math.sqrt(g);
                        dataOut[disp + 2] = b <= 0.0 ? 0.0f : (float) Math.sqrt(b);
                    }
                }
            }
        }
    }
}