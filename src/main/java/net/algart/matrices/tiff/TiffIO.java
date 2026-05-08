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

package net.algart.matrices.tiff;

import net.algart.arrays.JArrays;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagRational;
import net.algart.matrices.tiff.tags.TagTypes;
import net.algart.matrices.tiff.tags.Tags;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.FileHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.io.Closeable;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public sealed abstract class TiffIO implements Closeable permits TiffReader, TiffWriter {
    /**
     * Subclasses of this class can be used for storing additional information about encoding or decoding tiles,
     * for example, in some specific TIFF codecs.
     */
    public static class CodecReport {
    }

    public static final int FILE_USUAL_MAGIC_NUMBER = 0x2a;
    public static final int FILE_BIG_TIFF_MAGIC_NUMBER = 0x2b;
    public static final int FILE_PREFIX_LITTLE_ENDIAN = 0x49;
    public static final int FILE_PREFIX_BIG_ENDIAN = 0x4d;

    public static final boolean BUILT_IN_TIMING = getBooleanProperty("net.algart.matrices.tiff.timing");

    static final System.Logger LOG = System.getLogger(TiffIO.class.getName());
    static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private static final boolean OPTIMIZE_READING_IFD_ARRAYS = true;
    // - Note: this optimization allows speeding up reading a large array of offsets.
    // If we use simple FileHandle for reading files (based on RandomAccessFile),
    // acceleration is up to 100 and more times:
    // on my computer, 23220 int32 values were loaded in 0.2 ms instead of 570 ms.
    // Since scijava-common 2.95.1, we use optimized ReadBufferDataHandle for reading a file;
    // now acceleration for 23220 int32 values is 0.2 ms instead of 0.4 ms.

    private static final boolean AVOID_LONG8_FOR_ACTUAL_32_BITS = true;
    // - If was necessary for some old programs (like Aperio Image Viewer), which
    // did not understand LONG8 values for some popular tags like image sizes.
    // In any case, real BigTIFF files usually store most tags in standard LONG type (32 bits), not in LONG8.

    final DataHandle<?> stream;
    private final Path filePath;
    private final Object fileLock = new Object();

    volatile Object context = null;
    volatile boolean bigTiff = false;
    volatile long fileOffsetOfLastIFDOffset = -1;

    volatile Object scifio = null;
    private volatile CodecReport lastCodecReport = null;

    public TiffIO(DataHandle<?> stream) {
        this(stream, null);
    }

    TiffIO(DataHandle<?> stream, Path filePath) {
        this.stream = Objects.requireNonNull(stream, "Null data handle (input/output stream)");
        this.filePath = filePath;
    }

    public Object getContext() {
        return context;
    }

    public void setContext(Object context) {
        this.scifio = null;
        this.context = context;
    }

    /**
     * Returns whether we are writing BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Returns the object used to synchronize access to the underlying file {@link #stream() stream}.
     * Clients must synchronize on this object when performing manual IO operations to ensure thread safety.
     *
     * @return the lock object for synchronizing access to the stream.
     */
    public Object fileLock() {
        return fileLock;
    }

    /**
     * Returns an {@link Optional} containing the path to the TIFF file if this object was created by a constructor
     * with a {@link Path} argument.
     * If the path is unknown (for example, if the object was created from a {@link DataHandle}),
     * it returns {@link Optional#empty()}.
     *
     * @return the path to the TIFF file, or an empty {@code Optional} if there is no associated file path.
     */
    public Optional<Path> path() {
        return Optional.ofNullable(filePath);
    }

    /**
     * Returns the input/output stream for operation with this TIFF file.
     *
     * @return the {@link DataHandle} for this TIFF file; never {@code null}.
     * @see #fileLock()
     */
    public DataHandle<?> stream() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return stream;
        }
    }

    public String streamName() {
        return streamName("");
    }

    public String streamName(String prefix) {
        Objects.requireNonNull(prefix, "Null prefix");
        if (filePath != null) {
            return prefix + filePath;
        }
        Location location = stream.get();
        if (location == null) {
            return "";
        }
        URI uri = location.getURI();
        if (uri == null) {
            return "";
        }
        return prefix + uri;
    }

    /**
     * Returns the length of the file, in bytes.
     * This method never throws an exception; in case of any error (for example, "access is denied"),
     * it returns 0.
     *
     * @return the length of this file.
     */
    public long fileLength() {
        try {
            return stream.length();
        } catch (IOException e) {
            // - very improbable
            // (example: this is a subdirectory, and length() throws "FileNotFoundExceptoin ... (Access is denied)";
            // it is much better just to return something
            return 0;
        }
    }

    /**
     * Returns position in the file of the first IFD offset:
     * 8 for {@link TiffReader#isBigTiff() BigTIFF}, 4 for a usual TIFF.
     *
     * @return position in the file of the first IFD offset.
     */
    public long fileOffsetOfFirstIFDOffset() {
        return bigTiff ? 8L : 4L;
    }

    public TiffReader newReader(TiffOpenMode openMode) throws IOException {
        return new TiffReader(stream, openMode, false);
    }

    void setLastCodecReport(CodecReport lastCodecReport) {
        this.lastCodecReport = lastCodecReport;
    }

    public CodecReport lastCodecReport() {
        return lastCodecReport;
    }

    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            stream.close();
        }
    }

    public static Object newSCIFIOContext() {
        return SCIFIOBridge.getDefaultScifioContext();
    }

    @SuppressWarnings("UnusedReturnValue")
    public static long copyFile(DataHandle<?> inputStream, DataHandle<?> outputStream) throws IOException {
        Objects.requireNonNull(inputStream, "Null input stream");
        Objects.requireNonNull(outputStream, "Null output stream");
        inputStream.seek(0);
        outputStream.seek(0);
        final long inputLength = inputStream.length();
        final long result = copyData(inputStream, outputStream, false, inputLength);
        outputStream.setLength(outputStream.offset());
        if (result != inputLength) {
            throw new EOFException("Copied only " + result + " bytes from all " + inputLength + " bytes");
            // - should not occur in the normal situation
        }
        return result;
    }

    static long copyData(DataHandle<?> in, DataHandle<?> out) throws IOException {
        return copyData(in, out, true, in.length());
    }

    // A simplified clone of the function DataHandles.copy without the problem with invalid generic types
    static long copyData(DataHandle<?> in, DataHandle<?> out, boolean fromZeroOffset, long length)
            throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("Negative length: " + length);
        }
        if (fromZeroOffset) {
            in.seek(0);
        }
        final byte[] buffer = new byte[256 * 1024];
        long result = 0;
        while (result < length) {
            final int len = (int) Math.min(length - result, buffer.length);
            int actuallyRead = in.read(buffer, 0, len);
            if (actuallyRead <= 0) {
                break; // EOF
            }
            out.write(buffer, 0, actuallyRead);
            result += actuallyRead;
        }
        return result;
    }

    String spacedStreamName() {
        return streamName(" ");
    }

    Object scifio() {
        Object scifio = this.scifio;
        if (scifio == null) {
            this.scifio = scifio = SCIFIOBridge.createScifioFromContext(context);
        }
        return scifio;
    }

    Object requireScifio(TiffIFD ifd) throws UnsupportedTiffFormatException {
        Object scifio = scifio();
        if (scifio == null) {
            // - in other words, this.context is not set
            throw new UnsupportedTiffFormatException("Reading with TIFF compression " +
                    TagCompression.toPrettyString(ifd.optCompressionCode(TiffIFD.COMPRESSION_NONE)) +
                    " is not supported without external codecs");
        }
        return scifio;
    }

    TiffIFD.TiffEntry readIFDEntry(
            DataHandle<?> ifdStream,
            long entryOffset,
            long ifdStreamOffsetInTiffFile,
            long tiffFileLength) throws IOException {
        return readIFDEntry(
                ifdStream, bigTiff, entryOffset, ifdStreamOffsetInTiffFile, tiffFileLength, this::streamName);
    }

    static TiffIFD.TiffEntry readIFDEntry(
            DataHandle<?> ifdStream,
            boolean bigTiff,
            long entryOffset,
            long ifdStreamOffsetInTiffFile,
            long tiffFileLength,
            Supplier<String> fileNameSupplier) throws IOException {
        Objects.requireNonNull(ifdStream, "Null ifdStream");
        ifdStream.seek(entryOffset);
        final int entryTag = ifdStream.readUnsignedShort();
        final int entryType = ifdStream.readUnsignedShort();

        final long valueCount = bigTiff ? ifdStream.readLong() : ((long) ifdStream.readInt()) & 0xFFFFFFFFL;
        if (valueCount < 0 || valueCount > Integer.MAX_VALUE) {
            throw new TiffException("Invalid TIFF: very large number of IFD values in array " +
                    (valueCount < 0 ? " >= 2^63" : valueCount + " >= 2^31") + " is not supported");
        }
        final int bytesPerElement = TagTypes.sizeOfType(entryType);
        // - will be zero for an unknown type; in this case we will set valueOffset=in.offset() below
        final long valueLength = valueCount * (long) bytesPerElement;
        final boolean embeddedInEntry = TiffIFD.TiffEntry.isDataEmbeddedInEntry(valueLength, bigTiff);
        final long valueOffset = embeddedInEntry ?
                ifdStreamOffsetInTiffFile + ifdStream.offset() :
                readOffset(ifdStream, bigTiff, ifdStreamOffsetInTiffFile, tiffFileLength, fileNameSupplier);
        // - position in the file will be different depending on embeddedInEntry,
        // but it is not a problem: we will not use this position
        if (valueOffset < 0) {
            throw new TiffException("Invalid TIFF: negative offset of IFD values " + valueOffset);
        }
        if (valueOffset > tiffFileLength - valueLength) {
            throw new TiffException("Invalid TIFF: offset of IFD values " + valueOffset +
                    " + total lengths of values " + valueLength + " = " + valueCount + "*" + bytesPerElement +
                    " is outside the file length " + tiffFileLength);
        }
        final var result = new TiffIFD.TiffEntry(entryTag, entryType, (int) valueCount, valueOffset, bigTiff);
        assert result.valueLength() == valueLength;
        assert result.isDataEmbeddedInEntry() == embeddedInEntry;
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "Reading IFD entry: %s - %s", result, Tags.prettyName(result.tag())));
        return result;
    }

    static Object readIFDValueAtEntryOffset(
            DataHandle<?> ifdStream,
            DataHandle<?> fileStream,
            boolean readEmbeddedData,
            long ifdStreamOffsetInTiffFile,
            TiffIFD.TiffEntry entry) throws IOException {
        Objects.requireNonNull(ifdStream, "Null ifdStream");
        Objects.requireNonNull(fileStream, "Null fileStream");
        Objects.requireNonNull(entry, "Null entry");
        final int type = entry.type();
        final int count = entry.valueCount();
        final long offset = entry.valueOffset();
        assert ifdStream.isLittleEndian() == fileStream.isLittleEndian();
        final ByteOrder byteOrder = fileStream.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        final DataHandle<?> stream = readEmbeddedData ? ifdStream : fileStream;

        LOG.log(System.Logger.Level.TRACE, () ->
                "Reading entry " + entry.tag() + " from " + offset + "; type=" + type + ", count=" + count);

        stream.seek(readEmbeddedData ? offset - ifdStreamOffsetInTiffFile : offset);
        switch (type) {
            case TagTypes.BYTE -> {
                // 8-bit unsigned integer
                if (count == 1) {
                    return (short) stream.readByte();
                }
                final byte[] bytes = new byte[count];
                stream.readFully(bytes);
                // bytes are unsigned, so use shorts
                final short[] shorts = new short[count];
                for (int j = 0; j < count; j++) {
                    shorts[j] = (short) (bytes[j] & 0xff);
                }
                return shorts;
            }
            case TagTypes.ASCII -> {
                // 8-bit byte that contains a 7-bit ASCII code;
                // the last byte must be NUL (binary zero)
                final byte[] ascii = new byte[count];
                stream.read(ascii);

                String[] lines = TiffIFD.asciiToText(ascii);
                return lines.length != 1 ? lines : lines[0] == null ? "" : lines[0];

                /* // Deprecated solution:
                // count the number of null terminators
                int zeroCount = 0;
                for (int j = 0; j < count; j++) {
                    if (ascii[j] == 0 || j == count - 1) {
                        zeroCount++;
                    }
                }
                // convert character array to array of strings
                final String[] strings = zeroCount == 1 ? null : new String[zeroCount];
                String s = null;
                int c = 0, index = -1;
                for (int j = 0; j < count; j++) {
                    if (ascii[j] == 0) {
                        s = new String(ascii, index + 1, j - index - 1, StandardCharsets.UTF_8);
                        index = j;
                    } else if (j == count - 1) {
                        // handle non-null-terminated strings
                        s = new String(ascii, index + 1, j - index, StandardCharsets.UTF_8);
                    } else {
                        s = null;
                    }
                    if (strings != null && s != null) {
                        strings[c++] = s;
                    }
                }
                return strings != null ? strings : s != null ? s : "";
                */
            }
            case TagTypes.SHORT -> {
                // 16-bit (2-byte) unsigned integer
                if (count == 1) {
                    return stream.readUnsignedShort();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readBytes(stream, 2 * (long) count);
                    final short[] shorts = JArrays.bytesToShortArray(bytes, byteOrder);
                    final int[] result = new int[count];
                    for (int j = 0; j < count; j++) {
                        result[j] = shorts[j] & 0xFFFF;
                    }
                    return result;
                } else {
                    final int[] ints = new int[count];
                    for (int j = 0; j < count; j++) {
                        ints[j] = stream.readUnsignedShort();
                    }
                    return ints;
                }
            }
            case TagTypes.LONG, TagTypes.IFD -> {
                // 32-bit (4-byte) unsigned integer
                if (count == 1) {
                    return stream.readInt() & 0xFFFFFFFFL;
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readBytes(stream, 4 * (long) count);
                    final int[] ints = JArrays.bytesToIntArray(bytes, byteOrder);
                    return Arrays.stream(ints).mapToLong(anInt -> anInt & 0xFFFFFFFFL).toArray();
                    // note: TIFF_LONG is UNSIGNED long
                } else {
                    final long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = stream.readInt() & 0xFFFFFFFFL;
                    }
                    return longs;
                }
            }
            case TagTypes.LONG8, TagTypes.SLONG8, TagTypes.IFD8 -> {
                if (count == 1) {
                    return stream.readLong();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readBytes(stream, 8 * (long) count);
                    return JArrays.bytesToLongArray(bytes, byteOrder);
                } else {
                    long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = stream.readLong();
                    }
                    return longs;
                }
            }
            case TagTypes.RATIONAL, TagTypes.SRATIONAL -> {
                // Two LONGs or SLONGs: the first represents the numerator of a fraction; the second, the denominator
                if (count == 1) {
                    return new TagRational(stream.readInt(), stream.readInt());
                }
                final TagRational[] rationals = new TagRational[count];
                for (int j = 0; j < count; j++) {
                    rationals[j] = new TagRational(stream.readInt(), stream.readInt());
                }
                return rationals;
            }
            case TagTypes.SBYTE, TagTypes.UNDEFINED -> {
                // SBYTE: An 8-bit signed (twos-complement) integer
                // UNDEFINED: An 8-bit byte that may contain anything,
                // depending on the definition of the field
                if (count == 1) {
                    return stream.readByte();
                }
                final byte[] sbytes = new byte[count];
                stream.read(sbytes);
                return sbytes;
            }
            case TagTypes.SSHORT -> {
                // A 16-bit (2-byte) signed (twos-complement) integer
                if (count == 1) {
                    return stream.readShort();
                }
                final short[] sshorts = new short[count];
                for (int j = 0; j < count; j++) {
                    sshorts[j] = stream.readShort();
                }
                return sshorts;
            }
            case TagTypes.SLONG -> {
                // A 32-bit (4-byte) signed (twos-complement) integer
                if (count == 1) {
                    return stream.readInt();
                }
                final int[] slongs = new int[count];
                for (int j = 0; j < count; j++) {
                    slongs[j] = stream.readInt();
                }
                return slongs;
            }
            case TagTypes.FLOAT -> {
                // Single precision (4-byte) IEEE format
                if (count == 1) {
                    return stream.readFloat();
                }
                final float[] floats = new float[count];
                for (int j = 0; j < count; j++) {
                    floats[j] = stream.readFloat();
                }
                return floats;
            }
            case TagTypes.DOUBLE -> {
                // Double precision (8-byte) IEEE format
                if (count == 1) {
                    return stream.readDouble();
                }
                final double[] doubles = new double[count];
                for (int j = 0; j < count; j++) {
                    doubles[j] = stream.readDouble();
                }
                return doubles;
            }
            default -> {
                final long valueOrOffset = stream.readLong();
                return new TiffIFD.UnsupportedTypeValue(type, count, valueOrOffset);
            }
        }
    }

    /**
     * Writes the given IFD value, splitting it between the {@code ifdStream}
     * (the directory entry) and the {@code extraBuffer} (the actual data if it
     * doesn't fit in the entry).
     *
     * <p>Writing in both streams is performed starting from their current positions.
     * After calling this method, you
     * should copy full content of {@code extraBuffer} into the main stream at the position,
     * specified by the second argument;
     * {@link TiffWriter#writeIFDAt(TiffIFD, Long, boolean)} method does it automatically.
     *
     * <p>Here "extra" data means all data, for which IFD contains their offsets instead of data itself,
     * like arrays or text strings. The "main" data is a 12-byte IFD record (20-byte for BigTIFF),
     * which is written by this method into the main output stream from its current position.
     *
     * @param ifdStream                   the main stream where IFD entries should be written.
     * @param extraBuffer                 the buffer where "extra" IFD information should be written.
     * @param bigTiff                     Big-TIFF flag.
     * @param extraBufferOffsetInTiffFile the position of "extra" data in the result TIFF file =
     *                                    {@code extraBufferOffsetInTiffFile} +
     *                                    offset of the written "extra" data inside {@code extraBuffer};
     *                                    for example, this argument may be a position directly after
     *                                    the "main" content (sequence of 12/20-byte records).
     * @param entry                       the IFD tag and value to write.
     */
    static void writeIFDValueAtCurrentOffsets(
            final DataHandle<?> ifdStream,
            final DataHandle<?> extraBuffer,
            final boolean bigTiff,
            final long extraBufferOffsetInTiffFile,
            Map.Entry<Integer, Object> entry) throws IOException {
        final int tag = entry.getKey();
        Object value = entry.getValue();
        // Convert singleton objects into arrays, for simplicity:
        if (value instanceof Short v) {
            value = new short[]{v};
        } else if (value instanceof Integer v) {
            value = new int[]{v};
        } else if (value instanceof Long v) {
            value = new long[]{v};
        } else if (value instanceof TagRational v) {
            value = new TagRational[]{v};
        } else if (value instanceof Float v) {
            value = new float[]{v};
        } else if (value instanceof Double v) {
            value = new double[]{v};
        }

        boolean emptyStringList = false;
        if (value instanceof String[] list) {
            emptyStringList = list.length == 0;
            value = String.join("\0", list);
        } else if (value instanceof List<?> list) {
            emptyStringList = list.isEmpty();
            value = list.stream().map(String::valueOf).collect(Collectors.joining("\0"));
        }

        final int dataLength = bigTiff ? 8 : 4;
        final int dataLengthDiv2 = dataLength >> 1;
        final int dataLengthDiv4 = dataLength >> 2;
        final ByteOrder byteOrder = extraBuffer.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

        // write directory entry to output buffers
        writeUnsignedShort(ifdStream, tag);
        switch (value) {
            case byte[] v -> {
                ifdStream.writeShort(TagTypes.UNDEFINED);
                // - Most probable type. Maybe in future we will support here some algorithm,
                // determining the necessary type on the base of the tag value.
                writeIntOrLong(ifdStream, bigTiff, v.length);
                if (v.length <= dataLength) {
                    for (byte byteValue : v) {
                        ifdStream.writeByte(byteValue);
                    }
                    for (int i = v.length; i < dataLength; i++) {
                        ifdStream.writeByte(0);
                    }
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                    extraBuffer.write(v);
                }
            }
            case short[] v -> {
                // suppose BYTE (unsigned 8-bit)
                ifdStream.writeShort(TagTypes.BYTE);
                writeIntOrLong(ifdStream, bigTiff, v.length);
                if (v.length <= dataLength) {
                    for (short s : v) {
                        ifdStream.writeByte(s);
                    }
                    for (int i = v.length; i < dataLength; i++) {
                        ifdStream.writeByte(0);
                    }
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                    byte[] bytes = new byte[v.length];
                    for (int i = 0; i < v.length; i++) {
                        bytes[i] = (byte) v[i];
                    }
                    extraBuffer.write(bytes);
                }
            }
            case String stringValue -> {
                // suppose ASCII
                ifdStream.writeShort(TagTypes.ASCII);
                final byte[] v = stringValue.getBytes(StandardCharsets.UTF_8);
                writeIntOrLong(ifdStream, bigTiff, emptyStringList ? 0 : v.length + 1);
                // - with concluding zero bytes, excepting an empty string list (produced by ASCII byte[0])
                if (v.length < dataLength) {
                    // - this branch is the same for an empty string list (byte[0])
                    // and for an empty string (byte[1] which contains 0)
                    for (byte c : v) {
                        writeUnsignedByte(ifdStream, c & 0xFF);
                    }
                    for (int i = v.length; i < dataLength; i++) {
                        ifdStream.writeByte(0);
                    }
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                    extraBuffer.write(v);
//                for (byte c : v) {
//                    if (charValue > 0xFF) {
//                        throw new TiffException("Attempt to write a character with code " + (int) charValue +
//                                " > 255; only ASCII characters with 0..255 codes are supported in string TIFF tags");
//                    }
//                    writeUnsignedByte(extraBuffer, c & 0xFF);
//                }
                    extraBuffer.writeByte(0); // concluding zero bytes
                }
            }
            case int[] v -> {
                // suppose SHORT (unsigned 16-bit)
                if (v.length == 1) {
                    // - we should allow using usual int values for 32-bit tags to avoid a lot of obvious bugs
                    final int v0 = v[0];
                    if (v0 >= 0xFFFF) {
                        ifdStream.writeShort(TagTypes.LONG);
                        writeIntOrLong(ifdStream, bigTiff, v.length);
                        writeIntOrLong(ifdStream, bigTiff, v0);
                        return;
                    }
                }
                ifdStream.writeShort(TagTypes.SHORT);
                writeIntOrLong(ifdStream, bigTiff, v.length);
                if (v.length <= dataLengthDiv2) {
                    for (int intValue : v) {
                        writeUnsignedShort(ifdStream, intValue);
                    }
                    for (int i = v.length; i < dataLengthDiv2; i++) {
                        ifdStream.writeShort(0);
                    }
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                    short[] shorts = new short[v.length];
                    for (int i = 0; i < v.length; i++) {
                        shorts[i] = (short) v[i];
                    }
                    extraBuffer.write(JArrays.shortArrayToBytes(shorts, byteOrder));
                }
            }
            case long[] v -> {
                // suppose LONG (unsigned 32-bit) or LONG8 for BitTIFF
                if (AVOID_LONG8_FOR_ACTUAL_32_BITS && v.length == 1 && bigTiff) {
                    // - note: inside TIFF, long[1] is saved in the same way as Long; we have a difference in Java only
                    final long v0 = v[0];
                    if (v0 == (int) v0) {
                        // - it is probable for the following tags if they are added
                        // manually via TiffIFD.put with "long" argument
                        switch (tag) {
                            case Tags.IMAGE_WIDTH,
                                 Tags.IMAGE_LENGTH,
                                 Tags.TILE_WIDTH,
                                 Tags.TILE_LENGTH,
                                 Tags.IMAGE_DEPTH,
                                 Tags.ROWS_PER_STRIP,
                                 Tags.NEW_SUBFILE_TYPE -> {
                                ifdStream.writeShort(TagTypes.LONG);
                                writeIntOrLong(ifdStream, bigTiff, v.length);
                                ifdStream.writeInt((int) v0);
                                ifdStream.writeInt(0);
                                // - 4 bytes of padding until full length 20 bytes
                                return;
                            }
                        }
                    }
                }
                final int type = bigTiff ? TagTypes.LONG8 : TagTypes.LONG;
                ifdStream.writeShort(type);
                writeIntOrLong(ifdStream, bigTiff, v.length);

                if (v.length <= 1) {
                    for (int i = 0; i < v.length; i++) {
                        writeIntOrLong(ifdStream, bigTiff, v[0]);
                        // - v[0]: it is actually performed 0 or 1 times
                    }
                    for (int i = v.length; i < 1; i++) {
                        writeIntOrLong(ifdStream, bigTiff, 0);
                    }
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                    extraBuffer.write(longArrayToBytes(v, bigTiff, byteOrder));
//              Old solution:
//                for (long longValue : v) {
//                    writeIntOrLong(extraBuffer, bigTiff, longValue);
//                }
                }
            }
            case TagRational[] v -> {
                ifdStream.writeShort(TagTypes.RATIONAL);
                writeIntOrLong(ifdStream, bigTiff, v.length);
                if (bigTiff && v.length == 1) {
                    ifdStream.writeInt((int) v[0].getNumerator());
                    ifdStream.writeInt((int) v[0].getDenominator());
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                    for (TagRational tagRational : v) {
                        extraBuffer.writeInt((int) tagRational.getNumerator());
                        extraBuffer.writeInt((int) tagRational.getDenominator());
                    }
                }
            }
            case float[] v -> {
                ifdStream.writeShort(TagTypes.FLOAT);
                writeIntOrLong(ifdStream, bigTiff, v.length);
                if (v.length <= dataLengthDiv4) {
                    for (float floatValue : v) {
                        ifdStream.writeFloat(floatValue); // value
                        // - in old SCIFIO code, here was a bug (for a case bigTiff): v[0] was always written
                    }
                    for (int i = v.length; i < dataLengthDiv4; i++) {
                        ifdStream.writeInt(0); // padding
                    }
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                    for (float floatValue : v) {
                        extraBuffer.writeFloat(floatValue);
                    }
                }
            }
            case double[] v -> {
                ifdStream.writeShort(TagTypes.DOUBLE);
                writeIntOrLong(ifdStream, bigTiff, v.length);
                appendUntilEvenOffset(extraBuffer);
                writeOffset(ifdStream, bigTiff, extraBufferOffsetInTiffFile + extraBuffer.offset());
                for (final double doubleValue : v) {
                    extraBuffer.writeDouble(doubleValue);
                }
            }
            case TiffIFD.UnsupportedTypeValue unsupported -> {
                ifdStream.writeShort(unsupported.type());
                // - but we don't know the sense of its valueOrOffset field; it is better to write "0 elements"
                writeIntOrLong(ifdStream, bigTiff, 0);
                for (int i = 0; i < dataLength; i++) {
                    ifdStream.writeByte(0);
                }
            }
            case null -> throw new UnsupportedOperationException("IFD tag " + tag + " contains null value");
            default -> throw new UnsupportedOperationException("Unknown IFD tag " + tag + " value type ("
                    + value.getClass().getSimpleName() + "): " + value);
        }
    }

    static long readOffset(
            DataHandle<?> stream,
            boolean bigTiff,
            long positionStartIncrement,
            long tiffFileLength,
            Supplier<String> fileNameSupplier) throws IOException {
//        final long fileOffsetOfNextOffset = stream.offset();
        long offset;
        if (bigTiff) {
            offset = stream.readLong();
        } else {
            // Below is a deprecated solution
            // (this "trick" cannot help if a SINGLE image is very large (>2^32): for example,
            // previous = 8 (1st IFD) and the next is 0x120000000; but it is the mostly typical
            // problematic situation: for example, very large 1st IFD in SVS file).
            //
            // offset = (previous & ~0xffffffffL) | (in.readInt() & 0xffffffffL);
            // Only adjust the offset if we know that the file is too large for
            // 32-bit
            // offsets to be accurate; otherwise, we're making the incorrect
            // assumption
            // that IFDs are stored sequentially.
            // if (offset < previous && offset != 0 && in.length() > Integer.MAX_VALUE) {
            //      offset += 0x100000000L;
            // }
            // return offset;

            offset = (long) stream.readInt() & 0xffffffffL;
            // - in usual TIFF format, offset if 32-bit UNSIGNED value
        }
//        if (stream.offset() - (bigTiff ? 8 : 4) == fileOffsetOfNextOffset) {
//            System.out.println(stream.offset() - (bigTiff ? 8 : 4) + " " + bigTiff);
//        } else throw new AssertionError();
        if (offset < 0 || offset >= tiffFileLength) {
            // offset < 0 is possible in BigTIFF only
            String fileName = fileNameSupplier.get();
            throw new TiffException((
                    (offset < 0 ? "Invalid TIFF%s: negative 64-bit IFD offset %d (0x%X) at file offset %d, " :
                            "Invalid TIFF%s: IFD offset %d (0x%X) at file offset %d is outside the file length " +
                            tiffFileLength + ", ") +
                            "probably the file is corrupted").formatted(
                    fileName.isEmpty() ? "" : " " + fileName,
                    offset, offset,
                    stream.offset() - (bigTiff ? 8 : 4) + positionStartIncrement));
        }
        return offset;
    }

    static void writeOffset(DataHandle<?> handle, boolean bigTiff, long offsetValueToWrite) throws IOException {
        if (offsetValueToWrite < 0) {
            throw new IllegalArgumentException("Illegal usage of writeOffset: negative offset " + offsetValueToWrite);
        }
        if (bigTiff) {
            handle.writeLong(offsetValueToWrite);
        } else {
            if (offsetValueToWrite > 0xFFFFFFF0L) {
                throw new TiffException("Attempt to write too large 64-bit offset as unsigned 32-bit: " +
                        offsetValueToWrite + " > 2^32-16; such large files should be written in BigTIFF mode");
            }
            handle.writeInt((int) offsetValueToWrite);
            // - masking by 0xFFFFFFFF is unnecessary: cast to (int) works properly also for 32-bit unsigned values
        }
    }

    // Note used in the current version.
    // It was used in the TiffReader constructor with Path argument: see comments in its implementation.
    static DataHandle<?> getExistingFileHandle(Path file) throws FileNotFoundException {
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File " + file
                    + (Files.exists(file) ? " is not a regular file" : " does not exist"));
        }
        return getFileHandle(file);
    }

    static void appendUntilEvenOffset(DataHandle<?> handle) throws IOException {
        if ((handle.offset() & 0x1) != 0) {
//            System.out.println("Correction " + handle.offset());
            handle.writeByte(0);
            // - Well-formed IFD requires even offsets
        }
    }

    static DataHandle<?> getFileHandle(Path file) {
        Objects.requireNonNull(file, "Null file");
        FileHandle fileHandle = new FileHandle(new FileLocation(file.toFile()));
        fileHandle.setLittleEndian(false);
        // - in the current implementation it is an extra operator: BigEndian is defaulted in scijava;
        // but we want to be sure that this behavior will be the same in all future versions
        return fileHandle;
    }

    static BytesHandle getBytesHandle(byte[] data, boolean littleEndian) {
        Objects.requireNonNull(data, "Null data");
        final BytesHandle result = new BytesHandle(new BytesLocation(data));
        result.setLittleEndian(littleEndian);
        return result;
    }

    static BytesHandle newBytesHandle(boolean littleEndian) {
        final BytesLocation bytesLocation = new BytesLocation(0, "memory-buffer");
        final BytesHandle result = new BytesHandle(bytesLocation);
        result.setLittleEndian(littleEndian);
        return result;
    }

    static DataHandle<?> getFileHandle(FileLocation fileLocation) {
        Objects.requireNonNull(fileLocation, "Null fileLocation");
        FileHandle fileHandle = new FileHandle(fileLocation);
        fileHandle.setLittleEndian(false);
        // - in the current implementation it is an extra operator: BigEndian is defaulted in scijava;
        // but we want to be sure that this behavior will be the same in all future versions
        return fileHandle;
    }

    static long debugTime() {
        return BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }

    @SuppressWarnings("SameParameterValue")
    static boolean getBooleanProperty(String propertyName) {
        try {
            return Boolean.getBoolean(propertyName);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readBytes(DataHandle<?> handle, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new TiffException("Too large IFD value: " + length + " >= 2^31 bytes");
        }
        byte[] bytes = new byte[(int) length];
        handle.readFully(bytes);
        return bytes;
    }

    private static byte[] longArrayToBytes(long[] values, boolean bigTiff, ByteOrder byteOrder) throws TiffException {
        if (bigTiff) {
            return JArrays.longArrayToBytes(values, byteOrder);
        } else {
            int[] ints = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                long value = values[i];
                if (value < Integer.MIN_VALUE || value > 0xFFFFFFFFL) {
                    // - note: positive values in range 0x80000000..0xFFFFFFFF are mostly probably unsigned integers,
                    // not signed values with overflow
                    throw new TiffException("Attempt to write 64-bit value as 32-bit: " + value);
                }
                ints[i] = (int) value;
            }
            return JArrays.intArrayToBytes(ints, byteOrder);
        }
    }

    /**
     * Write the given value to the given RandomAccessOutputStream. If the
     * 'bigTiff' flag is set, then the value will be written as an 8-byte long;
     * otherwise, it will be written as a 4-byte integer.
     */
    private static void writeIntOrLong(DataHandle<?> handle, boolean bigTiff, long value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            if (value < Integer.MIN_VALUE || value > 0xFFFFFFFFL) {
                // - note: positive values in range 0x80000000..0xFFFFFFFF are mostly probably unsigned integers,
                // not signed values with overflow
                throw new TiffException("Attempt to write 64-bit value as 32-bit: " + value);
            }
            handle.writeInt((int) value);
        }
    }

    private static void writeIntOrLong(DataHandle<?> handle, boolean bigTiff, int value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            handle.writeInt(value);
        }
    }

    private static void writeUnsignedShort(DataHandle<?> handle, int value) throws IOException {
        if (value < 0 || value > 0xFFFF) {
            throw new TiffException("Attempt to write 32-bit value as 16-bit: " + value);
        }
        handle.writeShort(value);
    }

    private static void writeUnsignedByte(DataHandle<?> handle, int value) throws IOException {
        if (value < 0 || value > 0xFF) {
            throw new TiffException("Attempt to write 16/32-bit value as 8-bit: " + value);
        }
        handle.writeByte(value);
    }
}
