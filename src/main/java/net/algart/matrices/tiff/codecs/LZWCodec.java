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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIO;

import java.util.Arrays;
import java.util.Objects;

public class LZWCodec implements TiffCodec {
    // Note: compared to SCIFIO, support for 5.0-style LZW is added. - Daniel Alievsky.
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
    public static class LZWCodecReport extends TiffIO.CodecReport {
        private boolean tiff50Style = false;

        public boolean isTiff50Style() {
            return tiff50Style;
        }

        public LZWCodecReport setTiff50Style(boolean tiff50Style) {
            this.tiff50Style = tiff50Style;
            return this;
        }

        @Override
        public String toString() {
            return "LZW decoder report:" +
                    (tiff50Style ?
                            "%n    TIFF 5.0-style LZW compression detected (very old format)".formatted() :
                            "");
        }
    }

    /**
     * Size of hash table. Must be greater 3837 (the number of possible codes).
     * Bigger size reduces the number of rehashing steps -- at the expense of
     * initialization time.
     */
    private static final int HASH_SIZE = 7349;

    /**
     * Rehashing step. HASH_SIZE and HASH_STEP should be coprime.
     */
    private static final int HASH_STEP = 257;

    private static final int CLEAR_CODE = 256;

    private static final int EOI_CODE = 257;

    private static final int FIRST_CODE = 258;

    /**
     * Masks for writing bits in compressor.
     */
    private static final int[] COMPR_MASKS = {0xff, 0x7f, 0x3f, 0x1f, 0x0f, 0x07,
            0x03, 0x01};

    /**
     * Masks for reading bits in decompressor.
     */
    private static final int[] DECOMPR_MASKS = {0x00, 0x01, 0x03, 0x07, 0x0f,
            0x1f, 0x3f, 0x7f};

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (data.length == 0) {
            return data;
        }

        // Output buffer (see class comments for justification of size).
        final long bufferSize = ((long) data.length * 141) / 100 + 3;
        if (bufferSize > Integer.MAX_VALUE) {
            throw new TiffException("Output buffer is greater than 2 GB");
        }
        final byte[] output = new byte[(int) bufferSize];

        // Current size of output buffer (and position to write next byte).
        int outSize = 0;
        // The output always starts with CLEAR code
        output[outSize++] = (byte) (CLEAR_CODE >> 1);
        // Last incomplete byte to be written to output (bits shifted to the
        // right).
        // Always contains at least 1 bit, and may contain 8 bits.
        int currOutByte = CLEAR_CODE & 0x01;
        // Number of unused bits in currOutByte (from 0 to 7).
        int freeBits = 7;

        // Hash table.
        // Keys in the table are pairs (code,byte) and values are codes.
        // Pair (code,byte) is represented as ( (code<<8) | byte ).
        // Unused table entries have key=-1.
        final int[] htKeys = new int[HASH_SIZE];
        final int[] htValues = new int[HASH_SIZE];
        // Initialize hash table: mark all entries as unused
        Arrays.fill(htKeys, -1);

        // Next code to be used by compressor.
        int nextCode = FIRST_CODE;
        // Number of bits to be used to output code. Ranges from 9 to 12.
        int currCodeLength = 9;

        // Names of these variables are taken from TIFF specification.
        // The first byte of input is handled specially.
        int tiffK = data[0] & 0xff;
        int tiffOmega = tiffK;

        // Main loop.
        for (int currInPos = 1; currInPos < data.length; currInPos++) {
            tiffK = data[currInPos] & 0xff;
            final int hashKey = (tiffOmega << 8) | tiffK;
            int hashCode = hashKey % HASH_SIZE;
            do {
                if (htKeys[hashCode] == hashKey) {
                    // Omega+K in the table
                    tiffOmega = htValues[hashCode];
                    break;
                } else if (htKeys[hashCode] < 0) {
                    // Omega+K not in the table
                    // 1) add new entry to hash table
                    htKeys[hashCode] = hashKey;
                    htValues[hashCode] = nextCode++;
                    // 2) output last code
                    int shift = currCodeLength - freeBits;
                    output[outSize++] = (byte) ((currOutByte << freeBits) |
                            (tiffOmega >> shift));
                    if (shift > 8) {
                        output[outSize++] = (byte) (tiffOmega >> (shift - 8));
                        shift -= 8;
                    }
                    freeBits = 8 - shift;
                    currOutByte = tiffOmega & COMPR_MASKS[freeBits];
                    // 3) omega = K
                    tiffOmega = tiffK;
                    break;
                } else {
                    // we have to rehash
                    hashCode = (hashCode + HASH_STEP) % HASH_SIZE;
                }
            }
            while (true);

            switch (nextCode) {
                case 512:
                    currCodeLength = 10;
                    break;
                case 1024:
                    currCodeLength = 11;
                    break;
                case 2048:
                    currCodeLength = 12;
                    break;
                case 4096: // write CLEAR code and reinitialize hash table
                    int shift = currCodeLength - freeBits;
                    output[outSize++] = (byte) ((currOutByte << freeBits) |
                            (CLEAR_CODE >> shift));
                    if (shift > 8) {
                        output[outSize++] = (byte) (CLEAR_CODE >> (shift - 8));
                        shift -= 8;
                    }
                    freeBits = 8 - shift;
                    currOutByte = CLEAR_CODE & COMPR_MASKS[freeBits];
                    Arrays.fill(htKeys, -1);
                    nextCode = FIRST_CODE;
                    currCodeLength = 9;
                    break;
            }
        }

        // End of input:
        // 1) write code from tiff_Omega
        {
            int shift = currCodeLength - freeBits;
            output[outSize++] = (byte) ((currOutByte << freeBits) |
                    (tiffOmega >> shift));
            if (shift > 8) {
                output[outSize++] = (byte) (tiffOmega >> (shift - 8));
                shift -= 8;
            }
            freeBits = 8 - shift;
            currOutByte = tiffOmega & COMPR_MASKS[freeBits];
        }
        // 2) write END_OF_INFORMATION code
        // -- we write the last incomplete byte here as well
        // !!! We have to increase the length of code if needed !!!
        currCodeLength = switch (nextCode) {
            case 511 -> 10;
            case 1023 -> 11;
            case 2047 -> 12;
            default -> currCodeLength;
        };

        {
            int shift = currCodeLength - freeBits;
            output[outSize++] = (byte) ((currOutByte << freeBits) |
                    (EOI_CODE >> shift));
            if (shift > 8) {
                output[outSize++] = (byte) (EOI_CODE >> (shift - 8));
                shift -= 8;
            }
            freeBits = 8 - shift;
            currOutByte = EOI_CODE & COMPR_MASKS[freeBits];
            output[outSize++] = (byte) (currOutByte << freeBits);
        }

        final byte[] result = new byte[outSize];
        System.arraycopy(output, 0, result, 0, outSize);
        return result;
    }

    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (data.length < 2) {
            throw new TiffException("Too short LZW stream (" + data.length + " bytes)");
        }
        final boolean oldStyle = data[0] == 0x00 && (data[1] & 1) != 0;
        // TIFF 5.0-style LZW: codes are packed LSB-first.
        if (oldStyle) {
            options.setReport(new LZWCodecReport().setTiff50Style(true));
        }

        // Output buffer
        final byte[] out = new byte[options.getMaxUnpackedSizeInBytes()];
        // Position in the output buffer to write the next byte to
        int outPosition = 0;

        // Table mapping codes to strings.
        // Its structure is based on the fact that a string for a code has form:
        // (string for another code) + (new byte).
        // Thus, at index 'code': the first array contains 'another code', the second array
        // contains 'new byte', and the third array contains the length of the string.
        // The length is needed to make retrieving the string faster.
        final int[] anotherCodes = new int[4096 + (oldStyle ? 1024 : 0)];
        final byte[] newBytes = new byte[4096 + (oldStyle ? 1024 : 0)];
        final int[] lengths = new int[4096 + (oldStyle ? 1024 : 0)];
        // libTiff adds 1024 "for compatibility", this value seems to work fine
        // (without this, quad-lzw.tif file cannot be unpacked well)
        // We need to initialize only firt 256 entries in the table
        for (int i = 0; i < 256; i++) {
            newBytes[i] = (byte) i;
            lengths[i] = 1;
        }

        // Length of the code to be read from input
        int currCodeLength = 9;
        // Next code to be added to the table
        int nextCode = FIRST_CODE;

        // Variables to handle reading bit stream:
        // Byte from 'input[curr_in_pos-1]' -- only 'bits_read' bits on the
        // right
        // are non-zero
        int currRead = 0;
        // Number of bits in 'curr_read' that were not consumed yet
        int bitsRead = 0;

        // Current code being processed by decompressor.
        int currCode;
        // Previous code processed by decompressor.
        int oldCode = 0; // without initializer, Java reports error later

        int inPosition = 0;
        try {
            do {
                // read next code
                if (oldStyle) {
                    // TIFF 5.0-style LZW: codes are packed LSB-first.
                    while (bitsRead < currCodeLength) {
                        if (inPosition >= data.length) {
                            return out;
                        }
                        int b = data[inPosition++];
                        currRead |= (b & 0xff) << bitsRead;
                        bitsRead += 8;
                    }

                    currCode = currRead & ((1 << currCodeLength) - 1);
                    currRead >>>= currCodeLength;
                    bitsRead -= currCodeLength;
                } else {
                    // Normal TIFF LZW: codes are packed MSB-first.
                    int bitsLeft = currCodeLength - bitsRead;
                    if (bitsLeft > 8) {
                        currRead = (currRead << 8) | (data[inPosition++] & 0xff);
                        bitsLeft -= 8;
                    }
                    bitsRead = 8 - bitsLeft;

                    if (inPosition >= data.length) {
                        return out;
                    }
                    final int nextByte = data[inPosition++] & 0xff;
                    currCode = (currRead << bitsLeft) | (nextByte >> bitsRead);
                    currRead = nextByte & DECOMPR_MASKS[bitsRead];
                }
                if (currCode == EOI_CODE) {
                    break;
                }
                if (currCode == CLEAR_CODE) {
                    // initialize table -- nothing to do
                    nextCode = FIRST_CODE;
                    currCodeLength = 9;
                    // read next code
                    {
                        if (oldStyle) {
                            while (bitsRead < currCodeLength) {
                                if (inPosition >= data.length) {
                                    return out;
                                }
                                currRead |= (data[inPosition++] & 0xff) << bitsRead;
                                bitsRead += 8;
                            }

                            currCode = currRead & ((1 << currCodeLength) - 1);
                            currRead >>>= currCodeLength;
                            bitsRead -= currCodeLength;
                        } else {
                            int bitsLeft = currCodeLength - bitsRead;
                            if (bitsLeft > 8) {
                                if (inPosition >= data.length) {
                                    return out;
                                }
                                currRead = (currRead << 8) | (data[inPosition++] & 0xff);
                                bitsLeft -= 8;
                            }
                            bitsRead = 8 - bitsLeft;

                            if (inPosition >= data.length) {
                                return out;
                            }
                            final int nextByte = data[inPosition++] & 0xff;
                            currCode = (currRead << bitsLeft) | (nextByte >> bitsRead);
                            currRead = nextByte & DECOMPR_MASKS[bitsRead];
                        }
                    }
                    if (currCode == EOI_CODE) {
                        break;
                    }
                    // write string[curr_code] to output
                    // -- but here we are sure that string consists of a single byte
                    if (outPosition >= out.length) {
                        break;
                    }
                    out[outPosition++] = newBytes[currCode];
                    oldCode = currCode;
                } else if (currCode < nextCode) {
                    // Code is already in the table
                    // 1) Write string[curr_code] to output
                    final int outLength = lengths[currCode];
                    int i = outPosition + outLength;
                    int tablePos = currCode;
                    if (i > out.length) {
                        break;
                    }
                    while (i > outPosition) {
                        out[--i] = newBytes[tablePos];
                        tablePos = anotherCodes[tablePos];
                    }
                    outPosition += outLength;
                    // 2) Add string[old_code]+firstByte(string[curr_code]) to
                    // the table
                    if (nextCode >= anotherCodes.length) {
                        break;
                    }
                    anotherCodes[nextCode] = oldCode;
                    newBytes[nextCode] = out[i];
                    lengths[nextCode] = lengths[oldCode] + 1;
                    oldCode = currCode;
                    nextCode++;
                } else {
                    // Special case: code is not in the table
                    // 1) Write string[old_code] to output
                    final int outLength = lengths[oldCode];
                    int i = outPosition + outLength;
                    int tablePos = oldCode;
                    if (i > out.length) {
                        break;
                    }
                    while (i > outPosition) {
                        out[--i] = newBytes[tablePos];
                        tablePos = anotherCodes[tablePos];
                    }
                    outPosition += outLength;
                    // 2) Write firstByte(string[old_code]) to output
                    if (outPosition >= out.length) {
                        break;
                    }
                    out[outPosition++] = out[i];
                    // 3) Add string[old_code]+firstByte(string[old_code]) to
                    // the table
                    anotherCodes[nextCode] = oldCode;
                    newBytes[nextCode] = out[i];
                    lengths[nextCode] = outLength + 1;
                    oldCode = currCode;
                    nextCode++;
                }
                // Increase the length of code if needed
                if (oldStyle) {
                    currCodeLength = switch (nextCode) {
                        case 512 -> 10;
                        case 1024 -> 11;
                        case 2048 -> 12;
                        default -> currCodeLength;
                    };
                } else {
                    currCodeLength = switch (nextCode) {
                        case 511 -> 10;
                        case 1023 -> 11;
                        case 2047 -> 12;
                        default -> currCodeLength;
                    };
                }
            }
            while (outPosition < out.length && inPosition < data.length);
        } catch (final ArrayIndexOutOfBoundsException e) {
            // - just in case (should not occur)
            throw new TiffException("Invalid LZW data");
        }
        return out;
    }
}
