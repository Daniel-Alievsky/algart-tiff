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

import net.algart.matrices.tiff.tags.TagCompression;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public sealed abstract class TiffIO implements Closeable permits TiffReader, TiffWriter {
    public static final int FILE_USUAL_MAGIC_NUMBER = 0x2a;
    public static final int FILE_BIG_TIFF_MAGIC_NUMBER = 0x2b;
    public static final int FILE_PREFIX_LITTLE_ENDIAN = 0x49;
    public static final int FILE_PREFIX_BIG_ENDIAN = 0x4d;

    static final System.Logger LOG = System.getLogger(TiffIO.class.getName());
    static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    static final boolean BUILT_IN_TIMING = getBooleanProperty("net.algart.matrices.tiff.timing");

    final DataHandle<?> stream;
    private final Path filePath;
    private final Object fileLock = new Object();

    volatile Object context = null;
    volatile Object scifio = null;

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

    public TiffReader newReader(TiffOpenMode openMode) throws IOException {
        return new TiffReader(stream, openMode, false);
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
        final long result = copyData(inputStream, outputStream, inputLength);
        outputStream.setLength(outputStream.offset());
        if (result != inputLength) {
            throw new EOFException("Copied only " + result + " bytes from all " + inputLength + " bytes");
            // - should not occur in the normal situation
        }
        return result;
    }

    // A simplified clone of the function DataHandles.copy without the problem with invalid generic types
    static long copyData(DataHandle<?> in, DataHandle<?> out, long length)
            throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("Negative length: " + length);
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

    // Note used in the current version.
    // It was used in the TiffReader constructor with Path argument: see comments in its implementation.
    static DataHandle<?> getExistingFileHandle(Path file) throws FileNotFoundException {
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File " + file
                    + (Files.exists(file) ? " is not a regular file" : " does not exist"));
        }
        return getFileHandle(file);
    }

    static DataHandle<?> getFileHandle(Path file) {
        Objects.requireNonNull(file, "Null file");
        FileHandle fileHandle = new FileHandle(new FileLocation(file.toFile()));
        fileHandle.setLittleEndian(false);
        // - in the current implementation it is an extra operator: BigEndian is defaulted in scijava;
        // but we want to be sure that this behavior will be the same in all future versions
        return fileHandle;
    }

    static BytesHandle getBytesHandle(byte[] data) {
        Objects.requireNonNull(data, "Null data");
        return new BytesHandle(new BytesLocation(data));
    }

    static BytesHandle getBytesHandle(BytesLocation bytesLocation) {
        Objects.requireNonNull(bytesLocation, "Null bytesLocation");
        return new BytesHandle(bytesLocation);
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
}
