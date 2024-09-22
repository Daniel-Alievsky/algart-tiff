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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * This class is an analog of SCIFIO Codec interface, simplifying to use for TIFF encoding inside this library
 */
public interface TiffCodec {
    interface Timing {
        void setTiming(boolean timing);

        void clearTiming();

        long timeMain();

        long timeBridge();

        long timeAdditional();
    }

    /**
     * Options for compressing and decompressing data.
     */
    class Options implements Cloneable {
        int width = 0;
        int height = 0;
        int numberOfChannels = 0;
        int bitsPerSample = 0;
        boolean signed = false;
        boolean floatingPoint = false;
        boolean littleEndian = false;
        boolean interleaved = false;
        int maxSizeInBytes = 0;
        private Double quality = null;
        private TiffIFD ifd = null;
        // - used only if other information is not enough

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

        public int getNumberOfChannels() {
            return numberOfChannels;
        }

        public Options setNumberOfChannels(int numberOfChannels) {
            if (numberOfChannels < 0) {
                throw new IllegalArgumentException("Negative numberOfChannels = " + numberOfChannels);
            }
            this.numberOfChannels = numberOfChannels;
            return this;
        }

        public int getBitsPerSample() {
            return bitsPerSample;
        }

        public Options setBitsPerSample(int bitsPerSample) {
            if (bitsPerSample < 0) {
                throw new IllegalArgumentException("Negative bitsPerSample = " + bitsPerSample);
            }
            this.bitsPerSample = bitsPerSample;
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

        public int getMaxSizeInBytes() {
            return maxSizeInBytes;
        }

        /**
         * Sets the size of result unpacked data. Used for reading only.
         *
         * @param maxSizeInBytes new maximal data size to be uncompressed.
         * @return a reference to this object.
         */
        public Options setMaxSizeInBytes(int maxSizeInBytes) {
            if (maxSizeInBytes < 0) {
                throw new IllegalArgumentException("Negative maxSizeInBytes = " + maxSizeInBytes);
            }
            this.maxSizeInBytes = maxSizeInBytes;
            return this;
        }

        public boolean hasQuality() {
            return quality != null;
        }

        public Double getQuality() {
            return quality;
        }

        public Options setQuality(Double quality) {
            this.quality = quality;
            return this;
        }

        public double quality() {
            if (quality == null) {
                throw new IllegalStateException("Quality is required, but is not set");
            }
            return quality;
        }

        public TiffIFD getIfd() {
            return ifd;
        }

        public Options setIfd(TiffIFD ifd) {
            this.ifd = ifd;
            return this;
        }

        public Options setTo(Options options) {
            Objects.requireNonNull(options, "Null options");
            setWidth(options.width);
            setHeight(options.height);
            setNumberOfChannels(options.numberOfChannels);
            setBitsPerSample(options.bitsPerSample);
            setSigned(options.signed);
            setFloatingPoint(options.floatingPoint);
            setLittleEndian(options.littleEndian);
            setInterleaved(options.interleaved);
            setMaxSizeInBytes(options.maxSizeInBytes);
            setQuality(options.quality);
            setIfd(options.ifd);
            return this;
        }

        public final Object toScifioStyleOptions(String scifioStyleClassName) {
            Objects.requireNonNull(scifioStyleClassName, "Null scifioStyleClassName");
            final Class<?> c;
            try {
                c = Class.forName(scifioStyleClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("No class " + scifioStyleClassName, e);
            }
            return toScifioStyleOptions(c);
        }

        public <T> T toScifioStyleOptions(Class<T> scifioStyleClass) {
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
            setField(scifioStyleClass, result, "channels", numberOfChannels);
            setField(scifioStyleClass, result, "bitsPerSample", bitsPerSample);
            setField(scifioStyleClass, result, "littleEndian", littleEndian);
            setField(scifioStyleClass, result, "interleaved", interleaved);
            setField(scifioStyleClass, result, "maxBytes", maxSizeInBytes);
            if (quality != null) {
                setField(scifioStyleClass, result, "quality", quality);
            }
            return result;
        }

        public void setToScifioStyleOptions(Object scifioStyleOptions) {
            Objects.requireNonNull(scifioStyleOptions, "Null scifioStyleOptions");
            setWidth(getField(scifioStyleOptions, Integer.class, "width"));
            setHeight(getField(scifioStyleOptions, Integer.class, "height"));
            setNumberOfChannels(getField(scifioStyleOptions, Integer.class, "channels"));
            setBitsPerSample(getField(scifioStyleOptions, Integer.class, "bitsPerSample"));
            setSigned(false);
            setFloatingPoint(false);
            setLittleEndian(getField(scifioStyleOptions, Boolean.class, "littleEndian"));
            setInterleaved(getField(scifioStyleOptions, Boolean.class, "interleaved"));
            setMaxSizeInBytes(getField(scifioStyleOptions, Integer.class, "maxBytes"));
            setQuality(getField(scifioStyleOptions, Double.class, "quality"));
        }

        @Override
        public String toString() {
            return "Options: " +
                    "width=" + width +
                    ", height=" + height +
                    ", numberOfChannels=" + numberOfChannels +
                    ", bitsPerSample=" + bitsPerSample +
                    ", signed=" + signed +
                    ", floatingPoint=" + floatingPoint +
                    ", littleEndian=" + littleEndian +
                    ", interleaved=" + interleaved +
                    ", maxSizeInBytes=" + maxSizeInBytes +
                    ", quality=" + quality;
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
     * @throws TiffException If input is not a compressed data block of the
     *                       appropriate type.
     */
    byte[] compress(byte[] data, Options options) throws TiffException;

    /**
     * Decompresses a block of data.
     *
     * @param data    the data to be decompressed
     * @param options Options to be used during decompression.
     * @return the decompressed data.
     * @throws TiffException If data is not valid.
     */
    byte[] decompress(byte[] data, Options options) throws TiffException;
}
