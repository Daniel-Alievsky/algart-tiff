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

import net.algart.arrays.JArrays;
import net.algart.arrays.MutableShortArray;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.util.Objects;

/**
 * Decompresses lossless JPEG images.
 * <p>
 * Note: the only change in comparison with SCIFIO codec is removing usage of the Context
 * and usage of HuffmanCodecReduced.
 * - Daniel Alievsky
 *
 * @author Melissa Linkert
 */
public class LosslessJPEGCodec extends StreamTiffCodec {
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
    private static final int SOF0 = 0xffc0; // baseline DCT
    private static final int SOF1 = 0xffc1; // extended sequential DCT
    private static final int SOF2 = 0xffc2; // progressive DCT
    private static final int SOF3 = 0xffc3; // lossless (sequential)

    // Start of Frame markers - differential, Huffman coding
    private static final int SOF5 = 0xffc5; // differential sequential DCT
    private static final int SOF6 = 0xffc6; // differential progressive DCT
    private static final int SOF7 = 0xffc7; // differential lossless (sequential)

    // Start of Frame markers - non-differential, arithmetic coding
    private static final int JPG = 0xffc8; // reserved for JPEG extensions
    private static final int SOF9 = 0xffc9; // extended sequential DCT
    private static final int SOF10 = 0xffca; // progressive DCT
    private static final int SOF11 = 0xffcb; // lossless (sequential)

    // Start of Frame markers - differential, arithmetic coding
    private static final int SOF13 = 0xffcd; // differential sequential DCT
    private static final int SOF14 = 0xffce; // differential progressive DCT
    private static final int SOF15 = 0xffcf; // differential lossless
    // (sequential)
    private static final int DHT = 0xffc4; // define Huffman table(s)
    private static final int DAC = 0xffcc; // define arithmetic coding conditions

    // Restart interval termination
    private static final int RST_0 = 0xffd0;
    private static final int RST_1 = 0xffd1;
    private static final int RST_2 = 0xffd2;
    private static final int RST_3 = 0xffd3;
    private static final int RST_4 = 0xffd4;
    private static final int RST_5 = 0xffd5;
    private static final int RST_6 = 0xffd6;
    private static final int RST_7 = 0xffd7;
    private static final int SOI = 0xffd8; // start of image
    private static final int EOI = 0xffd9; // end of image
    private static final int SOS = 0xffda; // start of scan
    private static final int DQT = 0xffdb; // define quantization table(s)
    private static final int DNL = 0xffdc; // define number of lines
    private static final int DRI = 0xffdd; // define restart interval
    private static final int DHP = 0xffde; // define hierarchical progression
    private static final int EXP = 0xffdf; // expand reference components
    private static final int COM = 0xfffe; // comment
    // -- Codec API methods --

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        throw new UnsupportedTiffFormatException("Lossless JPEG compression not supported");
    }

    /**
     * The Options parameter should have the following fields set:
     * {@link Options#isInterleaved()}
     * {@link Options#isLittleEndian()}
     */
    @Override
    public byte[] decompress(DataHandle<?> in, Options options) throws IOException {
        Objects.requireNonNull(in, "Null input stream");
        Objects.requireNonNull(options, "Null codec options");
        return new StreamDecoder(in, options).decode();
    }

    private static class StreamDecoder {
        private final DataHandle<?> in;
        private final Options options;
        private final boolean scaleWhenIncreasingBitDepth;

        private byte[] buf = new byte[0];
        private int width = 0;
        private int height = 0;
        private int bitsPerSample = 0;
        private int nComponents = 0;
        private int bytesPerSample = 0;
        private short[][] huffmanTables = null;

        int startPredictor;

        int[] dcTable, acTable;

        private StreamDecoder(DataHandle<?> in, Options options) {
            this.in = Objects.requireNonNull(in);
            this.options = Objects.requireNonNull(options);
            this.scaleWhenIncreasingBitDepth = options.getIo() instanceof TiffReader reader &&
                    reader.isScaleWhenIncreasingBitDepth();
        }

        byte[] decode() throws IOException {
            while (in.offset() < in.length() - 1) {
                final int code = in.readShort() & 0xffff;
                int length = in.readShort() & 0xffff;
                final long fp = in.offset();
                if (length > 0xff00) {
                    length = 0;
                    in.seek(fp - 2);
                } else if (code == SOS) {
                    decodeScan();
                } else {
                    length -= 2;
                    // stored length includes length param
                    if (length == 0) {
                        continue;
                    }
                    if (code == EOI) {
                        // nothing to do
                    } else if (code == SOF3) {
                        // lossless w/Huffman coding
                        bitsPerSample = in.read();
                        height = in.readShort() & 0xFFFF;
                        width = in.readShort() & 0xFFFF;
                        nComponents = in.read();
                        for (int i = 0; i < nComponents; i++) {
                            in.skipBytes(1);
                            final int s = in.read();
                            final int hSampling = (s & 0xf0) >> 4;
                            final int vSampling = s & 0x0f;
                            if (hSampling != 1 || vSampling != 1) {
                                throw new UnsupportedTiffFormatException(
                                        "Lossless JPEG with subsampling is not supported (channel #" +
                                                i + " has sampling " + hSampling + "x" + vSampling + ")");
                            }
                            in.read(); // QTable is not used in lossless JPEG
                        }
                        bytesPerSample = (bitsPerSample + 7) >>> 3;
                        final int sizeInBytes = TiffIFD.sizeOfRegionInBytes(
                                width, height, nComponents, 8 * bytesPerSample);
                        buf = new byte[sizeInBytes];
                    } else if (code == SOF11) {
                        throw new UnsupportedTiffFormatException(
                                "Cannot decode lossless JPEG: arithmetic coding is not yet supported");
                    } else if (code == DHT) {
                        readHuffmanTables();
                    }
                    in.seek(fp + length);
                }
            }

            interleave();
            fixByteOrder();
            return buf;
        }

        private void readHuffmanTables() throws IOException {
            if (huffmanTables == null) {
                huffmanTables = new short[4][];
            }
            final int s = in.read();
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

        private void decodeScan() throws IOException {
            if (buf.length == 0) {
                throw new UnsupportedTiffFormatException(
                        "Invalid lossless JPEG stream: SOS marker found before necessary SOF3 marker (0xFFC3)");
            }
            final int nComponents = in.read();
            final int numberOfPixels = buf.length / nComponents;
            this.nComponents = nComponents;
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

            byte[] toDecode = new byte[(int) (in.length() - in.offset())];
            in.read(toDecode);
            final int scalingShift = scaleWhenIncreasingBitDepth ? 8 * bytesPerSample - bitsPerSample : 0;

            // scrub out byte stuffing
            final SmallHuffmanCodec.ByteVector b = new SmallHuffmanCodec.ByteVector();
            for (int i = 0; i < toDecode.length; i++) {
                b.add(toDecode[i]);
                if (toDecode[i] == (byte) 0xff && i + 1 < toDecode.length && toDecode[i + 1] == 0) {
                    i++;
                }
            }
            toDecode = b.toByteArray();

            final SmallHuffmanCodec.BitBuffer bb = new SmallHuffmanCodec.BitBuffer(toDecode);
            final SmallHuffmanCodec huffman = new SmallHuffmanCodec();
            final SmallHuffmanCodec.HuffmanCodecOptions huffmanOptions =
                    new SmallHuffmanCodec.HuffmanCodecOptions();
            if (bitsPerSample < 2 || bitsPerSample > 16) {
                throw new UnsupportedTiffFormatException("Lossless JPEG: " + bitsPerSample +
                        " bits/sample is not supported (only 2..16 values are allowed)");
            }
            huffmanOptions.bitsPerSample = bitsPerSample;
            huffmanOptions.maxBytes = numberOfPixels;

            int nextSampleIndex = 0;
            while (nextSampleIndex < numberOfPixels) {
                for (int i = 0; i < nComponents; i++) {
                    if (huffmanTables != null) {
                        huffmanOptions.table = huffmanTables[dcTable[i]];
                    }
                    if (huffmanOptions.table == null) {
                        throw new UnsupportedTiffFormatException(
                                "Cannot decode lossless JPEG: arithmetic coding not supported");
                    }
                    int v = huffman.getSample(bb, huffmanOptions);
                    if (nextSampleIndex == 0) {
                        v += 1 << (bitsPerSample - 1);
                    }
                    v <<= scalingShift;

                    // apply predictor to the sample
                    int predictor = startPredictor;
                    if (nextSampleIndex < width * bytesPerSample) {
                        predictor = 1;
                    } else if ((nextSampleIndex % (width * bytesPerSample)) == 0) {
                        predictor = 2;
                    }

                    final int componentOffset = i * numberOfPixels;

                    final int indexA = nextSampleIndex - bytesPerSample + componentOffset;
                    final int indexB = nextSampleIndex - width * bytesPerSample +
                            componentOffset;
                    final int indexC = nextSampleIndex - (width + 1) * bytesPerSample +
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

                    if (nextSampleIndex > 0) {
                        int pred = switch (predictor) {
                            case 1 -> sampleA;
                            case 2 -> sampleB;
                            case 3 -> sampleC;
                            case 4 -> sampleA + sampleB - sampleC;
                            // in SCIFIO code, here was sampleA + sampleB + sampleC: it was a bug
                            case 5 -> sampleA + ((sampleB - sampleC) / 2);
                            case 6 -> sampleB + ((sampleA - sampleC) / 2);
                            case 7 -> (sampleA + sampleB) / 2;
                            default -> 0;
                        };
                        v += pred;
                    }

                    final int offset = componentOffset + nextSampleIndex;
                    JArrays.setBytes8InBigEndianOrder(buf, offset, v, bytesPerSample);
//                        Bytes.unpack(v, buf, offset, bytesPerSample, false);
                }
                nextSampleIndex += bytesPerSample;
            }
        }

        private void fixByteOrder() {
            if (options.isLittleEndian() && bytesPerSample > 1) {
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
        }

        private void interleave() {
            if (options.isInterleaved() && nComponents > 1) {
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
        }
    }
}
