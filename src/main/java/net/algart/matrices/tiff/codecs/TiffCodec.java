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
            return this;
        }

        public final Object toOldStyleOptions(String oldStyleClassName) {
            Objects.requireNonNull(oldStyleClassName, "Null oldStyleClassName");
            final Class<?> c;
            try {
                c = Class.forName(oldStyleClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("No class " + oldStyleClassName, e);
            }
            return toOldStyleOptions(c);
        }

        public <T> T toOldStyleOptions(Class<T> oldStyleClass) {
            Objects.requireNonNull(oldStyleClass, "Null oldStyleClass");
            final T result;
            try {
                result = oldStyleClass.getConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException e) {
                throw new IllegalArgumentException("Class " + oldStyleClass +
                        " cannot be created with empty constructor", e);
            }
            setField(oldStyleClass, result, "width", width);
            setField(oldStyleClass, result, "height", height);
            setField(oldStyleClass, result, "channels", numberOfChannels);
            setField(oldStyleClass, result, "bitsPerSample", bitsPerSample);
            setField(oldStyleClass, result, "littleEndian", littleEndian);
            setField(oldStyleClass, result, "interleaved", interleaved);
            setField(oldStyleClass, result, "maxBytes", maxSizeInBytes);
            if (quality != null) {
                setField(oldStyleClass, result, "quality", quality);
            }
            return result;
        }

        public void setToOldStyleOptions(Object oldStyleOptions) {
            Objects.requireNonNull(oldStyleOptions, "Null oldStyleOptions");
            setWidth(getField(oldStyleOptions, Integer.class, "width"));
            setHeight(getField(oldStyleOptions, Integer.class, "height"));
            setNumberOfChannels(getField(oldStyleOptions, Integer.class, "channels"));
            setBitsPerSample(getField(oldStyleOptions, Integer.class, "bitsPerSample"));
            setSigned(false);
            setFloatingPoint(false);
            setLittleEndian(getField(oldStyleOptions, Boolean.class, "littleEndian"));
            setInterleaved(getField(oldStyleOptions, Boolean.class, "interleaved"));
            setMaxSizeInBytes(getField(oldStyleOptions, Integer.class, "maxBytes"));
            setQuality(getField(oldStyleOptions, Double.class, "quality"));
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
            // - performs necessary cloning of mutable fields like Java arrays
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
