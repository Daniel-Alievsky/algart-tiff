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
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffIO;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPhotometric;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * This class is an analog of SCIFIO Codec interface, simplifying to use for TIFF encoding inside this library
 */
public interface TiffCodec {
    interface Timing {
        void setTiming(boolean timing);

        void resetTiming();

        long timeMain();

        long timeBridge();

        long timeAdditional();
    }

    @FunctionalInterface
    interface Customizer {
        /**
         * Adjusts the options, usually before reading or writing the tile.
         *
         * @param options the options to be customized.
         */
        void customize(Options options);
    }

    /**
     * Options for compressing and decompressing data.
     */
    class Options implements Cloneable {
        private int width = 0;
        private int height = 0;
        private TiffSampleType sampleType = null;
        private int samplesPerPixel = 0;
        private int normalizedBitsPerSample = 0;
        private Integer rawEqualBitsPerSample = null;
        private boolean planarSeparated = false;
        private boolean tiled = false;
        private boolean signed = false;
        private boolean floatingPoint = false;
        private boolean littleEndian = false;
        private boolean interleaved = false;
        private int maxUnpackedSizeInBytes = 0;
        private TagCompression compression = null;
        private TagPhotometric photometric = null;
        // - the codec may need this information for "high-level" formats
        // (when TagCompression.isLowLevelBitsProcessing returns false);
        // in the current version, JPEGCodec and JPEGOptions use it
        private int[] yCbCrSubsampling = null;
        private Double compressionQuality = null;
        private Double losslessCompressionLevel = null;
        private TiffIFD ifd = null;
        // - used only if other information is not enough
        private TiffIO io = null;
        // - used only while reading if other information is not enough
        private TiffIO.CodecReport report = null;

        public Options() {
        }

        public int getWidth() {
            return width;
        }

        public Options setWidth(int width) {
            if (width < 0) {
                throw new IllegalArgumentException("Negative width = " + width);
            }
            this.width = width;
            return this;
        }

        public int getHeight() {
            return height;
        }

        public Options setHeight(int height) {
            if (height < 0) {
                throw new IllegalArgumentException("Negative height = " + height);
            }
            this.height = height;
            return this;
        }

        public Options setSizes(int width, int height) {
            return setWidth(width).setHeight(height);
        }

        public TiffSampleType getSampleType() {
            return sampleType;
        }

        public Options setSampleType(TiffSampleType sampleType) {
            this.sampleType = sampleType;
            return this;
        }

        /**
         * Returns number of samples per pixel. Note that it will be always 1 in
         * {@link #isPlanarSeparated() planar-separated} mode, even for 3-channels images.
         * In a usual (chunked) mode, this number is equal to the number of channels.
         *
         * @return number of samples per pixel.
         */
        public int getSamplesPerPixel() {
            return samplesPerPixel;
        }

        public Options setSamplesPerPixel(int samplesPerPixel) {
            if (samplesPerPixel < 0) {
                throw new IllegalArgumentException("Negative samplesPerPixel = " + samplesPerPixel);
            }
            this.samplesPerPixel = samplesPerPixel;
            return this;
        }

        public int getNormalizedBitsPerSample() {
            return normalizedBitsPerSample;
        }

        public Options setNormalizedBitsPerSample(int normalizedBitsPerSample) {
            if (normalizedBitsPerSample < 0) {
                throw new IllegalArgumentException("Negative normalizedBitsPerSample = " + normalizedBitsPerSample);
            }
            this.normalizedBitsPerSample = normalizedBitsPerSample;
            return this;
        }

        public OptionalInt optRawEqualBitsPerSample() {
            return rawEqualBitsPerSample == null ? OptionalInt.empty() : OptionalInt.of(rawEqualBitsPerSample);
        }

        public Integer getRawEqualBitsPerSample() {
            return rawEqualBitsPerSample;
        }

        public Options setRawEqualBitsPerSample(Integer rawEqualBitsPerSample) {
            this.rawEqualBitsPerSample = rawEqualBitsPerSample;
            return this;
        }

        public boolean isPlanarSeparated() {
            return planarSeparated;
        }

        public Options setPlanarSeparated(boolean planarSeparated) {
            this.planarSeparated = planarSeparated;
            return this;
        }

        public boolean isTiled() {
            return tiled;
        }

        public Options setTiled(boolean tiled) {
            this.tiled = tiled;
            return this;
        }

        public boolean isSigned() {
            return signed;
        }

        public Options setSigned(boolean signed) {
            this.signed = signed;
            return this;
        }

        public boolean isFloatingPoint() {
            return floatingPoint;
        }

        public Options setFloatingPoint(boolean floatingPoint) {
            this.floatingPoint = floatingPoint;
            return this;
        }

        public boolean isLittleEndian() {
            return littleEndian;
        }

        public Options setLittleEndian(boolean littleEndian) {
            this.littleEndian = littleEndian;
            return this;
        }

        public ByteOrder getByteOrder() {
            return isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        }

        public Options setByteOrder(ByteOrder byteOrder) {
            Objects.requireNonNull(byteOrder, "Null byteOrder");
            this.littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN;
            return this;
        }

        public boolean isInterleaved() {
            return interleaved;
        }

        public Options setInterleaved(boolean interleaved) {
            this.interleaved = interleaved;
            return this;
        }

        public int getMaxUnpackedSizeInBytes() {
            return maxUnpackedSizeInBytes;
        }

        /**
         * Sets the maximal size of resulting decoded data. Used for reading only.
         *
         * <p>This limit is used in some codecs such as {@link LZWCodec}, {@link PackBitsCodec},
         * {@link ThunderScanCodec}, and {@link ZstdCodec}.
         * This limit <b>may be</b> greater than required for completely decoding data,
         * but must not be less.</p>
         *
         * <p>Note that this value should contain the maximal number of bytes in the fully decoded tile
         * <i>after</i> possible unpacking of bits when the number of bits per sample is not a multiple of 8,
         * if this case is not supported directly in normal {@link TiffTile#getDecodedData() decoded data}
         * (i.e., if this is not {@link TiffSampleType#BIT}).
         * For example, for the {@link TagCompression#THUNDER_SCAN} format (4 bits/pixel),
         * this value should be the number of unpacked 8-bit pixels, <b>not</b> the summary size
         * of raw unpacked 4-bit samples.
         * In the case when the number of bits per sample is not a multiple of 8, some other codecs (such as {@link LZWCodec})
         * may use only part of the requested memory, but this is usually not a problem.</p>
         *
         * <p>More exactly, this value should be set to {@link TiffTile#getSizeInBytesInsideTIFF()}:
         * the unpacked tile size concerning possible alignment of each line when the number of bits
         * per sample is not a multiple of 8, even in unpacked data ({@link TiffSampleType#BIT}).</p>
         *
         * @param maxUnpackedSizeInBytes new maximal data size to be uncompressed.
         * @return a reference to this object.
         */
        public Options setMaxUnpackedSizeInBytes(int maxUnpackedSizeInBytes) {
            if (maxUnpackedSizeInBytes < 0) {
                throw new IllegalArgumentException("Negative maxUnpackedSizeInBytes = " + maxUnpackedSizeInBytes);
            }
            this.maxUnpackedSizeInBytes = maxUnpackedSizeInBytes;
            return this;
        }

        public TagCompression getCompression() {
            return compression;
        }

        public int compressionCode(int defaultValue) {
            return compression == null ? defaultValue : compression.code();
        }

        public Options setCompression(TagCompression compression) {
            this.compression = compression;
            return this;
        }

        public TagPhotometric getPhotometric() {
            return photometric;
        }

        public Options setPhotometric(TagPhotometric photometric) {
            this.photometric = photometric;
            return this;
        }

        public int[] getYCbCrSubsampling() {
            return yCbCrSubsampling == null ? null : yCbCrSubsampling.clone();
        }

        public Options setYCbCrSubsampling(int[] yCbCrSubsampling) {
            this.yCbCrSubsampling = yCbCrSubsampling == null ? null : yCbCrSubsampling.clone();
            return this;
        }

        public boolean hasCompressionQuality() {
            return compressionQuality != null;
        }

        public Double getCompressionQuality() {
            return compressionQuality;
        }

        public Options setCompressionQuality(Double compressionQuality) {
            this.compressionQuality = compressionQuality;
            return this;
        }

        public double compressionQuality(double defaultValue) {
            if (compressionQuality == null) {
                return defaultValue;
            }
            return compressionQuality;
        }

        public double compressionQuality() {
            if (compressionQuality == null) {
                throw new IllegalStateException("Quality level is required, but is not set");
            }
            return compressionQuality;
        }

        public Double getLosslessCompressionLevel() {
            return losslessCompressionLevel;
        }

        public Options setLosslessCompressionLevel(Double losslessCompressionLevel) {
            this.losslessCompressionLevel = losslessCompressionLevel;
            return this;
        }

        public TiffIFD getIfd() {
            return ifd;
        }

        public Options setIfd(TiffIFD ifd) {
            this.ifd = ifd;
            return this;
        }

        public TiffIO getIo() {
            return io;
        }

        public Options setIo(TiffIO io) {
            this.io = io;
            return this;
        }

        public TiffIO.CodecReport getReport() {
            return report;
        }

        public Options setReport(TiffIO.CodecReport report) {
            this.report = report;
            return this;
        }

        public Options setMainOptions(TiffTile tile) {
            this.setSizes(tile.getSizeX(), tile.getSizeY());
            this.setNormalizedBitsPerSample(tile.normalizedBitDepth());
            OptionalInt rawEqualBitDepth = tile.rawEqualBitDepth();
            this.setRawEqualBitsPerSample(rawEqualBitDepth.isPresent() ? rawEqualBitDepth.getAsInt() : null);
            this.setSampleType(tile.sampleType());
            this.setSamplesPerPixel(tile.samplesPerPixel());
            this.setPlanarSeparated(tile.isPlanarSeparated());
            this.setTiled(tile.tilingMode().isTileGrid());
            this.setSigned(tile.sampleType().isSigned());
            this.setFloatingPoint(tile.sampleType().isFloatingPoint());
            this.setByteOrder(tile.byteOrder());
            this.setInterleaved(true);
            // - Value "true" is necessary for most codecs that work with high-level classes (like JPEG or JPEG-2000)
            // and need to be instructed to interleave results while reading.
            // (For comparison, LZW or DECOMPRESSED work with data "as-is" and suppose
            // that data are interleaved according to TIFF format specification).
            // For JPEG, TagCompression overrides this value to false because it works faster in this mode.
            tile.optCompressionOrNoneForMissing().ifPresent(this::setCompression);
            // - default value can be not-null
            tile.optPhotometric().ifPresent(this::setPhotometric);
            // - default value can be not-null
            this.setYCbCrSubsampling(tile.getYCbCrSubsampling());
            this.setIfd(tile.ifd());
            return this;
        }

        public Options setTo(Options options) {
            Objects.requireNonNull(options, "Null options");
            this.width = options.width;
            this.height = options.height;
            this.sampleType = options.sampleType;
            this.samplesPerPixel = options.samplesPerPixel;
            this.normalizedBitsPerSample = options.normalizedBitsPerSample;
            this.rawEqualBitsPerSample = options.rawEqualBitsPerSample;
            this.planarSeparated = options.planarSeparated;
            this.tiled = options.tiled;
            this.signed = options.signed;
            this.floatingPoint = options.floatingPoint;
            this.littleEndian = options.littleEndian;
            this.interleaved = options.interleaved;
            this.maxUnpackedSizeInBytes = options.maxUnpackedSizeInBytes;
            this.compressionQuality = options.compressionQuality;
            this.losslessCompressionLevel = options.losslessCompressionLevel;
            this.compression = options.compression;
            this.photometric = options.photometric;
            this.yCbCrSubsampling = options.yCbCrSubsampling == null ? null : options.yCbCrSubsampling.clone();
            this.ifd = options.ifd;
            this.io = options.io;
            // but without codec report
            return this;
        }

        public final Object toSCIFIOStyleOptions(String scifioStyleClassName) {
            Objects.requireNonNull(scifioStyleClassName, "Null scifioStyleClassName");
            final Class<?> c;
            try {
                c = Class.forName(scifioStyleClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("No class " + scifioStyleClassName, e);
            }
            return toSCIFIOStyleOptions(c);
        }

        public <T> T toSCIFIOStyleOptions(Class<T> scifioStyleClass) {
            Objects.requireNonNull(scifioStyleClass, "Null scifioStyleClass");
            final T result;
            try {
                result = scifioStyleClass.getConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException e) {
                throw new IllegalArgumentException("Class " + scifioStyleClass +
                        " cannot be created with empty constructor", e);
            }
            setField(scifioStyleClass, result, "width", width);
            setField(scifioStyleClass, result, "height", height);
            setField(scifioStyleClass, result, "channels", samplesPerPixel);
            setField(scifioStyleClass, result, "bitsPerSample", normalizedBitsPerSample);
            setField(scifioStyleClass, result, "littleEndian", littleEndian);
            setField(scifioStyleClass, result, "interleaved", interleaved);
            setField(scifioStyleClass, result, "signed", signed);
            setField(scifioStyleClass, result, "maxBytes", maxUnpackedSizeInBytes);
            if (compressionQuality != null) {
                setField(scifioStyleClass, result, "quality", compressionQuality);
            }
            return result;
        }

        public void setToSCIFIOStyleOptions(Object scifioStyleOptions) {
            Objects.requireNonNull(scifioStyleOptions, "Null scifioStyleOptions");
            setWidth(getField(scifioStyleOptions, Integer.class, "width"));
            setHeight(getField(scifioStyleOptions, Integer.class, "height"));
            setSamplesPerPixel(getField(scifioStyleOptions, Integer.class, "channels"));
            setNormalizedBitsPerSample(getField(scifioStyleOptions, Integer.class, "bitsPerSample"));
            setSigned(getField(scifioStyleOptions, Boolean.class, "signed"));
            setFloatingPoint(false);
            setLittleEndian(getField(scifioStyleOptions, Boolean.class, "littleEndian"));
            setInterleaved(getField(scifioStyleOptions, Boolean.class, "interleaved"));
            setMaxUnpackedSizeInBytes(getField(scifioStyleOptions, Integer.class, "maxBytes"));
            setCompressionQuality(getField(scifioStyleOptions, Double.class, "quality"));
        }

        @Override
        public String toString() {
            return "Options: " +
                    "width=" + width +
                    ", height=" + height +
                    ", sampleType=" + sampleType +
                    ", samplesPerPixel=" + samplesPerPixel +
                    ", normalizedBitsPerSample=" + normalizedBitsPerSample +
                    ", rawEqualBitsPerSample=" + rawEqualBitsPerSample +
                    ", planarSeparated=" + planarSeparated +
                    ", tiled=" + tiled +
                    ", signed=" + signed +
                    ", floatingPoint=" + floatingPoint +
                    ", littleEndian=" + littleEndian +
                    ", interleaved=" + interleaved +
                    ", maxUnpackedSizeInBytes=" + maxUnpackedSizeInBytes +
                    ", compression=" + compression +
                    ", photometric=" + photometric +
                    ", yCbCrSubsampling=" + Arrays.toString(yCbCrSubsampling) +
                    ", compressionQuality=" + compressionQuality +
                    ", losslessCompressionLevel=" + losslessCompressionLevel;
            // - not include ifd and io: they are not values, but references to complex objects
        }

        public Options clone() {
            final Options result;
            try {
                result = (Options) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
            result.setTo(this);
            // - performs the necessary cloning of mutable fields like Java arrays
            return result;
        }

        static void setField(Class<?> oldStyleClass, Object result, String fieldName, Object value) {
            try {
                oldStyleClass.getField(fieldName).set(result, value);
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new IllegalArgumentException("Cannot set field \"" + fieldName + "\" in the class " +
                        oldStyleClass.getName() + ": " + e);
            }
        }

        static <T> T getField(Object options, Class<T> fieldType, String fieldName) {
            final Class<?> oldStyleClass = options.getClass();
            Object result;
            try {
                result = oldStyleClass.getField(fieldName).get(options);
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new IllegalArgumentException("Cannot get field \"" + fieldName + "\" in the class " +
                        oldStyleClass.getName() + ": " + e);
            }
            if (result == null) {
                throw new IllegalArgumentException("The field \"" + fieldName + "\" in the class " +
                        oldStyleClass.getName() + " contains null");
            }
            try {
                return fieldType.cast(result);
            } catch (ClassCastException e) {
                throw new IllegalArgumentException("Invalid type of the field \"" + fieldName + "\" in the class " +
                        oldStyleClass.getName() + ": " + result.getClass() + " instead of required " + fieldType);
            }
        }
    }

    /**
     * Compresses a block of data.
     *
     * @param data    The data to be compressed.
     * @param options Options to be used during compression, if appropriate.
     * @return The compressed data.
     * @throws TiffException        if input is not a compressed data block of the appropriate type.
     * @throws NullPointerException if one of the arguments is {@code null}.
     */
    byte[] compress(byte[] data, Options options) throws TiffException;

    /**
     * Decompresses a block of data.
     *
     * @param data    the data to be decompressed
     * @param options Options to be used during decompression.
     * @return the decompressed data.
     * @throws TiffException        if data is not valid.
     * @throws NullPointerException if one of the arguments is {@code null}.
     */
    byte[] decompress(byte[] data, Options options) throws TiffException;
}
