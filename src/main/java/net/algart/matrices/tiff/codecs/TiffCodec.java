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
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.awt.image.ColorModel;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * This class is an analog of SCIFIO Codec interface, simplifying to use for TIFF encoding inside this library
 */
public interface TiffCodec {

    /**
     * Options for compressing and decompressing data.
     */
    class Options implements Cloneable {
        int width = 0;
        int height = 0;
        int channels = 0;
        int bitsPerSample = 0;
        boolean littleEndian = false;
        public boolean interleaved = false;
        boolean signed = false;
        int maxBytes = 0;
        boolean lossless = true;
        ColorModel colorModel = null;
        public double quality = 0.0;

        // -- Constructors --

        public Options() {
        }

        public Options setTo(Options options) {
            Objects.requireNonNull(options, "Null options");
            this.width = options.width;
            this.height = options.height;
            this.channels = options.channels;
            this.bitsPerSample = options.bitsPerSample;
            this.littleEndian = options.littleEndian;
            this.interleaved = options.interleaved;
            this.signed = options.signed;
            this.maxBytes = options.maxBytes;
            this.lossless = options.lossless;
            this.colorModel = options.colorModel;
            this.quality = options.quality;
            return this;
        }

        public Options setWidth(int width) {
            this.width = width;
            return this;
        }

        public Options setHeight(int height) {
            this.height = height;
            return this;
        }

        public Options setChannels(int channels) {
            this.channels = channels;
            return this;
        }

        public Options setBitsPerSample(int bitsPerSample) {
            this.bitsPerSample = bitsPerSample;
            return this;
        }

        public Options setLittleEndian(boolean littleEndian) {
            this.littleEndian = littleEndian;
            return this;
        }

        public Options setInterleaved(boolean interleaved) {
            this.interleaved = interleaved;
            return this;
        }

        public Options setSigned(boolean signed) {
            this.signed = signed;
            return this;
        }

        public Options setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        public Options setLossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        public Options setColorModel(ColorModel colorModel) {
            this.colorModel = colorModel;
            return this;
        }

        public Options setQuality(double quality) {
            this.quality = quality;
            return this;
        }

        public Object toOldStyleOptions(String oldStyleClassName) {
            final Class<?> c;
            try {
                c = Class.forName(oldStyleClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("No class " + oldStyleClassName, e);
            }
            return toOldStyleOptions(c);
        }

        public <T> T toOldStyleOptions(Class<T> oldStyleClass) {
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
            setField(oldStyleClass, result, "channels", channels);
            setField(oldStyleClass, result, "bitsPerSample", bitsPerSample);
            setField(oldStyleClass, result, "littleEndian", littleEndian);
            setField(oldStyleClass, result, "interleaved", interleaved);
            setField(oldStyleClass, result, "signed", signed);
            setField(oldStyleClass, result, "maxBytes", maxBytes);
            setField(oldStyleClass, result, "lossless", lossless);
            setField(oldStyleClass, result, "colorModel", colorModel);
            setField(oldStyleClass, result, "quality", quality);
            return result;
        }

        @Override
        public String toString() {
            return "Options: " +
                    "width=" + width +
                    ", height=" + height +
                    ", channels=" + channels +
                    ", bitsPerSample=" + bitsPerSample +
                    ", littleEndian=" + littleEndian +
                    ", interleaved=" + interleaved +
                    ", signed=" + signed +
                    ", maxBytes=" + maxBytes +
                    ", lossless=" + lossless +
                    ", colorModel=" + colorModel +
                    ", quality=" + quality;
        }

        public Options clone() {
            try {
                return (Options) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        private <T> void setField(Class<T> oldStyleClass, Object result, String fieldName, Object value) {
            try {
                oldStyleClass.getField(fieldName).set(result, value);
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new IllegalArgumentException("Cannot set field \"" + fieldName + "\" in the class " +
                        oldStyleClass.getName() + ": " + e);
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

    /**
     * Decompresses data from the given DataHandle.
     *
     * @param in      The stream from which to read compressed data.
     * @param options Options to be used during decompression.
     * @return The decompressed data.
     * @throws TiffException If data is not valid compressed data for this
     *                       decompressor.
     */
    byte[] decompress(DataHandle<Location> in, Options options) throws IOException;
}
