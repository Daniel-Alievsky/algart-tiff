package net.algart.matrices.tiff.codecs;

import net.algart.arrays.Arrays;
import net.algart.arrays.JArrays;
import net.algart.arrays.PArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.tags.TagCompression;

import java.util.Objects;

public class LogLuvCodec implements TiffCodec {

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        throw new UnsupportedTiffFormatException("SGI LogL / LogLuv compression is not supported");
    }

    /**
     * The Options parameter should have the following fields set:
     * {@link Options#getMaxSizeInBytes()}.
     */
    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        // - zero-filled by Java

//         System.out.println("!!! " + options.getPhotometric());
        final TiffSampleType sampleType = options.getSampleType();
        final int bytesPerSample = sampleType.bytesPerSample().orElseThrow(() ->
                new UnsupportedTiffFormatException("Sample type " + sampleType +
                        " is not supported for LogL/LogLuv compression"));
        float[] floats = unpackLogLFloats(data, options);
        final int resultLength = floats.length * bytesPerSample;
        final PArray array = Arrays.asPrecision(PArray.as(floats), sampleType.elementType());
        byte[] result = new byte[resultLength];
        Arrays.toBytes(result, array, options.getByteOrder());
        return result;
    }

    private static float[] unpackLogLFloats(byte[] data, Options options) throws UnsupportedTiffFormatException {
        final int dimX = options.getWidth();
        final int dimY = options.getHeight();
        final int samplesPerPixel = options.getSamplesPerPixel();
        float[] result = new float[dimX * dimY * samplesPerPixel];
        final TagCompression compression = options.getCompression();
        switch (compression) {
            case SGI_LOG -> {
                switch (samplesPerPixel) {
                    case 1 -> {
                        unpackLogL(result, data, dimX, dimY);
                        return result;
                    }
                    case 3 -> {
                        unpackLogLuv(result, data, dimX, dimY);
                        return result;
                    }
                }
            }
            case SGI_LOG24 ->  {
                if (samplesPerPixel == 3) {
                    unpackLogLuv24(result, data, dimX, dimY);
                    return result;
                }
            }
        }
        throw new UnsupportedTiffFormatException("Compression \"" + compression +
                "\" for " + samplesPerPixel + " channels is not supported for LogL/LogLuv compression");
    }

    public static void unpackLogL(float[] dest, byte[] src, int dimX, int dimY) {
    }
    public static void unpackLogLuv(float[] dest, byte[] src, int dimX, int dimY) {
    }
    public static void unpackLogLuv24(float[] dest, byte[] src, int dimX, int dimY) {
    }

}