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
import java.util.Map;

class SCIFIOBridge {
    private static final Class<?> SCIFIO_CLASS = findScifioClass();

    static boolean isScifioInstalled() {
        return SCIFIO_CLASS != null;
    }

    static Class<?> codecOptionsClass() {
        return scifioClass("io.scif.codec.CodecOptions");
    }

    static Class<?> scifioIFDClass() {
        return scifioClass("io.scif.formats.tiff.IFD");
    }

    static Object createScifioFromContext(Context context) {
        if (context == null) {
            return null;
        }
        if (SCIFIO_CLASS == null) {
            throw new UnsupportedOperationException("SCIFIO library is not installed");
        }
        try {
            return SCIFIO_CLASS.getConstructor(Context.class).newInstance(context);
        } catch (InstantiationException | IllegalAccessException |
                 InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO library cannot be called, probably due to version mismatch", e);
        }
    }

    static Context getDefaultScifioContext() {
        if (SCIFIO_CLASS == null) {
            throw new UnsupportedOperationException("Cannot create SCIFIO context: SCIFIO library is not installed");
        }
        try {
            final Object scifio = SCIFIO_CLASS.getConstructor().newInstance();
            return (Context) SCIFIO_CLASS.getMethod("getContext").invoke(scifio);
        } catch (InstantiationException | IllegalAccessException |
                 InvocationTargetException | NoSuchMethodException | ClassCastException e) {
            throw new IllegalStateException("SCIFIO library cannot be called, probably due to version mismatch", e);
        }
    }

    static Map<Integer, Object> createIFD(Class<?> ifdClass) {
        final Class<?> logServiceClass = scifioClass("org.scijava.log.LogService");
        final Object result;
        try {
            result = ifdClass.getConstructor(logServiceClass).newInstance(new Object[]{null});
        } catch (InstantiationException | IllegalAccessException |
                 InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO IFD object cannot be created, " +
                    "probably due to version mismatch", e);
        }
        if (!(result instanceof Map)) {
            throw new IllegalStateException("SCIFIO IFD object is not Map and cannot be used, " +
                    "probably due to version mismatch: it is " + result);
        }
        //noinspection unchecked
        return (Map<Integer, Object>) result;
    }

    static Object createTiffCompression(int compressionCode) throws InvocationTargetException {
        final Class<?> tiffCompressionClass;
        try {
            tiffCompressionClass = Class.forName("io.scif.formats.tiff.TiffCompression");
        } catch (ClassNotFoundException e1) {
            throw new UnsupportedOperationException("Operation is not allowed: " +
                    "SCIFIO TiffCompression class is not found " +
                    "(SCIFIO library is probably not installed correctly or has incompatible version)", e1);
        }
        try {
            return tiffCompressionClass.getMethod("get", int.class).invoke(null, compressionCode);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO TiffCompression object cannot be created, " +
                    "probably due to version mismatch", e);
        }
    }

    static Object getCompressionCodecOptions(Object tiffCompression, Object ifd, Object options)
            throws InvocationTargetException {
        final Class<?> scifioIFDClass = scifioIFDClass();
        final Class<?> codecOptionsClass = codecOptionsClass();

        // public CodecOptions getCompressionCodecOptions(final IFD ifd, CodecOptions opt) throws FormatException
        final Object result;
        try {
            result = tiffCompression.getClass()
                    .getMethod("getCompressionCodecOptions", scifioIFDClass, codecOptionsClass)
                    .invoke(tiffCompression, ifd, options);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO TiffCompression.getCompressionCodecOptions method " +
                    "cannot be called, " +
                    "probably due to version mismatch", e);
        }
        return result;
    }

    static byte[] callDecompress(Object scifio, Object tiffCompression, byte[] input, Object options)
            throws InvocationTargetException {
        final Object codecService = scifioCodecService(scifio);
        final Class<?> codecServiceClass = scifioClass("io.scif.codec.CodecService");
        final Class<?> codecOptionsClass = codecOptionsClass();

        // public byte[] decompress(CodecService codecService, byte[] input, CodecOptions options)
        // throws FormatException
        final Object result;
        try {
            result = tiffCompression.getClass()
                    .getMethod("decompress", codecServiceClass, byte[].class, codecOptionsClass)
                    .invoke(tiffCompression, codecService, input, options);
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

    static byte[] callCompress(Object scifio, Object tiffCompression, byte[] input, Object options)
            throws InvocationTargetException {
        final Object codecService = scifioCodecService(scifio);
        final Class<?> codecServiceClass = scifioClass("io.scif.codec.CodecService");
        final Class<?> codecOptionsClass = codecOptionsClass();

        // public byte[] compress(CodecService codecService, byte[] input, CodecOptions options) throws FormatException
        final Object result;
        try {
            result = tiffCompression.getClass()
                    .getMethod("compress", codecServiceClass, byte[].class, codecOptionsClass)
                    .invoke(tiffCompression, codecService, input, options);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO TiffCompression.compress method cannot be called, " +
                    "probably due to version mismatch", e);
        }
        if (result != null && !(result instanceof byte[])) {
            throw new IllegalStateException("SCIFIO TiffCompression.compress method returns invalid result " +
                    result + ", probably due to version mismatch");
        }
        return (byte[]) result;
    }

    private static Class<?> scifioClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SCIFIO class \"" + className + "\" cannot be found, " +
                    "probably due to version mismatch", e);
        }
    }

    private static Object scifioCodecService(Object scifio) {
        try {
            return scifio.getClass().getMethod("codec").invoke(scifio);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("SCIFIO codec method cannot be called, " +
                    "probably due to version mismatch", e);
        }
    }

    private static Class<?> findScifioClass() {
        try {
            return Class.forName("io.scif.SCIFIO");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
