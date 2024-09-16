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

package net.algart.matrices.tiff.codecs;

import net.algart.arrays.JArrays;
import net.algart.arrays.MutableShortArray;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.util.Objects;

/**
 * Decompresses lossless JPEG images.
 * <p>
 * Note: the only change in comparison with SCIFIO codec is removing usage of the Context
 * and usage of HuffmanCodecReduced. - Daniel Alievsky
 *
 * @author Melissa Linkert
 */
public class LosslessJPEGCodec extends AbstractCodec {
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    // -- Constants --

    // Start of Frame markers - non-differential, Huffman coding
    public static final int SOF0 = 0xffc0; // baseline DCT

    public static final int SOF1 = 0xffc1; // extended sequential DCT

    public static final int SOF2 = 0xffc2; // progressive DCT

    public static final int SOF3 = 0xffc3; // lossless (sequential)

    // Start of Frame markers - differential, Huffman coding
    public static final int SOF5 = 0xffc5; // differential sequential DCT

    public static final int SOF6 = 0xffc6; // differential progressive DCT

    public static final int SOF7 = 0xffc7; // differential lossless (sequential)

    // Start of Frame markers - non-differential, arithmetic coding
    public static final int JPG = 0xffc8; // reserved for JPEG extensions

    public static final int SOF9 = 0xffc9; // extended sequential DCT

    public static final int SOF10 = 0xffca; // progressive DCT

    public static final int SOF11 = 0xffcb; // lossless (sequential)

    // Start of Frame markers - differential, arithmetic coding
    public static final int SOF13 = 0xffcd; // differential sequential DCT

    public static final int SOF14 = 0xffce; // differential progressive DCT

    public static final int SOF15 = 0xffcf; // differential lossless
    // (sequential)

    public static final int DHT = 0xffc4; // define Huffman table(s)

    public static final int DAC = 0xffcc; // define arithmetic coding conditions

    // Restart interval termination
    public static final int RST_0 = 0xffd0;

    public static final int RST_1 = 0xffd1;

    public static final int RST_2 = 0xffd2;

    public static final int RST_3 = 0xffd3;

    public static final int RST_4 = 0xffd4;

    public static final int RST_5 = 0xffd5;

    public static final int RST_6 = 0xffd6;

    public static final int RST_7 = 0xffd7;

    public static final int SOI = 0xffd8; // start of image

    public static final int EOI = 0xffd9; // end of image

    public static final int SOS = 0xffda; // start of scan

    public static final int DQT = 0xffdb; // define quantization table(s)

    public static final int DNL = 0xffdc; // define number of lines

    public static final int DRI = 0xffdd; // define restart interval

    public static final int DHP = 0xffde; // define hierarchical progression

    public static final int EXP = 0xffdf; // expand reference components

    public static final int COM = 0xfffe; // comment
    // -- Codec API methods --

    @Override
    public byte[] compress(final byte[] data, final Options options) throws TiffException {
        throw new UnsupportedTiffFormatException("Lossless JPEG compression not supported");
    }

    /**
     * The Options parameter should have the following fields set:
     * {@link Options#isInterleaved()}
     * {@link Options#isLittleEndian()}
     */
    @Override
    public byte[] decompress(final DataHandle<? extends Location> in, Options options) throws IOException {
        Objects.requireNonNull(in, "Null input handle");
        if (options == null) options = new Options();
        byte[] buf = new byte[0];

        int width = 0, height;
        int bitsPerSample = 0, nComponents = 0, bytesPerSample = 0;
        int[] horizontalSampling, verticalSampling;
        int[] quantizationTable;
        short[][] huffmanTables = null;

        int startPredictor;

        int[] dcTable, acTable;

        while (in.offset() < in.length() - 1) {
            final int code = in.readShort() & 0xffff;
            int length = in.readShort() & 0xffff;
            final long fp = in.offset();
            if (length > 0xff00) {
                length = 0;
                in.seek(fp - 2);
            } else if (code == SOS) {
                nComponents = in.read();
                dcTable = new int[nComponents];
                acTable = new int[nComponents];
                for (int i = 0; i < nComponents; i++) {
                    in.read(); // componentSelector
                    final int tableSelector = in.read();
                    dcTable[i] = (tableSelector & 0xf0) >> 4;
                    acTable[i] = tableSelector & 0xf;
                }
                startPredictor = in.read();
                in.read(); // endPredictor
                in.read(); // least significant 4 bits = pointTransform

                // read image data

                byte[] toDecode = new byte[(int) (in.length() - in.offset())];
                in.read(toDecode);

                // scrub out byte stuffing

                final HuffmanCodecReduced.ByteVector b = new HuffmanCodecReduced.ByteVector();
                for (int i = 0; i < toDecode.length; i++) {
                    b.add(toDecode[i]);
                    if (toDecode[i] == (byte) 0xff && toDecode[i + 1] == 0) i++;
                }
                toDecode = b.toByteArray();

                final HuffmanCodecReduced.BitBuffer bb = new HuffmanCodecReduced.BitBuffer(toDecode);
                final HuffmanCodecReduced huffman = new HuffmanCodecReduced();
                final HuffmanCodecReduced.HuffmanCodecOptions huffmanOptions =
                        new HuffmanCodecReduced.HuffmanCodecOptions();
                huffmanOptions.bitsPerSample = bitsPerSample;
                huffmanOptions.maxBytes = buf.length / nComponents;

                int nextSample = 0;
                while (nextSample < buf.length / nComponents) {
                    for (int i = 0; i < nComponents; i++) {
                        if (huffmanTables != null) {
                            huffmanOptions.table = huffmanTables[dcTable[i]];
                        }
                        int v = 0;

                        if (huffmanOptions.table != null) {
                            v = huffman.getSample(bb, huffmanOptions);
                            if (nextSample == 0) {
                                v += (int) Math.pow(2, bitsPerSample - 1);
                            }
                        } else {
                            throw new UnsupportedTiffFormatException("Arithmetic coding not supported");
                        }

                        // apply predictor to the sample
                        int predictor = startPredictor;
                        if (nextSample < width * bytesPerSample) predictor = 1;
                        else if ((nextSample % (width * bytesPerSample)) == 0) {
                            predictor = 2;
                        }

                        final int componentOffset = i * (buf.length / nComponents);

                        final int indexA = nextSample - bytesPerSample + componentOffset;
                        final int indexB = nextSample - width * bytesPerSample +
                                componentOffset;
                        final int indexC = nextSample - (width + 1) * bytesPerSample +
                                componentOffset;

//                        if (indexA >= 0 && indexA < buf.length - 4)
//                            assert Bytes.toInt(buf, indexA, 4, false) ==
//                                    (int) JArrays.getBytes8InBigEndianOrder(buf, indexA, 4);
//                        final int sampleA = indexA < 0 ? 0 : Bytes.toInt(buf, indexA, bytesPerSample, false);
//                        final int sampleB = indexB < 0 ? 0 : Bytes.toInt(buf, indexB, bytesPerSample, false);
//                        final int sampleC = indexC < 0 ? 0 : Bytes.toInt(buf, indexC, bytesPerSample, false);

                        final int sampleA = indexA < 0 ? 0 :
                                (int) JArrays.getBytes8InBigEndianOrder(buf, indexA, bytesPerSample);
                        final int sampleB = indexB < 0 ? 0 :
                                (int) JArrays.getBytes8InBigEndianOrder(buf, indexB, bytesPerSample);
                        final int sampleC = indexC < 0 ? 0 :
                                (int) JArrays.getBytes8InBigEndianOrder(buf, indexC, bytesPerSample);

                        if (nextSample > 0) {
                            int pred = 0;
                            switch (predictor) {
                                case 1:
                                    pred = sampleA;
                                    break;
                                case 2:
                                    pred = sampleB;
                                    break;
                                case 3:
                                    pred = sampleC;
                                    break;
                                case 4:
                                    pred = sampleA + sampleB + sampleC;
                                    break;
                                case 5:
                                    pred = sampleA + ((sampleB - sampleC) / 2);
                                    break;
                                case 6:
                                    pred = sampleB + ((sampleA - sampleC) / 2);
                                    break;
                                case 7:
                                    pred = (sampleA + sampleB) / 2;
                                    break;
                            }
                            v += pred;
                        }

                        final int offset = componentOffset + nextSample;

                        JArrays.setBytes8InBigEndianOrder(buf, offset, v, bytesPerSample);
//                        Bytes.unpack(v, buf, offset, bytesPerSample, false);
                    }
                    nextSample += bytesPerSample;
                }
            } else {
                length -= 2; // stored length includes length param
                if (length == 0) continue;

                if (code == EOI) {
                } else if (code == SOF3) {
                    // lossless w/Huffman coding
                    bitsPerSample = in.read();
                    height = in.readShort();
                    width = in.readShort();
                    nComponents = in.read();
                    horizontalSampling = new int[nComponents];
                    verticalSampling = new int[nComponents];
                    quantizationTable = new int[nComponents];
                    for (int i = 0; i < nComponents; i++) {
                        in.skipBytes(1);
                        final int s = in.read();
                        horizontalSampling[i] = (s & 0xf0) >> 4;
                        verticalSampling[i] = s & 0x0f;
                        quantizationTable[i] = in.read();
                    }

                    bytesPerSample = bitsPerSample / 8;
                    if ((bitsPerSample % 8) != 0) bytesPerSample++;

                    buf = new byte[width * height * nComponents * bytesPerSample];
                } else if (code == SOF11) {
                    throw new UnsupportedTiffFormatException("Arithmetic coding is not yet supported");
                } else if (code == DHT) {
                    if (huffmanTables == null) {
                        huffmanTables = new short[4][];
                    }
                    final int s = in.read();
//                    final byte tableClass = (byte) ((s & 0xf0) >> 4);
                    final byte destination = (byte) (s & 0xf);
                    final int[] nCodes = new int[16];
                    final MutableShortArray table = MutableShortArray.newArray();
                    for (int i = 0; i < nCodes.length; i++) {
                        nCodes[i] = in.read();
                        table.pushShort((short) nCodes[i]);
                    }

                    for (int nCode : nCodes) {
                        for (int j = 0; j < nCode; j++) {
                            table.pushShort((short) (in.read() & 0xff));
                        }
                    }
                    huffmanTables[destination] = new short[table.length32()];
                    for (int i = 0; i < huffmanTables[destination].length; i++) {
                        huffmanTables[destination][i] = (short) table.getShort(i);
                    }
                }
                in.seek(fp + length);
            }
        }

        if (options.interleaved && nComponents > 1) {
            // data is stored in planar (RRR...GGG...BBB...) order
            final byte[] newBuf = new byte[buf.length];
            for (int i = 0; i < buf.length; i += nComponents * bytesPerSample) {
                for (int c = 0; c < nComponents; c++) {
                    final int src = c * (buf.length / nComponents) + (i / nComponents);
                    final int dst = i + c * bytesPerSample;
                    System.arraycopy(buf, src, newBuf, dst, bytesPerSample);
                }
            }
            buf = newBuf;
        }

        if (options.littleEndian && bytesPerSample > 1) {
            // data is stored in big endian order
            // reverse the bytes in each sample
            final byte[] newBuf = new byte[buf.length];
            for (int i = 0; i < buf.length; i += bytesPerSample) {
                for (int q = 0; q < bytesPerSample; q++) {
                    newBuf[i + bytesPerSample - q - 1] = buf[i + q];
                }
            }
            buf = newBuf;
        }

        return buf;
    }
}
