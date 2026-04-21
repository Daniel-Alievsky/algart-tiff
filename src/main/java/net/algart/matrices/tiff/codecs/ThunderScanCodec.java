package net.algart.matrices.tiff.codecs;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;

import java.util.Objects;

public class ThunderScanCodec implements TiffCodec {
    private static final int THUNDER_DATA = 0x3f;
    private static final int THUNDER_CODE = 0xc0;
    private static final int THUNDER_RUN = 0x00;
    private static final int THUNDER_2BITDELTAS = 0x40;
    private static final int THUNDER_3BITDELTAS = 0x80;
    private static final int THUNDER_RAW = 0xc0;
    private static final int DELTA2_SKIP = 2;
    private static final int DELTA3_SKIP = 4;
    private static final int[] TWOBIT_DELTAS = {0, 1, 0, -1};
    private static final int[] THREEBIT_DELTAS = {0, 1, 2, 3, 0, -3, -2, -1};

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        throw new UnsupportedTiffFormatException("ThunderScan compression is not supported");
    }

    /**
     * The Options parameter should have the following fields set:
     * {@link Options#getMaxSizeInBytes()}.
     */
    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final int maxPixels = options.getMaxSizeInBytes();
        // - maxSizeInBytes relates to unpacked data consisting of whole bytes
        final byte[] result = new byte[(maxPixels + 1) >>> 1];
        // - zero-filled by Java

//         System.out.println("!!! " + options.getPhotometric());
        unpackThunderScan(result, data, data.length, maxPixels);
        // - note: this format is classified as "low level" in TagCompression.isLowLevelBitsProcessing,
        // so we do not need to check the White-is-zero photometric interpretation
        // and should return 4-bit (not 8-bit) unpacked data
        return result;
    }

    // See libtiff/tif_thunder.c
    public static void unpackThunderScan(byte[] dest, byte[] src, int packedLength, int maxPixels) {
        int srcPos = 0;
        int destPos = 0;
        int lastPixel = 0;
        int npixels = 0;
        while (srcPos < packedLength && npixels < maxPixels) {
            final int n = src[srcPos++] & 0xFF;
            switch (n & THUNDER_CODE) {
                case THUNDER_RUN: {
                    int count = n & THUNDER_DATA;
                    if ((npixels & 1) != 0) {
                        dest[destPos] |= (byte) (lastPixel & 0xF);
                        lastPixel = dest[destPos++] & 0xFF;
                        npixels++;
                        count--;
                    } else {
                        lastPixel |= lastPixel << 4;
                    }
                    npixels += count;
                    if (npixels < maxPixels) {
                        for (; count > 0; count -= 2) {
                            dest[destPos++] = (byte) lastPixel;
                        }
                    }
                    if (count == -1) {
                        dest[--destPos] &= (byte) 0xF0;
                    }
                    lastPixel &= 0xF;
                    break;
                }
                case THUNDER_2BITDELTAS: {
                    int delta;
                    delta = (n >> 4) & 3;
                    if (delta != DELTA2_SKIP)
                        destPos = setPixel(dest, destPos, lastPixel += TWOBIT_DELTAS[delta], npixels++);

                    delta = (n >> 2) & 3;
                    if (delta != DELTA2_SKIP && npixels < maxPixels)
                        destPos = setPixel(dest, destPos, lastPixel += TWOBIT_DELTAS[delta], npixels++);

                    delta = n & 3;
                    if (delta != DELTA2_SKIP && npixels < maxPixels)
                        destPos = setPixel(dest, destPos, lastPixel += TWOBIT_DELTAS[delta], npixels++);
                    break;
                }
                case THUNDER_3BITDELTAS: {
                    int delta;
                    delta = (n >> 3) & 7;
                    if (delta != DELTA3_SKIP)
                        destPos = setPixel(dest, destPos, lastPixel += THREEBIT_DELTAS[delta], npixels++);

                    delta = n & 7;
                    if (delta != DELTA3_SKIP && npixels < maxPixels)
                        destPos = setPixel(dest, destPos, lastPixel += THREEBIT_DELTAS[delta], npixels++);
                    break;
                }
                case THUNDER_RAW: {
                    destPos = setPixel(dest, destPos, n, npixels++);
                    lastPixel = n & 0xF;
                    break;
                }
            }
        }
    }

    private static int setPixel(byte[] dest, int destPos, int value, int npixels) {
        final int lastPixel = value & 0xF;
        if ((npixels & 1) != 0) {
            dest[destPos] |= (byte) lastPixel;
            return destPos + 1;
        } else {
            dest[destPos] = (byte) (lastPixel << 4);
            return destPos;
        }
    }
}