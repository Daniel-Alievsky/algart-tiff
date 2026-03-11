package net.algart.matrices.tiff.codecs;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;

import java.util.Objects;

public class ThunderScanCodec implements TiffCodec {
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

    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");

        byte[] result = new byte[options.maxSizeInBytes];
        unpackThunderScan(result, data, data.length);
        return result;
    }

    public static void unpackThunderScan(byte[] dest, byte[] src, int packedLength) {
        int srcPos = 0;
        int destPos = 0;
        int lastPixel = 0;
        int maxPixels = dest.length;

        while (srcPos < packedLength && destPos < maxPixels) {
            int n = src[srcPos++] & 0xFF;
            int code = n & THUNDER_CODE;
            int data = n & 0x3F;

            switch (code) {
                case THUNDER_RUN -> {
                    // - repeat the last pixel 'data' times
                    for (int i = 0; i < data && destPos < maxPixels; i++) {
                        dest[destPos++] = scale4bitTo8bit(lastPixel);
                    }
                }
                case THUNDER_2BITDELTAS -> {
                    // - 3 deltas per 2 bits
                    int d1 = (n >> 4) & 3;
                    if (d1 != DELTA2_SKIP) {
                        lastPixel = (lastPixel + TWOBIT_DELTAS[d1]) & 0xF;
                        dest[destPos++] = scale4bitTo8bit(lastPixel);
                    }
                    int d2 = (n >> 2) & 3;
                    if (d2 != DELTA2_SKIP && destPos < maxPixels) {
                        lastPixel = (lastPixel + TWOBIT_DELTAS[d2]) & 0xF;
                        dest[destPos++] = scale4bitTo8bit(lastPixel);
                    }
                    int d3 = n & 3;
                    if (d3 != DELTA2_SKIP && destPos < maxPixels) {
                        lastPixel = (lastPixel + TWOBIT_DELTAS[d3]) & 0xF;
                        dest[destPos++] = scale4bitTo8bit(lastPixel);
                    }
                }
                case THUNDER_3BITDELTAS -> {
                    // - 2 deltas per 3 bits
                    int d3_1 = (n >> 3) & 7;
                    if (d3_1 != DELTA3_SKIP) {
                        lastPixel = (lastPixel + THREEBIT_DELTAS[d3_1]) & 0xF;
                        dest[destPos++] = scale4bitTo8bit(lastPixel);
                    }
                    int d3_2 = n & 7;
                    if (d3_2 != DELTA3_SKIP && destPos < maxPixels) {
                        lastPixel = (lastPixel + THREEBIT_DELTAS[d3_2]) & 0xF;
                        dest[destPos++] = scale4bitTo8bit(lastPixel);
                    }
                }
                case THUNDER_RAW -> {
                    // direct 4-bit value
                    lastPixel = data & 0xF;
                    dest[destPos++] = scale4bitTo8bit(lastPixel);
                }
            }
        }
    }

    private static byte scale4bitTo8bit(int value4Bit) {
        return (byte) (value4Bit * 17);
    }
}