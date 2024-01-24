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

package net.algart.matrices.tiff;

import org.scijava.Context;

import java.lang.reflect.InvocationTargetException;

class SCIFIOBridge {
    static Object createScifio(Context context) {
        if (context == null) {
            return null;
        }
        final Class<?> scifioClass;
        try {
            scifioClass = Class.forName("io.scif.SCIFIO");
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Operation is not allowed: SCIFIO library is not installed", e);
        }
        try {
            return scifioClass.getConstructor(Context.class).newInstance(context);
        } catch (InstantiationException | IllegalAccessException |
                 InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO library cannot be called, probably due to version mismatch", e);
        }
    }

    static Object getTiffCompression(int compressionCode)
            throws InvocationTargetException {
        Class<?> tiffCompressionClass;
        try {
            tiffCompressionClass = Class.forName("io.scif.formats.tiff.TiffCompression");
        } catch (ClassNotFoundException e1) {
            throw new UnsupportedOperationException("Operation is not allowed: " +
                    "SCIFIO TiffCompression class is not found " +
                    "(SCIFIO library is probably not installed correctly or has incompatible version)", e1);
        }
        try {
            return tiffCompressionClass.getMethod( "get", int.class).invoke(null, compressionCode);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO TiffCompression class cannot be created, " +
                    "probably due to version mismatch", e);
        }
    }

    static byte[] callDecompress(Object scifio, Object tiffCompression, byte[] input, Object options)
            throws InvocationTargetException {
        Object codec = scifioCodec(scifio);
        Class<?> codecServiceClass = codecServiceClass();
        Class<?> codecOptionsClass = codecOptionsClass();

        Object result;
        try {
            result = tiffCompression.getClass()
                    .getMethod("decompress", codecServiceClass, byte[].class, codecOptionsClass)
                    .invoke(tiffCompression, codec, input, options);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO TiffCompression.decompress method cannot be called, " +
                    "probably due to version mismatch", e);
        }
        if (result != null && !(result instanceof byte[])) {
            throw new IllegalStateException("SCIFIO TiffCompression.decompress method returns invalid result " +
                    result + ", probably due to version mismatch");
        }
        return (byte[]) result;
    }

    static Class<?> codecOptionsClass() {
        try {
            return Class.forName("io.scif.codec.CodecOptions");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SCIFIO CodecOptions class cannot be found, " +
                    "probably due to version mismatch", e);
        }
    }

    private static Class<?> codecServiceClass() {
        try {
            return Class.forName("io.scif.codec.CodecService");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SCIFIO CodecService class cannot be found, " +
                    "probably due to version mismatch", e);
        }
    }

    private static Object scifioCodec(Object scifio) {
        try {
            return scifio.getClass().getMethod("codec").invoke(scifio);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO codec method cannot be called, " +
                    "probably due to version mismatch", e);
        }
    }

}
