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

package net.algart.matrices.tiff.awt;

import java.util.Objects;

/**
 * Simple parser of JPEG stream markers.
 */
public class JPEGMarkerInspector {
    private boolean hasSOI = false;
    private boolean hasSOF = false;
    private boolean hasDQT = false;
    private boolean hasDHT = false;
    private boolean hasSOS = false;
    private int sofMarker = -1;
    private int sofNumberOfChannels = 0;
    private boolean tooLargeNumberOfChannels = false;
    private int[] sofComponentId = null;
    private int[] sofDQTIndexes = null;

    private JPEGMarkerInspector(byte[] data) {
        Objects.requireNonNull(data, "Null data");
        if (data.length < 2) {
            return;
        }
        int p = 0;
        if (data[0] == (byte) 0xFF && data[1] == (byte) JPEGDecoding.SOI_BYTE) {
            hasSOI = true;
            p = 2;
        }
        while (p < data.length - 1) {
            if (data[p] != (byte) 0xFF) {
                p++;
                continue;
                // strange stream: let's search for the next 0xFF
            }
            while (p + 1 < data.length && data[p + 1] == (byte) 0xFF) {
                // skipping several 0xFF
                p++;
            }
            if (p + 1 >= data.length) {
                return;
            }
            final int marker = data[p + 1] & 0xFF;
            switch (marker) {
                case 0 -> {
                    // stuffed byte
                    // (not too important for OldJPEGCodec: it should not occur in JPEGInterchangeFormat)
                    p += 2;
                    continue;
                }
                case JPEGDecoding.DQT_BYTE -> {
                    hasDQT = true;
                }
                case JPEGDecoding.DHT_BYTE -> {
                    hasDHT = true;
                }
                case JPEGDecoding.SOI_BYTE -> {
                    // strange stream: several SOI
                    p += 2;
                    continue;
                }
                case JPEGDecoding.SOS_BYTE -> {
                    hasSOS = true;
                    // entropy data start here: we don't need the following markers
                    // (entropy data are not included into JPEGInterchangeFormat)
                    return;
                }
                case JPEGDecoding.EOI_BYTE -> {
                    return;
                }
                case JPEGDecoding.SOF0_BASELINE,
                     0xC1, 0xC2, 0xC3,
                     // not 0xC4 (DHT)
                     0xC5, 0xC6, 0xC7,
                     // not 0xC8 (JPG)
                     0xC9, 0xCA, 0xCB,
                     // not 0xCC (DAC)
                     0xCD, 0xCE, 0xCF -> {
                    if (p < data.length - 10) {
                        // - in other case, this SOF is invalid
                        final int n = data[p + 9] & 0xFF;
                        sofNumberOfChannels = n;
                        if (n > 16) {
                            tooLargeNumberOfChannels = true;
                            return;
                        }
                        if (p + 10 < data.length - 3 * n) {
                            // - in other case, this SOF is invalid
                            hasSOF = true;
                            sofMarker = marker;
                            sofDQTIndexes = new int[sofNumberOfChannels];
                            sofComponentId = new int[sofNumberOfChannels];
                            for (int i = 0; i < sofNumberOfChannels; i++) {
                                sofComponentId[i] = data[p + 10 + 3 * i] & 0xFF;
                                sofDQTIndexes[i] = data[p + 12 + 3 * i] & 0xF;
                                // - cannot be >15
                            }
                        }
                    }
                }
            }
            boolean hasLength = marker != JPEGDecoding.TEM_BYTE &&
                    !(marker >= JPEGDecoding.RST_FIRST && marker <= JPEGDecoding.RST_LAST);
            if (!hasLength) {
                p += 2;
            } else if (p < data.length - 3) {
                final int segmentLength = ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
                p += 2 + segmentLength;
            } else {
                return;
            }
        }
    }

    public static JPEGMarkerInspector of(byte[] data) {
        return new JPEGMarkerInspector(data);
    }

    public boolean hasSOI() {
        return hasSOI;
    }

    public boolean hasSOF() {
        return hasSOF;
    }

    public boolean hasDQT() {
        return hasDQT;
    }

    public boolean hasDHT() {
        return hasDHT;
    }

    public boolean hasSOS() {
        return hasSOS;
    }

    public int sofMarker() {
        return sofMarker;
    }

    public int sofNumberOfChannels() {
        return sofNumberOfChannels;
    }

    public boolean tooLargeNumberOfChannels() {
        return tooLargeNumberOfChannels;
    }

    public int sofComponentId(int index) {
        if (sofComponentId == null) {
            throw new IllegalStateException("SOF marker was not parsed");
        }
        return sofComponentId[index];
    }

    public int sofDQTIndexes(int index) {
        if (sofDQTIndexes == null) {
            throw new IllegalStateException("SOF marker was not parsed");
        }
        return sofDQTIndexes[index];
    }

    public boolean isAbbreviatedStream() {
        return hasSOF && !(hasDQT && hasDHT);
    }
}
