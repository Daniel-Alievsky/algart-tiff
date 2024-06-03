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

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.matrices.tiff.codecs.TiffCodec;
import net.algart.matrices.tiff.tags.*;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIO;
import net.algart.matrices.tiff.tiles.TiffTileIndex;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandles;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Writes TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe when used in multi-threaded environment.
 * However, you should not modify objects, passed to the methods of this class, from a parallel thread;
 * in particular, it concerns the {@link TiffIFD} arguments and Java-arrays with samples.
 * The same is true for the result of {@link #stream()} method.</p>
 */
public class TiffWriter implements Closeable {
    // Creating this class started from reworking SCIFIO TiffSaver class.
    // Below is a copy of list of its authors and of the SCIFIO license for that class.
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * @author Curtis Rueden
     * @author Eric Kjellman
     * @author Melissa Linkert
     * @author Chris Allan
     * @author Gabriel Einsdorf
     *
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    /**
     * If the file grows to about this limit and {@link #setBigTiff(boolean) big-TIFF} mode is not set,
     * attempt to write new IFD at the file end by methods of this class throw IO exception.
     * While writing tiles, an exception will be thrown only while exceeding the limit <tt>2^32-1</tt>
     * (~280 MB greater than this value {@value}).
     */
    public static final long MAXIMAL_ALLOWED_32BIT_IFD_OFFSET = 4_000_000_000L;

    private static final boolean AVOID_LONG8_FOR_ACTUAL_32_BITS = true;
    // - If was necessary for some old programs (like Aperio Image Viewer), which
    // did not understand LONG8 values for some popular tags like image sizes.
    // In any case, real BigTIFF files usually store most tags in standard LONG type (32 bits), not in LONG8.

    private static final System.Logger LOG = System.getLogger(TiffWriter.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean bigTiff = false;
    private boolean writingForwardAllowed = true;
    private boolean autoInterleaveSource = true;
    private boolean smartIFDCorrection = false;
    private TiffCodec.Options codecOptions = new TiffCodec.Options();
    private boolean enforceUseExternalCodec = false;
    private Double quality = null;
    private boolean preferRGB = false;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;
    private Consumer<TiffTile> tileInitializer = this::fillEmptyTile;
    private volatile Context context = null;

    private final DataHandle<Location> out;
    private volatile Object scifio = null;

    private final Object fileLock = new Object();

    private final LinkedHashSet<Long> ifdOffsets = new LinkedHashSet<>();
    private volatile long positionOfLastIFDOffset = -1;

    private long timeWriting = 0;
    private long timePreparingEncoding = 0;
    private long timeCustomizingEncoding = 0;
    private long timeEncoding = 0;
    private long timeEncodingMain = 0;
    private long timeEncodingBridge = 0;
    private long timeEncodingAdditional = 0;

    public TiffWriter(Path file) throws IOException {
        this(file, false);
    }

    public TiffWriter(Path file, boolean deleteExistingFile) throws IOException {
        this(openWithDeletingPreviousFileIfRequested(file, deleteExistingFile));
    }

    /**
     * Creates new TIFF writer.
     *
     * <p>Note: unlike classes like <tt>java.io.FileWriter</tt> and unlike {@link TiffReader},
     * this constructor <b>does not actually open or create file</b>.
     * You <b>must</b> call one of methods {@link #create()} or {@link #open(boolean)} after creating this object
     * by the constructor.
     *
     * @param outputStream output TIFF file.
     */
    public TiffWriter(DataHandle<Location> outputStream) {
        Objects.requireNonNull(outputStream, "Null \"outputStream\" data handle (output stream)");
        this.out = outputStream;
    }

    public TiffReader readerOfThisFile(boolean requireValidTiff) throws IOException {
        return new TiffReader(out, requireValidTiff, false);
    }

    /**
     * Returns whether we are writing little-endian data.
     */
    public boolean isLittleEndian() {
        synchronized (fileLock) {
            return out.isLittleEndian();
        }
    }

    /**
     * Sets whether little-endian data should be written.
     * <p>Note that the default order is <b>big-endian</b>.
     *
     * @param littleEndian new byte while writing the file: big-endian (<tt>false</tt>) or
     *                     little-endian (<tt>true</tt>); default is <tt>false</tt>.
     */
    public TiffWriter setLittleEndian(final boolean littleEndian) {
        synchronized (fileLock) {
            out.setLittleEndian(littleEndian);
        }
        return this;
    }

    /**
     * Returns whether we are writing BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Sets whether BigTIFF data should be written.
     */
    public TiffWriter setBigTiff(final boolean bigTiff) {
        this.bigTiff = bigTiff;
        return this;
    }


    public boolean isWritingForwardAllowed() {
        return writingForwardAllowed;
    }

    public TiffWriter setWritingForwardAllowed(boolean writingForwardAllowed) {
        this.writingForwardAllowed = writingForwardAllowed;
        return this;
    }

    public boolean isAutoInterleaveSource() {
        return autoInterleaveSource;
    }

    /**
     * Sets auto-interleave mode.
     *
     * <p>If set, then the samples array in <tt>write...</tt> methods is always supposed to be unpacked.
     * For multichannel images it means the samples order like RRR..GGG..BBB...: standard form, supposed by
     * <tt>io.scif.Plane</tt> class and returned by {@link TiffReader}. If the desired IFD format is
     * chunked, i.e. {@link Tags#PLANAR_CONFIGURATION} is {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED}
     * (that is the typical usage), then the passes samples are automatically re-packed into chunked (interleaved)
     * form RGBRGBRGB...
     *
     * <p>If this mode is not set, as well as if {@link Tags#PLANAR_CONFIGURATION} is
     * {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE}, the passed data are encoded as-as, i.e. as unpacked
     * RRR...GGG..BBB...  for {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE} or as interleaved RGBRGBRGB...
     * for {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED}.
     *
     * <p>Note that this flag is ignored if the result data in the file should not be interleaved,
     * i.e. for 1-channel images and if {@link Tags#PLANAR_CONFIGURATION} is
     * {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE}.
     *
     * @param autoInterleaveSource new auto-interleave mode. Default value is <tt>true</tt>.
     * @return a reference to this object.
     */
    public TiffWriter setAutoInterleaveSource(boolean autoInterleaveSource) {
        this.autoInterleaveSource = autoInterleaveSource;
        return this;
    }

    public boolean isSmartIFDCorrection() {
        return smartIFDCorrection;
    }

    /**
     * Sets smart IFD correction mode.
     *
     * <p>IFD, offered by the user for writing TIFF image (usually with help of {@link #newMap(TiffIFD)} method),
     * may contain specification, which are incorrect or not supported by this class. For example,
     * the user may specify {@link TiffIFD#putPhotometricInterpretation(TagPhotometricInterpretation)
     * photometric interpretation} {@link TagPhotometricInterpretation#RGB_PALETTE}, but not provide actual
     * palette via the corresponding TIFF entry, or may specify 1000000 bits/pixel etc.</p>
     *
     * <p>If the settings in the specified IFD are absolutely incorrect, this class always throws
     * {@link TiffException}. If the settings look possible in principle, but this class does not support
     * writing in this mode, the behaviour depends on the flag, setting by this method.</p>
     *
     * <p>If this mode is set to <tt>true</tt> (the "smart" IFD correction), the writer may try to change IFD to
     * some similar settings, so that it will be able to write the image. In particular, if number of bits
     * per sample is not divided by 8 (like 4 bits/sample or "HiRes" RGB image with 5+5+5 bits/pixel),
     * the number of bits will be automatically increased up to the nearest supported bit depth: 8, 16 or 32
     * (for floating-point images, up to 32- or 64-bit <tt>float</tt>/<tt>double</tt> precision).
     * If you specified YCbCr photometric interpretation for <i>non-JPEG</i> compression &mdash;
     * for example, uncompressed, LZW, or Deflate compression &mdash; it will be automatically replaced with RGB
     * (this class supports YCbCr encoding for JPEG only). And so on.</p>
     *
     * <p>If this mode is not set (this flag is <tt>false</tt>), such settings will lead to an exception.
     * In this case we guarantee that TIFF writer never changes existing entries in the passed IFD, but may only
     * <i>add</i> some tag if they are necessary.</p>
     *
     * <p>Default value is <tt>false</tt>. You may set it to <tt>true</tt>, for example, when you need
     * to encode a new copy of some existing TIFF file.</p>
     *
     * <p>Note that this flag is passed to {@link #correctIFDForWriting(TiffIFD, boolean)} method,
     * called inside <tt>writeSamples</tt>/<tt>writeJavaArray</tt> methods.
     *
     * @param smartIFDCorrection <tt>true</tt> means that we enable smart correction of the specified IFD
     *                           and do not require strictly accurate, fully supported IFD settings.
     * @return a reference to this object.
     */
    public TiffWriter setSmartIFDCorrection(boolean smartIFDCorrection) {
        this.smartIFDCorrection = smartIFDCorrection;
        return this;
    }

    public TiffCodec.Options getCodecOptions() {
        return codecOptions.clone();
    }

    /**
     * Sets the codec options.
     *
     * @param codecOptions The value to set.
     * @return a reference to this object.
     */
    public TiffWriter setCodecOptions(final TiffCodec.Options codecOptions) {
        this.codecOptions = Objects.requireNonNull(codecOptions, "Null codecOptions").clone();
        return this;
    }

    public boolean isEnforceUseExternalCodec() {
        return enforceUseExternalCodec;
    }

    public TiffWriter setEnforceUseExternalCodec(boolean enforceUseExternalCodec) {
        this.enforceUseExternalCodec = enforceUseExternalCodec;
        return this;
    }

    public boolean hasQuality() {
        return quality != null;
    }

    public Double getQuality() {
        return quality;
    }

    public TiffWriter setQuality(Double quality) {
        return quality == null ? removeQuality() : setQuality(quality.doubleValue());
    }

    /**
     * Sets the compression quality for JPEG tiles/strips to some non-negative value.
     *
     * <p>Possible values are format-specific. For JPEG, it should between 0.0 and 1.0 (1.0 means the best quality).
     * For JPEG-2000, maximal possible value is <tt>Double.MAX_VALUE</tt>, that means loss-less compression.
     *
     * <p>If this method was not called (or after {@link #removeQuality()}), the compression quality is not specified.
     * In this case, some default quality will be used. In particular, it will be 1.0 for JPEG (maximal JPEG quality),
     * 10 for JPEG-2000 (compression code 33003) or alternative JPEG-200 (code 33005),
     * <tt>Double.MAX_VALUE</tt> for lose-less JPEG-2000 ({@link TagCompression#JPEG_2000_LOSSY}, code 33004).
     * Note that the only difference between lose-less JPEG-2000 and the standard JPEG-2000 is this defaults:
     * if this method is called, both compressions work identically (but write different TIFF compression tags).
     *
     * <p>Note: the {@link TiffCodec.Options#setQuality(Double) quality}, that can be set via
     * {@link #setCodecOptions(TiffCodec.Options)} method, is ignored,
     * if this value is set to non-<tt>null</tt> value.
     *
     * <p>Please <b>remember</b> that this parameter may be different for different IFDs.
     * In this case, you need to call this method every time before creating new IFD,
     * not only once for all TIFF file!
     *
     * @param quality floating-point value, the desired quality level.
     * @return a reference to this object.
     */
    public TiffWriter setQuality(double quality) {
        if (quality < 0.0) {
            throw new IllegalArgumentException("Negative quality " + quality + " is not allowed");
        }
        this.quality = quality;
        return this;
    }

    public TiffWriter removeQuality() {
        this.quality = null;
        return this;
    }

    public boolean isPreferRGB() {
        return preferRGB;
    }

    /**
     * Sets whether you need to prefer RGB photometric interpretation even in such formats, where another
     * color space is more traditional. Default value is <tt>false</tt>.
     *
     * <p>In the current version, it only applies to {@link TagCompression#JPEG} format: this flag enforces the writer
     * to compress JPEG tiles/stripes with photometric interpretation RGB.
     * If this flag is <tt>false</tt>, the writer uses YCbCr photometric interpretation &mdash;
     * a standard encoding for JPEG, but not so popular in TIFF.
     *
     * <p>This flag is used if a photometric interpretation in not specified in the IFD. Otherwise,
     * this flag is ignored and the writer uses the photometric interpretation from the IFD
     * (but, for JPEG, only YCbCr and RGB options are allowed).
     *
     * <p>Please <b>remember</b> that this parameter may vary between different IFDs.
     * In this case, you need to call this method every time before creating a new IFD,
     * not just once for the entire TIFF file!
     *
     * <p>This parameter is ignored (as if it is <tt>false</tt>), if {@link #isEnforceUseExternalCodec()}
     * return <tt>true</tt>.
     *
     * <p>This flag affects the results of {@link #newMap(TiffIFD, boolean)} method: it helps to choose
     * correct photometric interpretation tag. Once a TIFF map is created and information is stored in an IFD object,
     * this object no longer uses this flag.
     *
     * @param preferRGB whether you want to compress JPEG in RGB encoding.
     * @return a reference to this object.
     */
    public TiffWriter setPreferRGB(boolean preferRGB) {
        this.preferRGB = preferRGB;
        return this;
    }

    public boolean isMissingTilesAllowed() {
        return missingTilesAllowed;
    }

    /**
     * Sets the special mode, when TIFF file is allowed to contain "missing" tiles or strips,
     * for which the offset (<tt>TileOffsets</tt> or <tt>StripOffsets</tt> tag) and/or
     * byte count (<tt>TileByteCounts</tt> or <tt>StripByteCounts</tt> tag) contains zero value.
     * In this mode, this writer will use zero offset and byte count, if
     * the written tile is actually empty &mdash; no pixels were written in it via
     * {@link #updateSamples(TiffMap, byte[], int, int, int, int)} or other methods.
     * In other case, this writer will create a normal tile, filled by
     * the {@link #setByteFiller(byte) default filler}.
     *
     * <p>Default value is <tt>false</tt>. Note that <tt>true</tt> value violates requirement of
     * the standard TIFF format (but may be used by some non-standard software, which needs to
     * detect areas, not containing any actual data).
     *
     * @param missingTilesAllowed whether "missing" tiles/strips are allowed.
     * @return a reference to this object.
     */
    public TiffWriter setMissingTilesAllowed(boolean missingTilesAllowed) {
        this.missingTilesAllowed = missingTilesAllowed;
        return this;
    }

    public byte getByteFiller() {
        return byteFiller;
    }

    public TiffWriter setByteFiller(byte byteFiller) {
        this.byteFiller = byteFiller;
        return this;
    }

    public Consumer<TiffTile> getTileInitializer() {
        return tileInitializer;
    }

    public TiffWriter setTileInitializer(Consumer<TiffTile> tileInitializer) {
        this.tileInitializer = Objects.requireNonNull(tileInitializer, "Null tileInitializer");
        return this;
    }

    public Context getContext() {
        return context;
    }

    public TiffWriter setContext(Context context) {
        this.scifio = null;
        this.context = context;
        return this;
    }

    /**
     * Gets the stream from which TIFF data is being saved.
     */
    public DataHandle<Location> stream() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return out;
        }
    }

    /**
     * Returns position in the file of the last IFD offset, written by methods of this object.
     * It is updated by {@link #rewriteIFD(TiffIFD, boolean)}.
     *
     * <p>Immediately after creating new object this position is <tt>-1</tt>.
     *
     * @return file position of the last IFD offset.
     */
    public long positionOfLastIFDOffset() {
        return positionOfLastIFDOffset;
    }

    public int numberOfIFDs() {
        return ifdOffsets.size();
    }

    public TiffWriter openExisting() throws IOException {
        return open(false);
    }

    public TiffWriter openOrCreate() throws IOException {
        return open(true);
    }

    public TiffWriter open(boolean createIfNotExists) throws IOException {
        synchronized (fileLock) {
            if (!out.exists()) {
                if (createIfNotExists) {
                    return create();
                } else {
                    throw new FileNotFoundException("Output TIFF file " +
                            TiffReader.prettyFileName("%s", out) + " does not exist");
                }
            }
            ifdOffsets.clear();
            final TiffReader reader = new TiffReader(out, true, false);
            // - note: we should NOT close the reader in a case of any problem,
            // because it uses the same stream with this writer
            final long[] offsets = reader.readIFDOffsets();
            final long readerPositionOfLastOffset = reader.positionOfLastIFDOffset();
            this.setBigTiff(reader.isBigTiff()).setLittleEndian(reader.isLittleEndian());
            ifdOffsets.addAll(Arrays.stream(offsets).boxed().toList());
            positionOfLastIFDOffset = readerPositionOfLastOffset;
            seekToEnd();
            // - ready to write after the end of the file
            // (not necessary, but can help to avoid accidental bugs)
            return this;
        }
    }

    public TiffWriter create() throws IOException {
        synchronized (fileLock) {
            ifdOffsets.clear();
            out.seek(0);
            if (isLittleEndian()) {
                out.writeByte(TiffTools.FILE_PREFIX_LITTLE_ENDIAN);
                out.writeByte(TiffTools.FILE_PREFIX_LITTLE_ENDIAN);
            } else {
                out.writeByte(TiffTools.FILE_PREFIX_BIG_ENDIAN);
                out.writeByte(TiffTools.FILE_PREFIX_BIG_ENDIAN);
            }
            // Writing magic number:
            if (bigTiff) {
                out.writeShort(TiffTools.FILE_BIG_TIFF_MAGIC_NUMBER);
            } else {
                out.writeShort(TiffTools.FILE_USUAL_MAGIC_NUMBER);
            }
            if (bigTiff) {
                out.writeShort(8);
                // - 16-bit "8" value means the number of bytes in BigTIFF offsets
                out.writeShort(0);
            }
            // Writing the "last offset" marker:
            positionOfLastIFDOffset = out.offset();
            writeOffset(TiffIFD.LAST_IFD_OFFSET);
            // Truncating the file if it already existed:
            // it is necessary, because this class writes all new information
            // to the file end (to avoid damaging existing content)
            out.setLength(out.offset());
            return this;
        }
    }

    public void rewriteIFD(final TiffIFD ifd, boolean updateIFDLinkages) throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (!ifd.hasFileOffsetForWriting()) {
            throw new IllegalArgumentException("Offset for writing IFD is not specified");
        }
        final long offset = ifd.getFileOffsetForWriting();
        assert (offset & 0x1) == 0 : "TiffIFD.setFileOffsetForWriting() has not check offset parity: " + offset;

        writeIFDAt(ifd, offset, updateIFDLinkages);
    }

    public void writeIFDAtFileEnd(TiffIFD ifd, boolean updateIFDLinkages) throws IOException {
        writeIFDAt(ifd, null, updateIFDLinkages);
    }

    /**
     * Writes IFD at the position, specified by <tt>startOffset</tt> argument, or at the file end
     * (aligned to nearest even length) if it is <tt>null</tt>.
     *
     * <p>Note: this IFD is automatically marked as last IFD in the file (next IFD offset is 0),
     * unless you explicitly specified other next offset via {@link TiffIFD#setNextIFDOffset(long)}.
     * You also may call {@link #rewritePreviousLastIFDOffset(long)} to correct
     * this mark inside the file in the previously written IFD, but usually there is no necessity to do this.</p>
     *
     * <p>If <tt>updateIFDLinkages</tt> is <tt>true</tt>, this method also performs the following 2 actions.</p>
     *
     * <ol>
     *     <li>It updates the offset, stored in the file at {@link #positionOfLastIFDOffset()}, with start offset of
     *     this IFD (i.e. <tt>startOffset</tt> or position of the file end). This action is performed <b>only</b>
     *     if this start offset is really new for this file, i.e. if it did not present in an existing file
     *     while opening it by {@link #openExisting()} method and if some IFD was not already written
     *     at this position by methods of this object.</li>
     *     <li>It replaces the internal field, returned by {@link #positionOfLastIFDOffset()}, with
     *     the position of the next IFD offset, written as a part of this IFD.
     *     This action is performed <b>only</b> when this IFD is marked as the last one (see the previos note).</li>
     * </ol>
     *
     * <p>Also note: this method changes position in the output stream.
     * (Actually it will be a position after the IFD information, including all additional data
     * like arrays of offsets; but you should not use this fact.)</p>
     *
     * @param ifd               IFD to write in the output stream.
     * @param updateIFDLinkages see comments above.
     * @throws IOException in a case of any I/O errors.
     */
    public void writeIFDAt(TiffIFD ifd, Long startOffset, boolean updateIFDLinkages) throws IOException {
        synchronized (fileLock) {
            checkVirginFile();
            if (startOffset == null) {
                appendFileUntilEvenLength();
                startOffset = out.length();
            }
            if (!bigTiff && startOffset > MAXIMAL_ALLOWED_32BIT_IFD_OFFSET) {
                throw new TiffException("Attempt to write too large TIFF file without big-TIFF mode: " +
                        "offset of new IFD will be " + startOffset + " > " + MAXIMAL_ALLOWED_32BIT_IFD_OFFSET);
            }
            ifd.setFileOffsetForWriting(startOffset);
            // - checks that startOffset is even and >= 0

            out.seek(startOffset);
            final Map<Integer, Object> sortedIFD = new TreeMap<>(ifd.map());
            final int numberOfEntries = sortedIFD.size();
            final int mainIFDLength = mainIFDLength(numberOfEntries);
            writeIFDNumberOfEntries(numberOfEntries);

            final long positionOfNextOffset = writeIFDEntries(sortedIFD, startOffset, mainIFDLength);

            final long previousPositionOfLastIFDOffset = positionOfLastIFDOffset;
            // - save it, because it will be updated in writeIFDNextOffsetAt
            writeIFDNextOffsetAt(ifd, positionOfNextOffset, updateIFDLinkages);
            if (updateIFDLinkages && !ifdOffsets.contains(startOffset)) {
                // - Only if it is really newly added IFD!
                // If this offset is already contained in the list, attempt to link to it
                // will probably lead to infinite loop of IFDs.
                writeIFDOffsetAt(startOffset, previousPositionOfLastIFDOffset, false);
                ifdOffsets.add(startOffset);
            }
        }
    }

    /**
     * Rewrites the offset, stored in the file at the {@link #positionOfLastIFDOffset()},
     * with the specified value.
     * This method is useful if you want to organize the sequence of IFD inside the file manually,
     * without automatic updating IFD linkage.
     *
     * @param nextLastIFDOffset new last IFD offset.
     * @throws IOException in a case of any I/O errors.
     */
    public void rewritePreviousLastIFDOffset(long nextLastIFDOffset) throws IOException {
        synchronized (fileLock) {
            if (nextLastIFDOffset < 0) {
                throw new IllegalArgumentException("Negative next last IFD offset " + nextLastIFDOffset);
            }
            if (positionOfLastIFDOffset < 0) {
                throw new IllegalStateException("Writing to this TIFF file is not started yet");
            }
            writeIFDOffsetAt(nextLastIFDOffset, positionOfLastIFDOffset, false);
            // - last argument is not important: positionOfLastIFDOffset will not change in any case
        }
    }


    public void writeTile(TiffTile tile) throws IOException {
        encode(tile);
        writeEncodedTile(tile, true);
    }

    public int writeTiles(Collection<TiffTile> tiles) throws IOException {
        return writeTiles(tiles, tile -> true);
    }

    public int writeCompletedTiles(Collection<TiffTile> tiles) throws IOException {
        return writeTiles(tiles, TiffTile::isCompleted);
    }

    public int writeTiles(Collection<TiffTile> tiles, Predicate<TiffTile> needToWrite) throws IOException {
        Objects.requireNonNull(tiles, "Null tiles");
        Objects.requireNonNull(needToWrite, "Null needToWrite");
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (TiffTile tile : tiles) {
            if (needToWrite.test(tile)) {
                writeTile(tile);
                count++;
                sizeInBytes += tile.getSizeInBytes();
            }
        }
        long t2 = debugTime();
        logTiles(tiles, "middle", "encoded/wrote", count, sizeInBytes, t1, t2);
        return count;
    }

    public void writeEncodedTile(TiffTile tile, boolean freeAfterWriting) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            return;
        }
        long t1 = debugTime();
        synchronized (fileLock) {
            checkVirginFile();
            TiffTileIO.writeToEnd(tile, out, freeAfterWriting, !bigTiff);
        }
        long t2 = debugTime();
        timeWriting += t2 - t1;
    }

    public List<TiffTile> updateSamples(TiffMap map, byte[] samples, long fromX, long fromY, long sizeX, long sizeY) {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        assert fromX == (int) fromX && fromY == (int) fromY && sizeX == (int) sizeX && sizeY == (int) sizeY;
        return updateSamples(map, samples, (int) fromX, (int) fromY, (int) sizeX, (int) sizeY);
    }

    public List<TiffTile> updateSamples(TiffMap map, byte[] samples, int fromX, int fromY, int sizeX, int sizeY) {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        TiffTools.checkRequestedAreaInArray(samples, sizeX, sizeY, map.totalBitsPerPixel());
        List<TiffTile> updatedTiles = new ArrayList<>();
        if (sizeX == 0 || sizeY == 0) {
            // - if no pixels are updated, no need to expand the map and to check correct expansion
            return updatedTiles;
        }
        final int toX = fromX + sizeX;
        final int toY = fromY + sizeY;
        map.expandDimensions(toX, toY);

        final int mapTileSizeX = map.tileSizeX();
        final int mapTileSizeY = map.tileSizeY();
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int samplesPerPixel = map.tileSamplesPerPixel();
        final long bitsPerSample = map.bitsPerSample();
        final long bitsPerPixel = map.tileBitsPerPixel();
        // - "long" here leads to stricter requirements later on

        final int minXIndex = Math.max(0, TiffReader.divFloor(fromX, mapTileSizeX));
        final int minYIndex = Math.max(0, TiffReader.divFloor(fromY, mapTileSizeY));
        if (minXIndex >= map.gridTileCountX() || minYIndex >= map.gridTileCountY()) {
            throw new AssertionError("Map was not expanded/checked properly: minimal tile index (" +
                    minXIndex + "," + minYIndex + ") is out of tile grid 0<=x<" +
                    map.gridTileCountX() + ", 0<=y<" + map.gridTileCountY() + "; map: " + map);
        }
        final int maxXIndex = Math.min(map.gridTileCountX() - 1, TiffReader.divFloor(toX - 1, mapTileSizeX));
        final int maxYIndex = Math.min(map.gridTileCountY() - 1, TiffReader.divFloor(toY - 1, mapTileSizeY));
        if (minYIndex > maxYIndex || minXIndex > maxXIndex) {
            // - possible when fromX < 0 or fromY < 0
            return updatedTiles;
        }

        final long tileChunkedRowSizeInBits = (long) mapTileSizeX * bitsPerPixel;
        final long samplesChunkedRowSizeInBits = (long) sizeX * bitsPerPixel;
        final long tileOneChannelRowSizeInBits = (long) mapTileSizeX * bitsPerSample;
        final long samplesOneChannelRowSizeInBits = (long) sizeX * bitsPerSample;

        final boolean sourceInterleaved = isSourceProbablyInterleaved(map);
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            // - for a rare case PlanarConfiguration=2 (RRR...GGG...BBB...)
            for (int yIndex = minYIndex; yIndex <= maxYIndex; yIndex++) {
                final int tileStartY = Math.max(yIndex * mapTileSizeY, fromY);
                final int fromYInTile = tileStartY % mapTileSizeY;
                final int yDiff = tileStartY - fromY;

                for (int xIndex = minXIndex; xIndex <= maxXIndex; xIndex++) {
                    final int tileStartX = Math.max(xIndex * mapTileSizeX, fromX);
                    final int fromXInTile = tileStartX % mapTileSizeX;
                    final int xDiff = tileStartX - fromX;

                    final TiffTile tile = map.getOrNewMultiplane(p, xIndex, yIndex);
                    tile.checkReadyForNewDecodedData(false);
                    tile.cropToMap(true);
                    // - In stripped image, we should correct the height of the last row.
                    // It is important for writing: without this correction, GIMP and other libtiff-based programs
                    // will report about an error (see libtiff, tif_jpeg.c, assigning segment_width/segment_height)
                    // However, if tiling is requested via TILE_WIDTH/TILE_LENGTH tags, we SHOULD NOT do this.
                    tile.fillEmpty(tileInitializer);
                    final byte[] data = tile.getDecodedData();

                    final int tileSizeX = tile.getSizeX();
                    final int tileSizeY = tile.getSizeY();
                    final int sizeXInTile = Math.min(toX - tileStartX, tileSizeX - fromXInTile);
                    assert sizeXInTile > 0 : "sizeXInTile=" + sizeXInTile;
                    final int sizeYInTile = Math.min(toY - tileStartY, tileSizeY - fromYInTile);
                    assert sizeYInTile > 0 : "sizeYInTile=" + sizeYInTile;
                    tile.reduceUnsetInTile(fromXInTile, fromYInTile, sizeXInTile, sizeYInTile);

                    // Tile must be interleaved always (RGBRGB...).
                    // A) planarSeparated=false, autoInterleave=false:
                    //      source pixels should be RGBRGB..., tile also will be RGBRGB...
                    // B) planarSeparated=false, autoInterleave=true:
                    //      source pixels are RRR...GGG..BBB..., every tile will also be RRR...GGG..BBB...
                    //      (will be interleaved later by tile.interleaveSamples() call)
                    // C) planarSeparated=true, autoInterleave is ignored:
                    //      source pixels are RRR...GGG..BBB..., we will have separate RRR tiles, GGG tiles, BBB tiles
                    //      (actually each tile is monochrome).
                    if (sourceInterleaved) {
//                        System.out.printf("!!!Chunked: %d%n", samplesPerPixel);
                        // - Case A: source data are already interleaved (like RGBRGB...): maybe, external code
                        // prefers to use interleaved form, for example, OpenCV library.
                        // Here tile will be actually interleaved directly after this method;
                        // we'll need to inform it about this fact (by setInterleaved(true)) later in encode().
                        final long partSizeXInBits = (long) sizeXInTile * bitsPerPixel;
                        long tOffset = ((long) fromYInTile * tileSizeX + fromXInTile) * bitsPerPixel;
                        long sOffset = ((long) yDiff * sizeX + xDiff) * bitsPerPixel;
                        for (int i = 0; i < sizeYInTile; i++) {
                            PackedBitArraysPer8.copyBitsNoSync(data, tOffset, samples, sOffset, partSizeXInBits);
                            tOffset += tileChunkedRowSizeInBits;
                            sOffset += samplesChunkedRowSizeInBits;
                        }
                    } else {
//                        System.out.printf("!!!Separate: %d%n", samplesPerPixel);
                        // - Source data are separated to channel planes: standard form, more convenient for image
                        // processing; this form is used for results of TiffReader by default (unless
                        // you specify another behaviour by setInterleaveResults method).
                        // Here are 2 possible cases:
                        //      B) planarSeparated=false (most typical): results in the file should be interleaved;
                        // we must prepare a single tile, but with SEPARATED data (they will be interleaved later);
                        //      C) planarSeparated=true (rare): for 3 channels (RGB) we must prepare 3 separate tiles;
                        // in this case samplesPerPixel=1.
                        final long partSizeXInBits = (long) sizeXInTile * bitsPerSample;
                        for (int s = 0; s < samplesPerPixel; s++) {
                            final int tileFirst = ((s * tileSizeY) + fromYInTile) * tileSizeX + fromXInTile;
                            final int samplesFirst = ((p + s) * sizeY + yDiff) * sizeX + xDiff;
                            long tOffset = (long) tileFirst * bitsPerSample;
                            long sOffset = (long) samplesFirst * bitsPerSample;
                            for (int i = 0; i < sizeYInTile; i++) {
                                PackedBitArraysPer8.copyBitsNoSync(data, tOffset, samples, sOffset, partSizeXInBits);
                                tOffset += tileOneChannelRowSizeInBits;
                                sOffset += samplesOneChannelRowSizeInBits;
                            }
                        }
                    }
                    updatedTiles.add(tile);
                }
            }
        }
        return updatedTiles;
    }

    public List<TiffTile> updateJavaArray(
            TiffMap map,
            Object samplesArray,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY) {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final Class<?> elementType = samplesArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified samplesArray is not actual an array: " +
                    "it is " + samplesArray.getClass());
        }
        if (elementType != map.elementType()) {
            throw new IllegalArgumentException("Invalid element type of samples array: " + elementType +
                    ", but the specified TIFF map stores " + map.elementType() + " elements");
        }
        final byte[] samples = TiffTools.javaArrayToBytes(samplesArray,
                (long) sizeX * (long) sizeY, isLittleEndian());
        return updateSamples(map, samples, fromX, fromY, sizeX, sizeY);
    }

    public List<TiffTile> updateMatrix(TiffMap map, Matrix<? extends PArray> matrix, int fromX, int fromY) {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(matrix, "Null matrix");
        final boolean sourceInterleaved = isSourceProbablyInterleaved(map);
        final Class<?> elementType = matrix.elementType();
        if (elementType != map.elementType()) {
            throw new IllegalArgumentException("Invalid element type of the matrix: " + elementType +
                    ", although the specified TIFF map stores " + map.elementType() + " elements");
        }
        if (matrix.dimCount() != 3 && !(matrix.dimCount() == 2 && map.numberOfChannels() == 1)) {
            throw new IllegalArgumentException("Illegal number of matrix dimensions " + matrix.dimCount() +
                    ": it must be 3-dimensional dimX*dimY*C, " +
                    "where C is the number of channels (z-dimension), " +
                    "or 3-dimensional C*dimX*dimY for interleaved case, " +
                    "or may be 2-dimensional in a case of monochrome TIFF image");
        }
        final int dimChannelsIndex = sourceInterleaved ? 0 : 2;
        final long numberOfChannels = matrix.dim(dimChannelsIndex);
        final long sizeX = matrix.dim(sourceInterleaved ? 1 : 0);
        final long sizeY = matrix.dim(sourceInterleaved ? 2 : 1);
        if (numberOfChannels != map.numberOfChannels()) {
            throw new IllegalArgumentException("Invalid number of channels in the matrix: " + numberOfChannels +
                    " (matrix " + matrix.dim(0) + "*" + matrix.dim(1) + "*" + matrix.dim(2) + "), " +
                    (matrix.dim(2 - dimChannelsIndex) == map.numberOfChannels() ?
                            "probably because of invalid interleaving mode: TIFF image is " +
                                    (sourceInterleaved ? "" : "NOT ") + "interleaved" :
                            "because the specified TIFF map stores " + map.numberOfChannels() + " channels"));
        }
        final byte[] samples = TiffTools.arrayToBytes(matrix.array(), isLittleEndian());
        return updateSamples(map, samples, fromX, fromY, sizeX, sizeY);
    }

    public void encode(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            return;
        }
        tile.checkStoredNumberOfPixels();
        long t1 = debugTime();
        prepareDecodedTileForEncoding(tile);
        long t2 = debugTime();

        final TagCompression compression = TagCompression.valueOfCodeOrNull(tile.ifd().getCompressionCode());
        TiffCodec codec = null;
        if (!enforceUseExternalCodec && compression != null) {
            codec = compression.codec();
            // - we are sure that this codec does not require SCIFIO context
        }
        TiffCodec.Options options = buildOptions(tile);
        long t3 = debugTime();

        byte[] data = tile.getDecodedData();
        if (codec != null) {
            options = compression.customizeWriting(tile, options);
            if (codec instanceof TiffCodec.Timing timing) {
                timing.setTiming(TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG);
                timing.clearTiming();
            }
            final byte[] encodedData = codec.compress(data, options);
            tile.setEncodedData(encodedData);
        } else {
            Object externalOptions = buildExternalOptions(tile, options);
            final byte[] encodedData = compressExternalFormat(tile, externalOptions);
            tile.setEncodedData(encodedData);
        }
        TiffTools.reverseFillOrderIfRequested(tile);
        long t4 = debugTime();

        timePreparingEncoding += t2 - t1;
        timeCustomizingEncoding += t3 - t2;
        timeEncoding += t4 - t3;
        if (codec instanceof TiffCodec.Timing timing) {
            timeEncodingMain += timing.timeMain();
            timeEncodingBridge += timing.timeBridge();
            timeEncodingAdditional += timing.timeAdditional();
        } else {
            timeEncodingMain += t4 - t3;
        }
    }

    public void encode(TiffMap map) throws TiffException {
        encode(map, null);
    }

    public void correctIFDForWriting(TiffIFD ifd) throws TiffException {
        correctIFDForWriting(ifd, smartIFDCorrection);
    }

    public void correctIFDForWriting(TiffIFD ifd, boolean smartCorrection) throws TiffException {
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        if (!ifd.containsKey(Tags.BITS_PER_SAMPLE)) {
            ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8});
            // - Default value of BitsPerSample is 1 bit/pixel, but it is a rare case,
            // not supported at all by SCIFIO library FormatTools; so, we set another default 8 bits/pixel
            // Note: we do not change SAMPLE_FORMAT tag here!
        }
        final TiffSampleType sampleType;
        try {
            sampleType = ifd.sampleType();
        } catch (TiffException e) {
            throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                    "requested combination of number of bits per sample and sample format is not supported: " +
                    e.getMessage());
        }
        if (!smartCorrection) {
            final OptionalInt optionalBits = ifd.tryEqualBitDepth();
            if (optionalBits.isEmpty()) {
                throw new UnsupportedTiffFormatException("Cannot write TIFF, because requested number of " +
                        "bits per samples is unequal for different channels: " +
                        Arrays.toString(ifd.getBitsPerSample()) + " (this variant is not supported)");
            }
            final int bits = optionalBits.getAsInt();
            final boolean usualPrecision = sampleType.isBinary() ||
                    bits == 8 || bits == 16 || bits == 32 || bits == 64;
            // - bits == 1 is possible also for more than 1 channel, which is recognized as non-binary UINT8
            if (!usualPrecision) {
                throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                        "requested number of bits per sample is not supported for writing: " +
                        Arrays.toString(ifd.getBitsPerSample()) + " bits");
            }
            if (sampleType == TiffSampleType.FLOAT && bits != 32) {
                throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                        "requested number of bits per sample is not supported: " +
                        bits + " bits for floating-point precision");
            }
        } else {
            ifd.putSampleType(sampleType);
        }

        if (!ifd.containsKey(Tags.COMPRESSION)) {
            ifd.put(Tags.COMPRESSION, TagCompression.UNCOMPRESSED.code());
            // - We prefer explicitly specify this case
        }
        final TagCompression compression = ifd.optCompression().orElse(null);

        final TagPhotometricInterpretation suggestedPhotometric =
                ifd.containsKey(Tags.PHOTOMETRIC_INTERPRETATION) ? ifd.getPhotometricInterpretation() : null;
        TagPhotometricInterpretation newPhotometric = suggestedPhotometric;
        // - note: it is possible, that we DO NOT KNOW this newPhotometric interpretation;
        // in this case, newPhotometric will be UNKNOWN, but we should not prevent writing such image
        // in simple formats like UNCOMPRESSED or LZW: maybe, the client knows how to process it
        if (compression == TagCompression.JPEG) {
            if (samplesPerPixel != 1 && samplesPerPixel != 3) {
                throw new TiffException("JPEG compression for " + samplesPerPixel + " channels is not supported");
            }
            if (newPhotometric == null) {
                newPhotometric = samplesPerPixel == 1 ? TagPhotometricInterpretation.BLACK_IS_ZERO :
                        (preferRGB || !ifd.isChunked()) ?
                                TagPhotometricInterpretation.RGB : TagPhotometricInterpretation.Y_CB_CR;
            } else {
                checkPhotometricInterpretation(newPhotometric,
                        samplesPerPixel == 1 ? EnumSet.of(TagPhotometricInterpretation.BLACK_IS_ZERO) :
                                !enforceUseExternalCodec ?
                                        EnumSet.of(TagPhotometricInterpretation.Y_CB_CR,
                                                TagPhotometricInterpretation.RGB) :
                                        EnumSet.of(TagPhotometricInterpretation.Y_CB_CR),
                        "JPEG " + samplesPerPixel + "-channel image");
            }
        } else if (samplesPerPixel == 1) {
            final boolean hasColorMap = ifd.containsKey(Tags.COLOR_MAP);
            if (newPhotometric == null) {
                newPhotometric = hasColorMap ?
                        TagPhotometricInterpretation.RGB_PALETTE :
                        TagPhotometricInterpretation.BLACK_IS_ZERO;
            } else {
                if (newPhotometric == TagPhotometricInterpretation.RGB_PALETTE && !hasColorMap) {
                    throw new TiffException("Cannot write TIFF image: newPhotometric interpretation \"" +
                            newPhotometric.prettyName() + "\" requires also \"ColorMap\" tag");
                }
                checkPhotometricInterpretation(newPhotometric,
                        EnumSet.of(TagPhotometricInterpretation.BLACK_IS_ZERO,
                                TagPhotometricInterpretation.RGB_PALETTE),
                        samplesPerPixel + "-channel image");
            }
        } else if (samplesPerPixel == 3) {
            if (newPhotometric == null) {
                newPhotometric = TagPhotometricInterpretation.RGB;
            } else {
                if (ifd.isStandardYCbCrNonJpeg()) {
                    if (!smartCorrection) {
                        throw new UnsupportedTiffFormatException("Cannot write TIFF: encoding YCbCr " +
                                "photometric interpretation is not supported for compression \"" +
                                (compression == null ? "??? : " : compression.prettyName()) + "\"");
                        // - compression == null is added just in case (it is impossible when isStandardYCbCrNonJpeg,
                        // excepting the case of correction from a parallel thread)
                    } else {
                        // - In this case, we automatically decode YCbCr into RGB while reading;
                        // we cannot encode pixels back to YCbCr,
                        // so, it would be better to change newPhotometric interpretation to RGB.
                        // Note that for JPEG we have no this problem: we CAN encode JPEG as YCbCr.
                        // For other models (like CMYK or CIE Lab), we ignore newPhotometric interpretation
                        // and suppose that the user herself prepared channels in the necessary model.
                        newPhotometric = TagPhotometricInterpretation.RGB;
                    }
                }
            }
        } else {
            if (newPhotometric == null) {
                if (samplesPerPixel == 4) {
                    // - probably RGBA
                    newPhotometric = TagPhotometricInterpretation.RGB;
                }
                // else we stay IFD without photometric interpretation: incorrect for good TIFF,
                // but better than senseless interpretation
            }
        }
        if (newPhotometric != suggestedPhotometric) {
            ifd.putPhotometricInterpretation(newPhotometric);
        }

        ifd.setLittleEndian(out.isLittleEndian());
        // - will be used, for example, in getCompressionCodecOptions
        ifd.setBigTiff(bigTiff);
        // - not used, but helps to provide better TiffIFD.toString
    }

    public TiffIFD newIFD(boolean tiled) {
        final TiffIFD ifd = new TiffIFD();
        ifd.putCompression(TagCompression.UNCOMPRESSED);
        if (tiled) {
            ifd.putDefaultTileSizes();
        } else {
            ifd.putDefaultStripSize();
        }
        return ifd;
    }

    /**
     * Starts writing new IFD image.
     *
     * @param ifd       newly created and probably customized IFD.
     * @param resizable if <tt>true</tt>, IFD dimensions may not be specified yet.
     * @return map for writing further data.
     */
    public TiffMap newMap(TiffIFD ifd, boolean resizable) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (ifd.isFrozen()) {
            throw new IllegalStateException("IFD is already frozen for usage while writing TIFF; " +
                    "probably you called this method twice");
        }
        correctIFDForWriting(ifd);
        final TiffMap map = new TiffMap(ifd, resizable);
        map.buildGrid();
        // - useful to perform loops on all tiles, especially in non-resizable case
        ifd.removeNextIFDOffset();
        ifd.removeDataPositioning();
        if (resizable) {
            ifd.removeImageDimensions();
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs
        return map;
    }

    public TiffMap newMap(TiffIFD ifd) throws TiffException {
        return newMap(ifd, false);
    }

    /**
     * Starts overwriting existing IFD image.
     *
     * <p>Usually you should avoid usage this method without necessity: though it allows modify some existing tiles,
     * but all newly updated tiles will written at the file end, and the previously occupied space in the file
     * will be lost. This method may be suitable if you need to perform little correction in 1-2 tiles of
     * very large TIFF without full recompression of all its tiles.</p>
     *
     * <p>Note: this method does not remove information about tile/strip offsets and byte counts. So, you can
     * read some tiles from this IFD via {@link TiffReader} class (it is important for tiles, that you neeed to
     * partially fill, but partially load from the old file).</p>
     *
     * @param ifd IFD of some existing image, probably loaded from the current TIFF file.
     * @return map for writing further data.
     */
    public TiffMap existingMap(TiffIFD ifd) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        correctIFDForWriting(ifd);
        final TiffMap map = new TiffMap(ifd);
        final long[] offsets = ifd.cachedTileOrStripOffsets();
        final long[] byteCounts = ifd.cachedTileOrStripByteCounts();
        assert offsets != null;
        assert byteCounts != null;
        map.buildGrid();
        if (offsets.length < map.numberOfTiles() || byteCounts.length < map.numberOfTiles()) {
            throw new ConcurrentModificationException("Strange length of tile offsets " + offsets.length +
                    " or byte counts " + byteCounts.length);
            // - should not occur: it is checked in getTileOrStripOffsets/getTileOrStripByteCounts methods
            // (only possible way is modification from parallel thread)
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs
        int k = 0;
        for (TiffTile tile : map.tiles()) {
            tile.setStoredDataFileRange(offsets[k], (int) byteCounts[k]);
            // - we "tell" that all tiles already exist in the file;
            // note we can use index k, because buildGrid() method, called above for an empty map,
            // provided correct tiles order
            tile.removeUnset();
            k++;
        }
        return map;
    }

    /**
     * Prepare writing new image with known fixed sizes.
     * This method writes image header (IFD) to the end of the TIFF file,
     * so it will be placed before actually written data: it helps
     * to improve performance of future reading this file.
     *
     * <p>Note: this method does nothing if the image is {@link TiffMap#isResizable() resizable}
     * or if this action is disabled by {@link #setWritingForwardAllowed(boolean) setWritingForwardAllowed(false)}
     * call.
     * In this case, IFD will be written at the final stage ({@link #complete(TiffMap)} method).
     *
     * @param map map, describing the image.
     */
    public void writeForward(TiffMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        if (!writingForwardAllowed || map.isResizable()) {
            return;
        }
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        map.ifd().updateDataPositioning(offsets, byteCounts);
        final TiffIFD ifd = map.ifd();
        if (!ifd.hasFileOffsetForWriting()) {
            writeIFDAtFileEnd(ifd, false);
        }
    }

    public int complete(final TiffMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        final boolean resizable = map.isResizable();
        map.checkTooSmallDimensionsForCurrentGrid();

        encode(map, "completion");
        // - encode tiles, which are not encoded yet

        final TiffIFD ifd = map.ifd();
        if (resizable) {
            ifd.updateImageDimensions(map.dimX(), map.dimY());
        }

        final int count = completeWritingMap(map);
        map.cropAllUnset();
        appendFileUntilEvenLength();
        // - not absolutely necessary, but good idea

        if (ifd.hasFileOffsetForWriting()) {
            // - usually it means that we did call writeForward
            rewriteIFD(ifd, true);
        } else {
            writeIFDAtFileEnd(ifd, true);
        }

        seekToEnd();
        // - This seeking to file end is not necessary, but can help to avoid accidental bugs
        // (this is much better than keeping file offset in the middle of the last image
        // between IFD and newly written TIFF tiles).
        return count;
    }

    public void writeSamples(final TiffMap map, byte[] samples) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        map.checkZeroDimensions();
        writeSamples(map, samples, 0, 0, map.dimX(), map.dimY());
    }

    public void writeSamples(TiffMap map, byte[] samples, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");

        clearTime();
        long t1 = debugTime();
        updateSamples(map, samples, fromX, fromY, sizeX, sizeY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        complete(map);
        logWritingMatrix(map, "byte samples", sizeX, sizeY, t1, t2, t3, t4);

    }

    public void writeJavaArray(TiffMap map, Object samplesArray) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        map.checkZeroDimensions();
        writeJavaArray(map, samplesArray, 0, 0, map.dimX(), map.dimY());
    }

    public void writeJavaArray(TiffMap map, Object samplesArray, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        clearTime();
        long t1 = debugTime();
        updateJavaArray(map, samplesArray, fromX, fromY, sizeX, sizeY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        complete(map);
        logWritingMatrix(map, "pixel array", sizeX, sizeY, t1, t2, t3, t4);

    }

    /**
     * Writes the matrix at the position (0,0).
     *
     * <p>Note: unlike {@link #writeJavaArray(TiffMap, Object)} and {@link #writeSamples(TiffMap, byte[])},
     * this method always use the actual sizes of the passed matrix and, so, <i>does not require</i>
     * the map to have correct non-zero dimensions (a situation, possible for resizable maps).</p>
     *
     * @param map    TIFF map.
     * @param matrix matrix of pixels.
     * @throws TiffException in a case of invalid TIFF IFD.
     * @throws IOException   in a case of any I/O errors.
     */
    public void writeMatrix(TiffMap map, Matrix<? extends PArray> matrix) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        writeMatrix(map, matrix, 0, 0);
    }

    public void writeMatrix(TiffMap map, Matrix<? extends PArray> matrix, int fromX, int fromY) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(matrix, "Null matrix");
        clearTime();
        long t1 = debugTime();
        updateMatrix(map, matrix, fromX, fromY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        complete(map);
        logWritingMatrix(map, "matrix", matrix, t1, t2, t3, t4);
    }

    public void fillEmptyTile(TiffTile tiffTile) {
        if (byteFiller != 0) {
            // - Java-arrays are automatically filled by zero
            Arrays.fill(tiffTile.getDecodedData(), byteFiller);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            out.close();
        }
    }

    protected Object buildExternalOptions(TiffTile tile, TiffCodec.Options options) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(options, "Null options");
        if (!SCIFIOBridge.isScifioInstalled()) {
            throw new UnsupportedTiffFormatException("TIFF compression with code " + tile.ifd().getCompressionCode() +
                    " cannot be processed");
        }
        return options.toOldStyleOptions(SCIFIOBridge.codecOptionsClass());
    }

    protected byte[] compressExternalFormat(TiffTile tile, Object externalOptions) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(externalOptions, "Null externalOptions");
        final byte[] decodedData = tile.getDecodedData();
        final int compressionCode = tile.ifd().getCompressionCode();
        final Object scifio = scifio();
        if (scifio == null) {
            throw new IllegalStateException(
                    "Compression type " + compressionCode + " requires specifying non-null SCIFIO context");
        }
        final Object compression;
        try {
            compression = SCIFIOBridge.createTiffCompression(compressionCode);
        } catch (InvocationTargetException e) {
            throw new UnsupportedTiffFormatException("TIFF compression code " + compressionCode +
                    " is unknown and is not correctly recognized by the external SCIFIO subsystem", e);
        }
        try {
            final TiffIFD ifd = tile.ifd();
            final Class<?> scifioIFDClass = SCIFIOBridge.scifioIFDClass();
            final Map<Integer, Object> scifioIFD = SCIFIOBridge.createIFD(scifioIFDClass);
            scifioIFD.putAll(ifd.map());
            scifioIFD.put(0, ifd.isLittleEndian()); // IFD.LITTLE_ENDIAN
            scifioIFD.put(1, ifd.isBigTiff());      // IFD.BIG_TIFF
            scifioIFD.put(Tags.IMAGE_WIDTH, tile.getSizeX());
            scifioIFD.put(Tags.IMAGE_LENGTH, tile.getSizeY());
            // - correct dimensions (especially useful for resizable map, when dimensions are not set yet)
            externalOptions = SCIFIOBridge.getCompressionCodecOptions(compression, scifioIFD, externalOptions);
            return SCIFIOBridge.callCompress(scifio, compression, decodedData, externalOptions);
        } catch (InvocationTargetException e) {
            throw new TiffException("TIFF compression code " + compressionCode + " is unknown and " +
                    "cannot be correctly processed for compression by the external SCIFIO subsystem", e);
        }

    }

    protected void prepareDecodedTileForEncoding(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (autoInterleaveSource) {
            if (tile.isInterleaved()) {
                throw new IllegalArgumentException("Tile for encoding and writing to TIFF file must not be " +
                        "interleaved:: " + tile);
            }
            tile.interleaveSamples();
        } else {
            tile.setInterleaved(true);
            // - if not autoInterleave, we should suppose that the samples were already interleaved
        }
        TiffTools.packTiffBits(tile);

        // scifio.tiff().difference(tile.getDecodedData(), ifd);
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffTools.subtractPredictionIfRequested(tile);
    }

    Object scifio() {
        Object scifio = this.scifio;
        if (scifio == null) {
            this.scifio = scifio = SCIFIOBridge.createScifioFromContext(context);
        }
        return scifio;
    }

    private void clearTime() {
        timeWriting = 0;
        timeCustomizingEncoding = 0;
        timePreparingEncoding = 0;
        timeEncoding = 0;
        timeEncodingMain = 0;
        timeEncodingBridge = 0;
        timeEncodingAdditional = 0;
    }

    private void checkVirginFile() throws IOException {
        if (positionOfLastIFDOffset < 0) {
            throw new IllegalStateException("TIFF file is not yet created / opened for writing");
        }
        final boolean exists = out.exists();
        if (!exists || out.length() < (bigTiff ? 16 : 8)) {
            // - very improbable, but can occur as a result of direct operations with output stream
            throw new IllegalStateException(
                    (exists ?
                            "Existing TIFF file is too short (" + out.length() + " bytes)" :
                            "TIFF file does not exists yet") +
                            ": probably file header was not written correctly by startWriting() method");
        }
    }

    private int mainIFDLength(int numberOfEntries) {
        final int numberOfEntriesLimit = bigTiff ? TiffReader.MAX_NUMBER_OF_IFD_ENTRIES : 65535;
        if (numberOfEntries > numberOfEntriesLimit) {
            throw new IllegalStateException("Too many IFD entries: " + numberOfEntries + " > " + numberOfEntriesLimit);
            // - theoretically BigTIFF allows to write more, but we prefer to make some restriction and
            // guarantee 32-bit number of bytes
        }
        final int bytesPerEntry = bigTiff ? TiffTools.BIG_TIFF_BYTES_PER_ENTRY : TiffTools.BYTES_PER_ENTRY;
        return (bigTiff ? 8 + 8 : 2 + 4) + bytesPerEntry * numberOfEntries;
        // - includes starting number of entries (2 or 8) and ending next offset (4 or 8)
    }

    private boolean isSourceProbablyInterleaved(TiffMap map) {
        return !map.isPlanarSeparated() && !this.autoInterleaveSource;
    }

    private void writeIFDNumberOfEntries(int numberOfEntries) throws IOException {
        if (bigTiff) {
            out.writeLong(numberOfEntries);
        } else {
            writeUnsignedShort(out, numberOfEntries);
        }
    }

    private long writeIFDEntries(Map<Integer, Object> ifd, long startOffset, int mainIFDLength) throws IOException {
        final long afterMain = startOffset + mainIFDLength;
        final BytesLocation bytesLocation = new BytesLocation(0, "memory-buffer");
        final long positionOfNextOffset;
        try (final DataHandle<Location> extraHandle = TiffTools.getBytesHandle(bytesLocation)) {
            for (final Map.Entry<Integer, Object> e : ifd.entrySet()) {
                writeIFDValueAtCurrentPosition(extraHandle, afterMain, e.getKey(), e.getValue());
            }

            positionOfNextOffset = out.offset();
            writeOffset(TiffIFD.LAST_IFD_OFFSET);
            // - not too important: will be rewritten in writeIFDNextOffset
            final int extraLength = (int) extraHandle.offset();
            extraHandle.seek(0L);
            DataHandles.copy(extraHandle, out, extraLength);
            appendUntilEvenPosition(out);
        }
        return positionOfNextOffset;
    }

    /**
     * Writes the given IFD value to the {@link #stream() main output stream}, excepting "extra" data,
     * which are written into the specified <tt>extraBuffer</tt>. After calling this method, you
     * should copy full content of <tt>extraBuffer</tt> into the main stream at the position,
     * specified by the 2nd argument; {@link #rewriteIFD(TiffIFD, boolean)} method does it automatically.
     *
     * <p>Here "extra" data means all data, for which IFD contains their offsets instead of data itself,
     * like arrays or text strings. The "main" data is 12-byte IFD record (20-byte for Big-TIFF),
     * which is written by this method into the main output stream from its current position.
     *
     * @param extraBuffer              buffer to which "extra" IFD information should be written.
     * @param bufferOffsetInResultFile position of "extra" data in the result TIFF file =
     *                                 bufferOffsetInResultFile +
     *                                 offset of the written "extra" data inside <tt>extraBuffer</tt>>;
     *                                 for example, this argument may be a position directly after
     *                                 the "main" content (sequence of 12/20-byte records).
     * @param tag                      IFD tag to write.
     * @param value                    IFD value to write.
     */
    private void writeIFDValueAtCurrentPosition(
            final DataHandle<Location> extraBuffer,
            final long bufferOffsetInResultFile,
            final int tag,
            Object value) throws IOException {
        extraBuffer.setLittleEndian(isLittleEndian());
        appendUntilEvenPosition(extraBuffer);

        // convert singleton objects into arrays, for simplicity
        if (value instanceof Short) {
            value = new short[]{(Short) value};
        } else if (value instanceof Integer) {
            value = new int[]{(Integer) value};
        } else if (value instanceof Long) {
            value = new long[]{(Long) value};
        } else if (value instanceof TagRational) {
            value = new TagRational[]{(TagRational) value};
        } else if (value instanceof Float) {
            value = new float[]{(Float) value};
        } else if (value instanceof Double) {
            value = new double[]{(Double) value};
        }

        final boolean bigTiff = this.bigTiff;
        final int dataLength = bigTiff ? 8 : 4;

        // write directory entry to output buffers
        writeUnsignedShort(out, tag);
        if (value instanceof byte[] q) {
            out.writeShort(TagTypes.UNDEFINED);
            // - Most probable type. Maybe in future we will support here some algorithm,
            // determining necessary type on the base of the tag value.
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength) {
                for (byte byteValue : q) {
                    out.writeByte(byteValue);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                extraBuffer.write(q);
            }
        } else if (value instanceof short[] q) { // suppose BYTE (unsigned 8-bit)
            out.writeShort(TagTypes.BYTE);
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength) {
                for (short s : q) {
                    out.writeByte(s);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (short shortValue : q) {
                    extraBuffer.writeByte(shortValue);
                }
            }
        } else if (value instanceof String) { // suppose ASCII
            final char[] q = ((String) value).toCharArray();
            out.writeShort(TagTypes.ASCII);
            writeIntOrLong(out, q.length + 1);
            // - with concluding zero byte
            if (q.length < dataLength) {
                for (char c : q) {
                    writeUnsignedByte(out, c);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (char charValue : q) {
                    writeUnsignedByte(extraBuffer, charValue);
                }
                extraBuffer.writeByte(0); // concluding zero byte
            }
        } else if (value instanceof int[] q) { // suppose SHORT (unsigned 16-bit)
            if (q.length == 1) {
                // - we should allow to use usual int values for 32-bit tags, to avoid a lot of obvious bugs
                final int v = q[0];
                if (v >= 0xFFFF) {
                    out.writeShort(TagTypes.LONG);
                    writeIntOrLong(out, q.length);
                    writeIntOrLong(out, v);
                    return;
                }
            }
            out.writeShort(TagTypes.SHORT);
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength / 2) {
                for (int intValue : q) {
                    writeUnsignedShort(out, intValue);
                }
                for (int i = q.length; i < dataLength / 2; i++) {
                    out.writeShort(0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (int intValue : q) {
                    extraBuffer.writeShort(intValue);
                }
            }
        } else if (value instanceof long[] q) { // suppose LONG (unsigned 32-bit) or LONG8 for BitTIFF
            if (AVOID_LONG8_FOR_ACTUAL_32_BITS && q.length == 1 && bigTiff) {
                // - note: inside TIFF, long[1] is saved in the same way as Long; we have a difference in Java only
                final long v = q[0];
                if (v == (int) v) {
                    // - it is probable for the following tags, if they are added
                    // manually via TiffIFD.put with "long" argument
                    switch (tag) {
                        case Tags.IMAGE_WIDTH,
                                Tags.IMAGE_LENGTH,
                                Tags.TILE_WIDTH,
                                Tags.TILE_LENGTH,
                                Tags.IMAGE_DEPTH,
                                Tags.ROWS_PER_STRIP,
                                Tags.NEW_SUBFILE_TYPE -> {
                            out.writeShort(TagTypes.LONG);
                            writeIntOrLong(out, q.length);
                            out.writeInt((int) v);
                            out.writeInt(0);
                            // - 4 bytes of padding until full length 20 bytes
                            return;
                        }
                    }
                }
            }
            final int type = bigTiff ? TagTypes.LONG8 : TagTypes.LONG;
            out.writeShort(type);
            writeIntOrLong(out, q.length);

            if (q.length <= 1) {
                for (int i = 0; i < q.length; i++) {
                    writeIntOrLong(out, q[0]);
                    // - q[0]: it is actually performed 0 or 1 times
                }
                for (int i = q.length; i < 1; i++) {
                    writeIntOrLong(out, 0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (long longValue : q) {
                    writeIntOrLong(extraBuffer, longValue);
                }
            }
        } else if (value instanceof TagRational[] q) {
            out.writeShort(TagTypes.RATIONAL);
            writeIntOrLong(out, q.length);
            if (bigTiff && q.length == 1) {
                out.writeInt((int) q[0].getNumerator());
                out.writeInt((int) q[0].getDenominator());
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (TagRational tagRational : q) {
                    extraBuffer.writeInt((int) tagRational.getNumerator());
                    extraBuffer.writeInt((int) tagRational.getDenominator());
                }
            }
        } else if (value instanceof float[] q) {
            out.writeShort(TagTypes.FLOAT);
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength / 4) {
                for (float floatValue : q) {
                    out.writeFloat(floatValue); // value
                    // - in old SCIFIO code, here was a bug (for a case bigTiff): q[0] was always written
                }
                for (int i = q.length; i < dataLength / 4; i++) {
                    out.writeInt(0); // padding
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (float floatValue : q) {
                    extraBuffer.writeFloat(floatValue);
                }
            }
        } else if (value instanceof double[] q) {
            out.writeShort(TagTypes.DOUBLE);
            writeIntOrLong(out, q.length);
            writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
            for (final double doubleValue : q) {
                extraBuffer.writeDouble(doubleValue);
            }
        } else if (!(value instanceof TiffIFD.UnsupportedTypeValue)) {
            // - for unsupported type, we don't know the sense of its valueOrOffset field; it is better to skip it
            throw new UnsupportedOperationException("Unknown IFD tag " + tag + " value type ("
                    + value.getClass().getSimpleName() + "): " + value);
        }
    }


    private void writeIFDNextOffsetAt(TiffIFD ifd, long positionToWrite, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        writeIFDOffsetAt(
                ifd.hasNextIFDOffset() ? ifd.getNextIFDOffset() : TiffIFD.LAST_IFD_OFFSET,
                positionToWrite,
                updatePositionOfLastIFDOffset);
    }

    private void encode(TiffMap map, String stage) throws TiffException {
        Objects.requireNonNull(map, "Null TIFF map");
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (TiffTile tile : map.tiles()) {
            if (!tile.isEncoded()) {
                encode(tile);
                count++;
                sizeInBytes += tile.getSizeInBytes();
            }
        }
        long t2 = debugTime();
        logTiles(map, stage, "encoded", count, sizeInBytes, t1, t2);
    }

    private int completeWritingMap(TiffMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        TiffTile filler = null;
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int gridTileCountY = map.gridTileCountY();
        final int gridTileCountX = map.gridTileCountX();
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (int p = 0, k = 0; p < numberOfSeparatedPlanes; p++) {
            for (int yIndex = 0; yIndex < gridTileCountY; yIndex++) {
                for (int xIndex = 0; xIndex < gridTileCountX; xIndex++, k++) {
                    TiffTileIndex tileIndex = map.multiplaneIndex(p, xIndex, yIndex);
                    TiffTile tile = map.getOrNew(tileIndex);
                    // - non-existing is created (empty) and saved in the map;
                    // this is necessary to inform the map about new data file range for this tile
                    // and to avoid twice writing it while twice calling "complete()" method
                    tile.cropToMap(true);
                    // - like in updateSamples
                    if (!tile.isEmpty()) {
                        writeEncodedTile(tile, true);
                        count++;
                        sizeInBytes += tile.getSizeInBytes();
                    }
                    if (tile.isStoredInFile()) {
                        offsets[k] = tile.getStoredDataFileOffset();
                        byteCounts[k] = tile.getStoredDataLength();
                    } else {
                        assert tile.isEmpty() : "writeEncodedTile() call above did not store data file offset!";
                        if (!missingTilesAllowed) {
                            if (!tile.equalSizes(filler)) {
                                // - usually performed once, maybe twice for stripped image (where last strip has smaller height)
                                // or even 2 * numberOfSeparatedPlanes times for plane-separated tiles
                                filler = new TiffTile(tileIndex).setEqualSizes(tile);
                                filler.fillEmpty(tileInitializer);
                                encode(filler);
                                writeEncodedTile(filler, false);
                                // - note: unlike usual tiles, the filler tile is written once,
                                // but its offset/byte-count are used many times!
                            }
                            offsets[k] = filler.getStoredDataFileOffset();
                            byteCounts[k] = filler.getStoredDataLength();
                            tile.copyStoredDataFileRange(filler);
                        }
                        // else (if missingTilesAllowed) offsets[k]/byteCounts[k] stay to be zero
                    }
                }
            }
        }
        map.ifd().updateDataPositioning(offsets, byteCounts);
        long t2 = debugTime();
        logTiles(map, "completion", "wrote", count, sizeInBytes, t1, t2);
        return count;
    }

    /**
     * Write the given value to the given RandomAccessOutputStream. If the
     * 'bigTiff' flag is set, then the value will be written as an 8 byte long;
     * otherwise, it will be written as a 4 byte integer.
     */
    private void writeIntOrLong(DataHandle<Location> handle, long value) throws IOException {
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

    private void writeIntOrLong(DataHandle<Location> handle, int value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            handle.writeInt(value);
        }
    }

    private static void writeUnsignedShort(DataHandle<Location> handle, int value) throws IOException {
        if (value < 0 || value > 0xFFFF) {
            throw new TiffException("Attempt to write 32-bit value as 16-bit: " + value);
        }
        handle.writeShort(value);
    }

    private static void writeUnsignedByte(DataHandle<Location> handle, int value) throws IOException {
        if (value < 0 || value > 0xFF) {
            throw new TiffException("Attempt to write 16/32-bit value as 8-bit: " + value);
        }
        handle.writeByte(value);
    }

    private void writeOffset(long offset) throws IOException {
        if (offset < 0) {
            throw new AssertionError("Illegal usage of writeOffset: negative offset " + offset);
        }
        if (bigTiff) {
            out.writeLong(offset);
        } else {
            if (offset > 0xFFFFFFF0L) {
                throw new TiffException("Attempt to write too large 64-bit offset as unsigned 32-bit: " + offset
                        + " > 2^32-16; such large files should be written in Big-TIFF mode");
            }
            out.writeInt((int) offset);
            // - masking by 0xFFFFFFFF is not needed: cast to (int) works properly also for 32-bit unsigned values
        }
    }

    private void writeIFDOffsetAt(long offset, long positionToWrite, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        synchronized (fileLock) {
            // - to be on the safe side (this synchronization is not necessary)
            final long savedPosition = out.offset();
            try {
                out.seek(positionToWrite);
                writeOffset(offset);
                if (updatePositionOfLastIFDOffset && offset == TiffIFD.LAST_IFD_OFFSET) {
                    positionOfLastIFDOffset = positionToWrite;
                }
            } finally {
                out.seek(savedPosition);
            }
        }
    }

    private void seekToEnd() throws IOException {
        synchronized (fileLock) {
            out.seek(out.length());
        }
    }

    private void appendFileUntilEvenLength() throws IOException {
        synchronized (fileLock) {
            seekToEnd();
            appendUntilEvenPosition(out);
        }
    }

    private static void appendUntilEvenPosition(DataHandle<Location> handle) throws IOException {
        if ((handle.offset() & 0x1) != 0) {
            handle.writeByte(0);
            // - Well-formed IFD requires even offsets
        }
    }

    @SuppressWarnings("RedundantThrows")
    private TiffCodec.Options buildOptions(TiffTile tile) throws TiffException {
        TiffCodec.Options result = this.codecOptions.clone();
        result.setSizes(tile.getSizeX(), tile.getSizeY());
        result.setBitsPerSample(tile.bitsPerSample());
        result.setNumberOfChannels(tile.samplesPerPixel());
        result.setSigned(tile.sampleType().isSigned());
        result.setFloatingPoint(tile.sampleType().isFloatingPoint());
        result.setLittleEndian(tile.isLittleEndian());
        result.setInterleaved(true);
        if (this.quality != null) {
            result.setQuality(this.quality);
        }
        return result;
    }

    private void logWritingMatrix(TiffMap map, String name, Matrix<?> matrix, long t1, long t2, long t3, long t4) {
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            final boolean sourceInterleaved = isSourceProbablyInterleaved(map);
            final long dimX = matrix.dim(sourceInterleaved ? 1 : 0);
            final long dimY = matrix.dim(sourceInterleaved ? 2 : 1);
            // - already checked that they are actually "int"
            logWritingMatrix(map, name, dimX, dimY, t1, t2, t3, t4);
        }
    }

    private void logTiles(
            Collection<TiffTile> tiles,
            String stage,
            String action,
            int count,
            long sizeInBytes,
            long t1,
            long t2) {
        logTiles(tiles.isEmpty() ? null : tiles.iterator().next().map(), stage, action, count, sizeInBytes, t1, t2);
    }

    private void logTiles(TiffMap map, String stage, String action, int count, long sizeInBytes, long t1, long t2) {
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            LOG.log(System.Logger.Level.DEBUG,
                    count == 0 ?
                            String.format(Locale.US,
                                    "%s%s %s no tiles in %.3f ms",
                                    getClass().getSimpleName(),
                                    stage == null ? "" : " (" + stage + " stage)",
                                    action,
                                    (t2 - t1) * 1e-6) :
                    String.format(Locale.US,
                            "%s%s %s %d tiles %dx%dx%d (%.3f MB) in %.3f ms, %.3f MB/s",
                            getClass().getSimpleName(),
                            stage == null ? "" : " (" + stage + " stage)",
                            action,
                            count, map.numberOfChannels(), map.tileSizeX(), map.tileSizeY(),
                            sizeInBytes / 1048576.0,
                            (t2 - t1) * 1e-6,
                            sizeInBytes / 1048576.0 / ((t2 - t1) * 1e-9)));
        }
    }

    private void logWritingMatrix(TiffMap map, String name, long dimX, long dimY, long t1, long t2, long t3, long t4) {
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            final long sizeInBytes = map.totalSizeInBytes();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s wrote %dx%dx%d %s (%.3f MB) in %.3f ms = " +
                            "%.3f conversion/copying data + %.3f writing IFD " +
                            "+ %.3f/%.3f encoding/writing " +
                            "(%.3f prepare, %.3f customize, %.3f encode: " +
                            "%.3f encode-main%s, " +
                            "%.3f write), %.3f MB/s",
                    getClass().getSimpleName(),
                    dimX, dimY, map.numberOfChannels(),
                    name,
                    sizeInBytes / 1048576.0,
                    (t5 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
                    (t4 - t3) * 1e-6, (t5 - t4) * 1e-6,
                    timePreparingEncoding * 1e-6,
                    timeCustomizingEncoding * 1e-6,
                    timeEncoding * 1e-6,
                    timeEncodingMain * 1e-6,
                    timeEncodingBridge + timeEncodingAdditional > 0 ?
                            String.format(Locale.US, " + %.3f encode-bridge + %.3f encode-additional",
                                    timeEncodingBridge * 1e-6,
                                    timeEncodingAdditional * 1e-6) :
                            "",
                    timeEncodingBridge * 1e-6,
                    timeEncodingAdditional * 1e-6,
                    timeWriting * 1e-6,
                    sizeInBytes / 1048576.0 / ((t5 - t1) * 1e-9)));
        }
    }

    private static void checkPhotometricInterpretation(
            TagPhotometricInterpretation photometricInterpretation,
            EnumSet<TagPhotometricInterpretation> allowed,
            String whatToWrite)
            throws TiffException {
        if (photometricInterpretation != null) {
            if (!allowed.contains(photometricInterpretation)) {
                throw new TiffException("Writing " + whatToWrite + " with photometric interpretation \"" +
                        photometricInterpretation.prettyName() + "\" is not supported (only " +
                        allowed.stream().map(photometric -> "\"" + photometric.prettyName() + "\"")
                                .collect(Collectors.joining(", ")) +
                        " allowed)");
            }
        }
    }

    private static long debugTime() {
        return TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }

    private static DataHandle<Location> openWithDeletingPreviousFileIfRequested(Path file, boolean deleteExisting)
            throws IOException {
        Objects.requireNonNull(file, "Null file");
        if (deleteExisting) {
            Files.deleteIfExists(file);
        }
        return TiffTools.getFileHandle(file);
    }
}