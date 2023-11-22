/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import io.scif.FormatException;
import io.scif.formats.tiff.OnDemandLongArray;
import io.scif.formats.tiff.TiffCompression;
import io.scif.formats.tiff.TiffRational;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;

public class TiffIFD {
    public record UnsupportedTypeValue(int type, int count, long valueOrOffset) {
        public UnsupportedTypeValue(int type, int count, long valueOrOffset) {
            if (count < 0) {
                throw new IllegalArgumentException("Negative count of values");
            }
            this.type = type;
            this.count = count;
            this.valueOrOffset = valueOrOffset;
        }

        @Override
        public String toString() {
            return "Unsupported TIFF value (unknown type code " + type + ", " + count + " elements)";
        }
    }

    public enum StringFormat {
        BRIEF(true, false),
        NORMAL(true, false),
        NORMAL_SORTED(true, true),
        DETAILED(false, false),
        JSON(false, false);
        private final boolean compactArrays;
        private final boolean sorted;

        StringFormat(boolean compactArrays, boolean sorted) {
            this.compactArrays = compactArrays;
            this.sorted = sorted;
        }

        public boolean isJson() {
            return this == JSON;
        }
    }

    /**
     * Maximal supported number of channels. Popular OpenCV library has the same limit.
     *
     * <p>This limit helps to avoid "crazy" or corrupted TIFF and also help to avoid arithmetic overflow.
     */
    public static final int MAX_NUMBER_OF_CHANNELS = 512;

    public static final int LAST_IFD_OFFSET = 0;

    /*
     * TIFF entry data types:
     */
    public static final int TIFF_BYTE = 1;
    public static final int TIFF_ASCII = 2;
    public static final int TIFF_SHORT = 3;
    public static final int TIFF_LONG = 4;
    public static final int TIFF_RATIONAL = 5;
    public static final int TIFF_SBYTE = 6;
    public static final int TIFF_UNDEFINED = 7;
    public static final int TIFF_SSHORT = 8;
    public static final int TIFF_SLONG = 9;
    public static final int TIFF_SRATIONAL = 10;
    public static final int TIFF_FLOAT = 11;
    public static final int TIFF_DOUBLE = 12;
    public static final int TIFF_IFD = 13;
    public static final int TIFF_LONG8 = 16;
    public static final int TIFF_SLONG8 = 17;
    public static final int TIFF_IFD8 = 18;

    public static final int NEW_SUBFILE_TYPE = 254;
    public static final int SUBFILE_TYPE = 255;
    public static final int IMAGE_WIDTH = 256;
    public static final int IMAGE_LENGTH = 257;
    public static final int BITS_PER_SAMPLE = 258;
    public static final int COMPRESSION = 259;
    public static final int PHOTOMETRIC_INTERPRETATION = 262;
    public static final int THRESHHOLDING = 263;
    public static final int CELL_WIDTH = 264;
    public static final int CELL_LENGTH = 265;
    public static final int FILL_ORDER = 266;
    public static final int DOCUMENT_NAME = 269;
    public static final int IMAGE_DESCRIPTION = 270;
    public static final int MAKE = 271;
    public static final int MODEL = 272;
    public static final int STRIP_OFFSETS = 273;
    public static final int ORIENTATION = 274;
    public static final int SAMPLES_PER_PIXEL = 277;
    public static final int ROWS_PER_STRIP = 278;
    public static final int STRIP_BYTE_COUNTS = 279;
    public static final int MIN_SAMPLE_VALUE = 280;
    public static final int MAX_SAMPLE_VALUE = 281;
    public static final int X_RESOLUTION = 282;
    public static final int Y_RESOLUTION = 283;
    public static final int PLANAR_CONFIGURATION = 284;
    public static final int PAGE_NAME = 285;
    public static final int X_POSITION = 286;
    public static final int Y_POSITION = 287;
    public static final int FREE_OFFSETS = 288;
    public static final int FREE_BYTE_COUNTS = 289;
    public static final int GRAY_RESPONSE_UNIT = 290;
    public static final int GRAY_RESPONSE_CURVE = 291;
    public static final int T4_OPTIONS = 292;
    public static final int T6_OPTIONS = 293;
    public static final int RESOLUTION_UNIT = 296;
    public static final int PAGE_NUMBER = 297;
    public static final int TRANSFER_FUNCTION = 301;
    public static final int SOFTWARE = 305;
    public static final int DATE_TIME = 306;
    public static final int ARTIST = 315;
    public static final int HOST_COMPUTER = 316;
    public static final int PREDICTOR = 317;
    public static final int WHITE_POINT = 318;
    public static final int PRIMARY_CHROMATICITIES = 319;
    public static final int COLOR_MAP = 320;
    public static final int HALFTONE_HINTS = 321;
    public static final int TILE_WIDTH = 322;
    public static final int TILE_LENGTH = 323;
    public static final int TILE_OFFSETS = 324;
    public static final int TILE_BYTE_COUNTS = 325;
    public static final int SUB_IFD = 330;
    public static final int INK_SET = 332;
    public static final int INK_NAMES = 333;
    public static final int NUMBER_OF_INKS = 334;
    public static final int DOT_RANGE = 336;
    public static final int TARGET_PRINTER = 337;
    public static final int EXTRA_SAMPLES = 338;
    public static final int SAMPLE_FORMAT = 339;
    public static final int S_MIN_SAMPLE_VALUE = 340;
    public static final int S_MAX_SAMPLE_VALUE = 341;
    public static final int TRANSFER_RANGE = 342;
    public static final int JPEG_TABLES = 347;
    public static final int JPEG_PROC = 512;
    public static final int JPEG_INTERCHANGE_FORMAT = 513;
    public static final int JPEG_INTERCHANGE_FORMAT_LENGTH = 514;
    public static final int JPEG_RESTART_INTERVAL = 515;
    public static final int JPEG_LOSSLESS_PREDICTORS = 517;
    public static final int JPEG_POINT_TRANSFORMS = 518;
    public static final int JPEG_Q_TABLES = 519;
    public static final int JPEG_DC_TABLES = 520;
    public static final int JPEG_AC_TABLES = 521;
    public static final int Y_CB_CR_COEFFICIENTS = 529;
    public static final int Y_CB_CR_SUB_SAMPLING = 530;
    public static final int Y_CB_CR_POSITIONING = 531;
    public static final int REFERENCE_BLACK_WHITE = 532;
    public static final int COPYRIGHT = 33432;
    public static final int EXIF = 34665;

    /*
     * EXIF tags.
     */
    public static final int EXPOSURE_TIME = 33434;
    public static final int F_NUMBER = 33437;
    public static final int EXPOSURE_PROGRAM = 34850;
    public static final int SPECTRAL_SENSITIVITY = 34852;
    public static final int ISO_SPEED_RATINGS = 34855;
    public static final int OECF = 34856;
    public static final int EXIF_VERSION = 36864;
    public static final int DATE_TIME_ORIGINAL = 36867;
    public static final int DATE_TIME_DIGITIZED = 36868;
    public static final int COMPONENTS_CONFIGURATION = 37121;
    public static final int COMPRESSED_BITS_PER_PIXEL = 37122;
    public static final int SHUTTER_SPEED_VALUE = 37377;
    public static final int APERTURE_VALUE = 37378;
    public static final int BRIGHTNESS_VALUE = 37379;
    public static final int EXPOSURE_BIAS_VALUE = 37380;
    public static final int MAX_APERTURE_VALUE = 37381;
    public static final int SUBJECT_DISTANCE = 37382;
    public static final int METERING_MODE = 37383;
    public static final int LIGHT_SOURCE = 37384;
    public static final int FLASH = 37385;
    public static final int FOCAL_LENGTH = 37386;
    public static final int MAKER_NOTE = 37500;
    public static final int USER_COMMENT = 37510;
    public static final int SUB_SEC_TIME = 37520;
    public static final int SUB_SEC_TIME_ORIGINAL = 37521;
    public static final int SUB_SEC_TIME_DIGITIZED = 37522;
    public static final int FLASH_PIX_VERSION = 40960;
    public static final int COLOR_SPACE = 40961;
    public static final int PIXEL_X_DIMENSION = 40962;
    public static final int PIXEL_Y_DIMENSION = 40963;
    public static final int RELATED_SOUND_FILE = 40964;
    public static final int FLASH_ENERGY = 41483;
    public static final int SPATIAL_FREQUENCY_RESPONSE = 41484;
    public static final int FOCAL_PLANE_X_RESOLUTION = 41486;
    public static final int FOCAL_PLANE_Y_RESOLUTION = 41487;
    public static final int FOCAL_PLANE_RESOLUTION_UNIT = 41488;
    public static final int SUBJECT_LOCATION = 41492;
    public static final int EXPOSURE_INDEX = 41493;
    public static final int SENSING_METHOD = 41495;
    public static final int FILE_SOURCE = 41728;
    public static final int SCENE_TYPE = 41729;
    public static final int CFA_PATTERN = 41730;
    public static final int CUSTOM_RENDERED = 41985;
    public static final int EXPOSURE_MODE = 41986;
    public static final int WHITE_BALANCE = 41987;
    public static final int DIGITAL_ZOOM_RATIO = 41988;
    public static final int FOCAL_LENGTH_35MM_FILM = 41989;
    public static final int SCENE_CAPTURE_TYPE = 41990;
    public static final int GAIN_CONTROL = 41991;
    public static final int CONTRAST = 41992;
    public static final int SATURATION = 41993;
    public static final int SHARPNESS = 41994;
    public static final int SUBJECT_DISTANCE_RANGE = 41996;

    public static final int ICC_PROFILE = 34675;
    public static final int MATTEING = 32995;
    public static final int DATA_TYPE = 32996;
    public static final int IMAGE_DEPTH = 32997;
    public static final int TILE_DEPTH = 32998;
    public static final int STO_NITS = 37439;

    /**
     * Contiguous (chunked) samples format (PlanarConfiguration), for example: RGBRGBRGB....
     */
    public static final int PLANAR_CONFIGURATION_CHUNKED = 1;

    /**
     * Planar samples format (PlanarConfiguration), for example: RRR...GGG...BBB...
     * Note: the specification adds a warning that PlanarConfiguration=2 is not in widespread use and
     * that Baseline TIFF readers are not required to support it.
     */
    public static final int PLANAR_CONFIGURATION_SEPARATE = 2;

    public static final int SAMPLE_FORMAT_UINT = 1;
    public static final int SAMPLE_FORMAT_INT = 2;
    public static final int SAMPLE_FORMAT_IEEEFP = 3;
    public static final int SAMPLE_FORMAT_VOID = 4;
    public static final int SAMPLE_FORMAT_COMPLEX_INT = 5;
    public static final int SAMPLE_FORMAT_COMPLEX_IEEEFP = 6;

    public static final int PREDICTOR_NONE = 1;
    public static final int PREDICTOR_HORIZONTAL = 2;
    public static final int PREDICTOR_FLOATING_POINT = 3;
    // - value 3 is not supported by TiffParser/TiffSaver

    public static final int FILETYPE_REDUCED_IMAGE = 1;

    private final Map<Integer, Object> map;
    private final Map<Integer, TiffEntry> detailedEntries;
    private boolean littleEndian = false;
    private boolean bigTiff = false;
    private long fileOffsetForReading = -1;
    private long fileOffsetForWriting = -1;
    private long nextIFDOffset = -1;
    private Integer subIFDType = null;
    private volatile boolean frozen = false;

    private volatile long[] cachedTileOrStripByteCounts = null;
    private volatile long[] cachedTileOrStripOffsets = null;

    public TiffIFD() {
        this(new LinkedHashMap<>());
    }

    @SuppressWarnings("CopyConstructorMissesField")
    public TiffIFD(TiffIFD ifd) {
        fileOffsetForReading = ifd.fileOffsetForReading;
        fileOffsetForWriting = ifd.fileOffsetForWriting;
        nextIFDOffset = ifd.nextIFDOffset;
        map = new LinkedHashMap<>(ifd.map);
        detailedEntries = ifd.detailedEntries == null ? null : new LinkedHashMap<>(ifd.detailedEntries);
        subIFDType = ifd.subIFDType;
        frozen = false;
        // - Important: a copy is not frozen!
        // And it is the only way to clear this flag.
    }

    public static TiffIFD valueOf(Map<Integer, Object> ifdEntries) {
        return new TiffIFD(ifdEntries);
    }

    private TiffIFD(Map<Integer, Object> ifdEntries) {
        this(ifdEntries, null);
    }

    // Note: detailedEntries is not cloned by this constructor.
    TiffIFD(Map<Integer, Object> ifdEntries, Map<Integer, TiffEntry> detailedEntries) {
        Objects.requireNonNull(ifdEntries);
        this.map = new LinkedHashMap<>(ifdEntries);
        this.detailedEntries = detailedEntries;
    }

    public boolean isLittleEndian() {
        return littleEndian;
    }

    public TiffIFD setLittleEndian(boolean littleEndian) {
        this.littleEndian = littleEndian;
        return this;
    }

    public boolean isBigTiff() {
        return bigTiff;
    }

    public TiffIFD setBigTiff(boolean bigTiff) {
        this.bigTiff = bigTiff;
        return this;
    }

    public boolean hasFileOffsetForReading() {
        return fileOffsetForReading >= 0;
    }

    public long getFileOffsetForReading() {
        if (fileOffsetForReading < 0) {
            throw new IllegalStateException("IFD offset of the TIFF tile is not set while reading");
        }
        return fileOffsetForReading;
    }

    public TiffIFD setFileOffsetForReading(long fileOffsetForReading) {
        if (fileOffsetForReading < 0) {
            throw new IllegalArgumentException("Negative IFD offset in the file: " + fileOffsetForReading);
        }
        this.fileOffsetForReading = fileOffsetForReading;
        return this;
    }

    public TiffIFD removeFileOffsetForReading() {
        this.fileOffsetForReading = -1;
        return this;
    }

    public boolean hasFileOffsetForWriting() {
        return fileOffsetForWriting >= 0;
    }

    public long getFileOffsetForWriting() {
        if (fileOffsetForWriting < 0) {
            throw new IllegalStateException("IFD offset of the TIFF tile for writing is not set");
        }
        return fileOffsetForWriting;
    }

    public TiffIFD setFileOffsetForWriting(long fileOffsetForWriting) {
        if (fileOffsetForWriting < 0) {
            throw new IllegalArgumentException("Negative IFD file offset: " + fileOffsetForWriting);
        }
        if ((fileOffsetForWriting & 0x1) != 0) {
            throw new IllegalArgumentException("Odd IFD file offset " + fileOffsetForWriting +
                    " is prohibited for writing valid TIFF");
            // - But we allow such offsets for reading!
            // Such minor inconsistency in the file is not a reason to decline ability to read it.
        }
        this.fileOffsetForWriting = fileOffsetForWriting;
        return this;
    }

    public TiffIFD removeFileOffsetForWriting() {
        this.fileOffsetForWriting = -1;
        return this;
    }

    public boolean hasNextIFDOffset() {
        return nextIFDOffset >= 0;
    }

    /**
     * Returns <tt>true</tt> if this IFD is marked as the last ({@link #getNextIFDOffset()} returns 0).
     *
     * @return whether this IFD is the last one in the TIFF file.
     */
    public boolean isLastIFD() {
        return nextIFDOffset == LAST_IFD_OFFSET;
    }

    public long getNextIFDOffset() {
        if (nextIFDOffset < 0) {
            throw new IllegalStateException("Next IFD offset is not set");
        }
        return nextIFDOffset;
    }

    public TiffIFD setNextIFDOffset(long nextIFDOffset) {
        if (nextIFDOffset < 0) {
            throw new IllegalArgumentException("Negative next IFD offset: " + nextIFDOffset);
        }
        this.nextIFDOffset = nextIFDOffset;
        return this;
    }

    public TiffIFD setLastIFD() {
        return setNextIFDOffset(LAST_IFD_OFFSET);
    }

    public TiffIFD removeNextIFDOffset() {
        this.nextIFDOffset = -1;
        return this;
    }

    public boolean isMainIFD() {
        return subIFDType == null;
    }

    public Integer getSubIFDType() {
        return subIFDType;
    }

    public TiffIFD setSubIFDType(Integer subIFDType) {
        this.subIFDType = subIFDType;
        return this;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * Disables most possible changes in this map,
     * excepting the only ones, which are absolutely necessary for making final IFD inside the file.
     * Such "necessary" changes are performed by special "updateXxx" method.
     *
     * <p>This method is usually called before writing process.
     * This helps to avoid bugs, connected with changing IFD properties (such as compression, tile sizes etc.)
     * when we already started to write image into the file, for example, have written some its tiles.
     *
     * @return a reference to this object.
     */
    public TiffIFD freeze() {
        this.frozen = true;
        return this;
    }

    public Map<Integer, Object> map() {
        return Collections.unmodifiableMap(map);
    }

    public int numberOfEntries() {
        return map.size();
    }

    public int sizeOfRegionBasedOnType(long sizeX, long sizeY) throws FormatException {
        return TiffTools.checkedMul(sizeX, sizeY, getSamplesPerPixel(), sampleType().bytesPerSample(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample (type-based)",
                () -> "Invalid requested area: ", () -> "");
    }

    public int sizeOfRegion(long sizeX, long sizeY) throws FormatException {
        return TiffTools.checkedMul(sizeX, sizeY, getSamplesPerPixel(), equalBytesPerSample(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample",
                () -> "Invalid requested area: ", () -> "");
    }

    public boolean containsKey(int key) {
        return map.containsKey(key);
    }

    public Object get(int key) {
        return map.get(key);
    }

    public <R> Optional<R> optValue(final int tag, final Class<? extends R> requiredClass) {
        Objects.requireNonNull(requiredClass, "Null requiredClass");
        Object value = get(tag);
        if (!requiredClass.isInstance(value)) {
            // - in particular, if value == null
            return Optional.empty();
        }
        return Optional.of(requiredClass.cast(value));
    }

    public <R> Optional<R> getValue(int tag, Class<? extends R> requiredClass) throws FormatException {
        Objects.requireNonNull(requiredClass, "Null requiredClass");
        Object value = get(tag);
        if (value == null) {
            return Optional.empty();
        }
        if (!requiredClass.isInstance(value)) {
            throw new FormatException("TIFF tag " + ifdTagName(tag, true) +
                    " has wrong type: " + value.getClass().getSimpleName() +
                    " instead of expected " + requiredClass.getSimpleName());
        }
        return Optional.of(requiredClass.cast(value));
    }

    public <R> R reqValue(int tag, Class<? extends R> requiredClass) throws FormatException {
        return getValue(tag, requiredClass).orElseThrow(() -> new FormatException(
                "TIFF tag " + ifdTagName(tag, true) + " is required, but it is absent"));
    }

    public OptionalInt optType(int tag) {
        TiffEntry entry = detailedEntries == null ? null : detailedEntries.get(tag);
        return entry == null ? OptionalInt.empty() : OptionalInt.of(entry.type());
    }

    public boolean optBoolean(int tag, boolean defaultValue) {
        return optValue(tag, Boolean.class).orElse(defaultValue);
    }

    public int reqInt(int tag) throws FormatException {
        return checkedIntValue(reqValue(tag, Number.class), tag);
    }

    public int getInt(int tag, int defaultValue) throws FormatException {
        return checkedIntValue(getValue(tag, Number.class).orElse(defaultValue), tag);
    }

    public int optInt(int tag, int defaultValue) {
        return truncatedIntValue(optValue(tag, Number.class).orElse(defaultValue));
    }

    public long reqLong(int tag) throws FormatException {
        return reqValue(tag, Number.class).longValue();
    }

    public long getLong(int tag, int defaultValue) throws FormatException {
        return getValue(tag, Number.class).orElse(defaultValue).longValue();
    }

    public long optLong(int tag, int defaultValue) {
        return optValue(tag, Number.class).orElse(defaultValue).longValue();
    }

    public long[] getLongArray(int tag) throws FormatException {
        final Object value = get(tag);
        long[] results = null;
        if (value instanceof long[]) {
            results = ((long[]) value).clone();
        } else if (value instanceof Number) {
            results = new long[]{((Number) value).longValue()};
        } else if (value instanceof Number[] numbers) {
            results = new long[numbers.length];
            for (int i = 0; i < results.length; i++) {
                results[i] = numbers[i].longValue();
            }
        } else if (value instanceof int[] integers) {
            results = new long[integers.length];
            for (int i = 0; i < integers.length; i++) {
                results[i] = integers[i];
            }
        } else if (value != null) {
            throw new FormatException("TIFF tag " + ifdTagName(tag, true) +
                    " has wrong type: " + value.getClass().getSimpleName() +
                    " instead of expected Number, Number[], long[] or int[]");
        }
        return results;
    }

    public int[] getIntArray(int tag) throws FormatException {
        final Object value = get(tag);
        int[] results = null;
        if (value instanceof int[]) {
            results = ((int[]) value).clone();
        } else if (value instanceof Number) {
            results = new int[]{checkedIntValue(((Number) value).intValue(), tag)};
        } else if (value instanceof long[] longs) {
            results = new int[longs.length];
            for (int i = 0; i < longs.length; i++) {
                results[i] = checkedIntValue(longs[i], tag);
            }
        } else if (value instanceof Number[] numbers) {
            results = new int[numbers.length];
            for (int i = 0; i < results.length; i++) {
                results[i] = checkedIntValue(numbers[i].longValue(), tag);
            }
        } else if (value != null) {
            throw new FormatException("TIFF tag " + ifdTagName(tag, true) +
                    " has wrong type: " + value.getClass().getSimpleName() +
                    " instead of expected Number, Number[], long[] or int[]");
        }
        return results;
    }

    public int getSamplesPerPixel() throws FormatException {
        int compressionValue = getInt(COMPRESSION, 0);
        if (compressionValue == TiffCompression.OLD_JPEG.getCode()) {
            return 3;
            // always 3 channels: RGB
        }
        final int samplesPerPixel = getInt(SAMPLES_PER_PIXEL, 1);
        if (samplesPerPixel < 1) {
            throw new FormatException("TIFF tag SamplesPerPixel contains illegal zero or negative value: " +
                    samplesPerPixel);
        }
        if (samplesPerPixel > MAX_NUMBER_OF_CHANNELS) {
            throw new FormatException("TIFF tag SamplesPerPixel contains too large value: " +
                    samplesPerPixel + " (maximal support number of channels is " + MAX_NUMBER_OF_CHANNELS + ")");
        }
        return samplesPerPixel;
    }

    public int[] getBitsPerSample() throws FormatException {
        int[] bitsPerSample = getIntArray(BITS_PER_SAMPLE);
        if (bitsPerSample == null) {
            bitsPerSample = new int[]{1};
            // - In the following loop, this array will be appended to necessary length.
        }
        if (bitsPerSample.length == 0) {
            throw new FormatException("Zero length of BitsPerSample array");
        }
        for (int i = 0; i < bitsPerSample.length; i++) {
            if (bitsPerSample[i] <= 0) {
                throw new FormatException("Zero or negative BitsPerSample[" + i + "] = " + bitsPerSample[i]);
            }
        }
        final int samplesPerPixel = getSamplesPerPixel();
        if (bitsPerSample.length < samplesPerPixel) {
            // - Result must contain at least samplesPerPixel elements (SCIFIO agreement)
            // It is not a bug, because getSamplesPerPixel() MAY return 3 for OLD_JPEG
            int[] newBitsPerSample = new int[samplesPerPixel];
            for (int i = 0; i < newBitsPerSample.length; i++) {
                newBitsPerSample[i] = bitsPerSample[i < bitsPerSample.length ? i : 0];
            }
            bitsPerSample = newBitsPerSample;
        }
        return bitsPerSample;
    }

    public int[] getBytesPerSample() throws FormatException {
        final int[] bitsPerSample = getBitsPerSample();
        final int[] result = new int[bitsPerSample.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (bitsPerSample[i] + 7) >>> 3;
            // ">>>" for a case of integer overflow
            assert result[i] >= 0;
        }
        return result;
    }

    public TiffSampleType sampleType() throws FormatException {
        TiffSampleType result = sampleType(true);
        assert result != null;
        return result;
    }

    public TiffSampleType sampleType(boolean requireNonNullResult) throws FormatException {
        final int bytesPerSample;
        if (requireNonNullResult) {
            bytesPerSample = equalBytesPerSample();
        } else {
            try {
                bytesPerSample = equalBytesPerSample();
            } catch (FormatException e) {
                return null;
            }
        }
        int[] sampleFormats = getIntArray(SAMPLE_FORMAT);
        if (sampleFormats == null) {
            sampleFormats = new int[]{SAMPLE_FORMAT_UINT};
        }
        if (sampleFormats.length == 0) {
            throw new FormatException("Zero length of SampleFormat array");
        }
        for (int v : sampleFormats) {
            if (v != sampleFormats[0]) {
                if (requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF IFD: " +
                            "different sample format for different samples (" +
                            Arrays.toString(sampleFormats) + ")");
                } else {
                    return null;
                }
            }
        }
        TiffSampleType result = null;
        switch (sampleFormats[0]) {
            case SAMPLE_FORMAT_UINT -> {
                switch (bytesPerSample) {
                    case 1 -> result = TiffSampleType.UINT8;
                    case 2 -> result = TiffSampleType.UINT16;
                    case 3, 4 -> result = TiffSampleType.UINT32;
                    // - note: 3-byte format should be converted to 4-byte (TiffTools.unpackUnusualPrecisions)
                }
                if (result == null && requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                            Arrays.toString(getBitsPerSample()) + " bits/sample, or " + bytesPerSample +
                            " bytes/sample for unsigned integers, " +
                            "but only 1..4 bytes/sample are supported for integers");

                }
            }
            case SAMPLE_FORMAT_INT -> {
                switch (bytesPerSample) {
                    case 1 -> result = TiffSampleType.INT8;
                    case 2 -> result = TiffSampleType.INT16;
                    case 3, 4 -> result = TiffSampleType.INT32;
                    // - note: 3-byte format should be converted to 4-byte (TiffTools.unpackUnusualPrecisions)
                }
                if (result == null && requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                            Arrays.toString(getBitsPerSample()) + " bits/sample, or " + bytesPerSample +
                            " bytes/sample for signed integers, " +
                            "but only 1..4 bytes/sample are supported for integers");
                }
            }
            case SAMPLE_FORMAT_IEEEFP -> {
                switch (bytesPerSample) {
                    case 2, 3, 4 -> result = TiffSampleType.FLOAT;
                    case 8 -> result = TiffSampleType.DOUBLE;
                    // - note: 2/3-byte float format should be converted to 4-byte (TiffTools.unpackUnusualPrecisions)
                }
                if (result == null && requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                            Arrays.toString(getBitsPerSample()) + " bits/sample, or " +
                            bytesPerSample + " bytes/sample for floating point values, " +
                            "but only 2, 3, 4 bytes/sample cases are supported");
                }
            }
            case SAMPLE_FORMAT_VOID -> {
                if (bytesPerSample == 1) {
                    result = TiffSampleType.UINT8;
                } else {
                    if (requireNonNullResult) {
                        throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                                Arrays.toString(getBitsPerSample()) + " bits/sample, or " +
                                bytesPerSample + " bytes/sample for void values " +
                                "(only 1 byte/sample is supported for unknown data type)");
                    }
                }
            }
            default -> {
                if (requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF data type: SampleFormat=" +
                            Arrays.toString(sampleFormats));
                }
            }
        }
        return result;
    }

    public long[] getTileOrStripByteCounts() throws FormatException {
        final boolean tiled = hasTileInformation();
        final int tag = tiled ? TILE_BYTE_COUNTS : STRIP_BYTE_COUNTS;
        long[] counts = getLongArray(tag);
        if (tiled && counts == null) {
            counts = getLongArray(STRIP_BYTE_COUNTS);
            // - rare situation, when tile byte counts are actually stored in StripByteCounts
        }
        if (counts == null) {
            throw new FormatException("Invalid IFD: no required StripByteCounts/TileByteCounts tag");
        }
        final long numberOfTiles = (long) getTileCountX() * (long) getTileCountY();
        if (counts.length < numberOfTiles) {
            throw new FormatException("StripByteCounts/TileByteCounts length (" + counts.length +
                    ") does not match expected number of strips/tiles (" + numberOfTiles + ")");
        }
        return counts;
    }

    public long[] cachedTileOrStripByteCounts() throws FormatException {
        long[] result = this.cachedTileOrStripByteCounts;
        if (result == null) {
            this.cachedTileOrStripByteCounts = result = getTileOrStripByteCounts();
        }
        return result;
    }

    public int cachedTileOrStripByteCount(int index) throws FormatException {
        long[] byteCounts = cachedTileOrStripByteCounts();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= byteCounts.length) {
            throw new FormatException((hasTileInformation() ?
                    "Tile index is too big for TileByteCounts" :
                    "Strip index is too big for StripByteCounts") +
                    "array: it contains only " + byteCounts.length + " elements");
        }
        long result = byteCounts[index];
        if (result < 0) {
            throw new FormatException(
                    "Negative value " + result + " in " +
                            (hasTileInformation() ? "TileByteCounts" : "StripByteCounts") + " array");
        }
        if (result > Integer.MAX_VALUE) {
            throw new FormatException("Too large tile/strip #" + index + ": " + result + " bytes > 2^31-1");
        }
        return (int) result;
    }

    public long[] getTileOrStripOffsets() throws FormatException {
        final boolean tiled = hasTileInformation();
        final int tag = tiled ? TILE_OFFSETS : STRIP_OFFSETS;
        long[] offsets;
        final OnDemandLongArray compressedOffsets = getOnDemandStripOffsets();
        // - compatibility with old TiffParser feature (can be removed in future versions)
        if (compressedOffsets != null) {
            offsets = new long[(int) compressedOffsets.size()];
            try {
                for (int q = 0; q < offsets.length; q++) {
                    offsets[q] = compressedOffsets.get(q);
                }
            } catch (final IOException e) {
                throw new FormatException("Failed to retrieve offset", e);
            }
        } else {
            offsets = getLongArray(tag);
        }
        if (tiled && offsets == null) {
            // - rare situation, when tile offsets are actually stored in StripOffsets
            offsets = getLongArray(STRIP_OFFSETS);
        }
        if (offsets == null) {
            throw new FormatException("Invalid IFD: no required StripOffsets/TileOffsets tag");
        }
        final long numberOfTiles = (long) getTileCountX() * (long) getTileCountY();
        if (offsets.length < numberOfTiles) {
            throw new FormatException("StripByteCounts/TileByteCounts length (" + offsets.length +
                    ") does not match expected number of strips/tiles (" + numberOfTiles + ")");
        }
        // Note: old getStripOffsets method also performed correction:
        // if (offsets[i] < 0) {
        //     offsets[i] += 0x100000000L;
        // }
        // But it is not necessary with new TiffReader: if reads correct 32-bit unsigned values.

        return offsets;
    }

    public long[] cachedTileOrStripOffsets() throws FormatException {
        long[] result = this.cachedTileOrStripOffsets;
        if (result == null) {
            this.cachedTileOrStripOffsets = result = getTileOrStripOffsets();
        }
        return result;
    }

    public long cachedTileOrStripOffset(int index) throws FormatException {
        long[] offsets = cachedTileOrStripOffsets();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= offsets.length) {
            throw new FormatException((hasTileInformation() ?
                    "Tile index is too big for TileOffsets" :
                    "Strip index is too big for StripOffsets") +
                    "array: it contains only " + offsets.length + " elements");
        }
        final long result = offsets[index];
        if (result < 0) {
            throw new FormatException(
                    "Negative value " + result + " in " +
                            (hasTileInformation() ? "TileOffsets" : "StripOffsets") + " array");
        }
        return result;
    }

    public Optional<String> optDescription() {
        return optValue(IMAGE_DESCRIPTION, String.class);
    }

    public Optional<TiffCompression> optCompression() {
        final int code = optInt(COMPRESSION, -1);
        return code == -1 ? Optional.empty() : Optional.ofNullable(KnownCompression.compressionOfCodeOrNull(code));
    }

    public TiffCompression getCompression() throws FormatException {
        final int code = getInt(COMPRESSION, TiffCompression.UNCOMPRESSED.getCode());
        final TiffCompression result = KnownCompression.compressionOfCodeOrNull(code);
        if (result == null) {
            throw new UnsupportedTiffFormatException("Unknown TIFF compression code: " + code);
        }
        return result;
    }

    public TiffPhotometricInterpretation getPhotometricInterpretation()
            throws FormatException {
        if (!containsKey(PHOTOMETRIC_INTERPRETATION)
                && getInt(COMPRESSION, 0) == TiffCompression.OLD_JPEG.getCode()) {
            return TiffPhotometricInterpretation.RGB;
        }
        final int code = reqInt(PHOTOMETRIC_INTERPRETATION);
        return TiffPhotometricInterpretation.valueOfCodeOrUnknown(code);
    }

    public int[] getYCbCrSubsampling() throws FormatException {
        final Object value = get(Y_CB_CR_SUB_SAMPLING);
        if (value == null) {
            return new int[]{2, 2};
        }
        int[] result;
        if (value instanceof int[] ints) {
            result = ints;
        } else if (value instanceof short[] shorts) {
            result = new int[shorts.length];
            for (int k = 0; k < result.length; k++) {
                result[k] = shorts[k];
            }
        } else {
            throw new FormatException("TIFF tag YCbCrSubSampling has the wrong type " +
                    value.getClass().getSimpleName() + ": must be int[] or short[]");
        }
        if (result.length < 2) {
            throw new FormatException("TIFF tag YCbCrSubSampling contains only " + result.length +
                    " elements: " + Arrays.toString(result) + "; it must contain at least 2 numbers");
        }
        for (int v : result) {
            if (v != 1 && v != 2 && v != 4) {
                throw new FormatException("TIFF tag YCbCrSubSampling must contain only values 1, 2 or 4, " +
                        "but it is " + Arrays.toString(result));
            }
        }
        return Arrays.copyOf(result, 2);
    }

    public int[] getYCbCrSubsamplingLogarithms() throws FormatException {
        int[] result = getYCbCrSubsampling();
        for (int k = 0; k < result.length; k++) {
            final int v = result[k];
            result[k] = 31 - Integer.numberOfLeadingZeros(v);
            assert 1 << result[k] == v;
        }
        return result;
    }


    public int getPlanarConfiguration() throws FormatException {
        final int result = getInt(PLANAR_CONFIGURATION, 1);
        if (result != 1 && result != 2) {
            throw new FormatException("TIFF tag PlanarConfiguration must contain only values 1 or 2, " +
                    "but it is " + result);
        }
        return result;
    }

    public boolean isPlanarSeparated() throws FormatException {
        return getPlanarConfiguration() == PLANAR_CONFIGURATION_SEPARATE;
    }

    public boolean isChunked() throws FormatException {
        return getPlanarConfiguration() == PLANAR_CONFIGURATION_CHUNKED;
    }

    public boolean isReversedBits() throws FormatException {
        final int result = getInt(FILL_ORDER, 1);
        if (result != 1 && result != 2) {
            throw new FormatException("TIFF tag FillOrder must contain only values 1 or 2, " +
                    "but it is " + result);
        }
        return result == 2;
    }

    public boolean hasImageDimensions() {
        return containsKey(IMAGE_WIDTH) && containsKey(IMAGE_LENGTH);
    }

    public int getImageDimX() throws FormatException {
        final int imageWidth = reqInt(IMAGE_WIDTH);
        if (imageWidth <= 0) {
            throw new FormatException("Zero or negative image width = " + imageWidth);
            // - impossible in a correct TIFF
        }
        return imageWidth;
    }

    public int getImageDimY() throws FormatException {
        final int imageLength = reqInt(IMAGE_LENGTH);
        if (imageLength <= 0) {
            throw new FormatException("Zero or negative image height = " + imageLength);
            // - impossible in a correct TIFF
        }
        return imageLength;
    }

    public int getStripRows() throws FormatException {
        final long[] rowsPerStrip = getLongArray(ROWS_PER_STRIP);
        final int imageDimY = getImageDimY();
        if (rowsPerStrip == null || rowsPerStrip.length == 0) {
            // - zero rowsPerStrip.length is possible only as a result of manual modification of this IFD
            return imageDimY == 0 ? 1 : imageDimY;
            // - imageDimY == 0 is checked to be on the safe side
        }

        // rowsPerStrip should never be more than the total number of rows
        for (int i = 0; i < rowsPerStrip.length; i++) {
            int rows = (int) Math.min(rowsPerStrip[i], imageDimY);
            if (rows <= 0) {
                throw new FormatException("Zero or negative RowsPerStrip[" + i + "] = " + rows);
            }
            rowsPerStrip[i] = rows;
        }
        final int result = (int) rowsPerStrip[0];
        assert result > 0 : result + " was not checked in the loop above?";
        for (int i = 1; i < rowsPerStrip.length; i++) {
            if (result != rowsPerStrip[i]) {
                throw new FormatException("Non-uniform RowsPerStrip is not supported");
            }
        }
        return result;
    }

    /**
     * Returns the tile width in tiled image. If there are no tiles,
     * returns max(w,1), where w is the image width.
     *
     * <p>Note: result is always positive!
     *
     * @return tile width.
     * @throws FormatException in a case of incorrect IFD.
     */
    public int getTileSizeX() throws FormatException {
        if (hasTileInformation()) {
            // - Note: we refuse to handle situation, when TileLength presents, but TileWidth not, or vice versa
            final int tileWidth = reqInt(TILE_WIDTH);
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
            if (tileWidth <= 0) {
                throw new FormatException("Zero or negative tile width = " + tileWidth);
                // - impossible in a correct TIFF
            }
            return tileWidth;
        }
        final int imageDimX = getImageDimX();
        return imageDimX == 0 ? 1 : imageDimX;
        // - imageDimX == 0 is checked to be on the safe side
    }


    /**
     * Returns the tile height in tiled image, strip height in other images. If there are no tiles or strips,
     * returns max(h,1), where h is the image height.
     *
     * <p>Note: result is always positive!
     *
     * @return tile/strip height.
     * @throws FormatException in a case of incorrect IFD.
     */
    public int getTileSizeY() throws FormatException {
        if (hasTileInformation()) {
            // - Note: we refuse to handle situation, when TileLength presents, but TileWidth not, or vice versa
            final int tileLength = reqInt(TILE_LENGTH);
            if (tileLength <= 0) {
                throw new FormatException("Zero or negative tile length (height) = " + tileLength);
                // - impossible in a correct TIFF
            }
            return tileLength;
        }
        final int stripRows = getStripRows();
        assert stripRows > 0 : "getStripRows() did not check non-positive result";
        return stripRows;
        // - unlike old SCIFIO getTileLength, we do not return 0 if rowsPerStrip==0:
        // it allows to avoid additional checks in a calling code
    }

    public int getTileCountX() throws FormatException {
        int tileSizeX = getTileSizeX();
        if (tileSizeX <= 0) {
            throw new FormatException("Zero or negative tile width = " + tileSizeX);
        }
        final long imageWidth = getImageDimX();
        final long n = (imageWidth + (long) tileSizeX - 1) / tileSizeX;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageWidth + "/" + tileSizeX + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    public int getTileCountY() throws FormatException {
        int tileSizeY = getTileSizeY();
        if (tileSizeY <= 0) {
            throw new FormatException("Zero or negative tile height = " + tileSizeY);
        }
        final long imageLength = getImageDimY();
        final long n = (imageLength + (long) tileSizeY - 1) / tileSizeY;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageLength + "/" + tileSizeY + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    /**
     * Checks that all bits per sample (<tt>BitsPerSample</tt> tag) for all channels are equal to the same integer,
     * and returns this integer. If it is not so, returns empty optional result.
     * Note that unequal bits per sample is not supported by all software.
     *
     * <p>Note: {@link TiffReader} class does not strictly require this condition, it requires only
     * equality of number of <i>bytes</i> per sample: see {@link #equalBytesPerSample()}
     * (though the complete support of TIFF with different number of bits is not provided).
     * In comparison, {@link TiffWriter} class really <i>does</i> require this condition: it cannot
     * create TIFF files with different number of bits per channel.
     *
     * @return bits per sample, if this value is the same for all channels, or empty value in other case.
     * @throws FormatException in a case of any problems while parsing IFD, in particular,
     *                         if <tt>BitsPerSample</tt> tag contains zero or negative values.
     */
    public OptionalInt tryEqualBitDepth() throws FormatException {
        final int[] bitsPerSample = getBitsPerSample();
        final int bits0 = bitsPerSample[0];
        for (int i = 1; i < bitsPerSample.length; i++) {
            if (bitsPerSample[i] != bits0) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(bits0);
    }

    public OptionalInt tryEqualBitDepthAlignedByBytes() throws FormatException {
        OptionalInt result = tryEqualBitDepth();
        return result.isPresent() && (result.getAsInt() & 7) == 0 ? result : OptionalInt.empty();
    }

    /**
     * Returns the number of bytes per each sample. It is calculated by rounding the number of
     * {@link #getBitsPerSample() bits per sample},
     * divided by 8 with rounding up to nearest integer: &#8968;BitsPerSample/8&#8969;.
     *
     * <p>This method requires that the number of bytes, calculated by this formula, must be positive and
     * equal for all channels.
     * This is also requirement for TIFF files, that can be read by {@link TiffReader} class.
     * However, equality of number of <i>bits</i> is not required.
     *
     * @return number of bytes per each sample.
     * @throws FormatException if &#8968;bitsPerSample/8&#8969; values are different for some channels.
     */
    public int equalBytesPerSample() throws FormatException {
        final int[] bytesPerSample = getBytesPerSample();
        final int bytes0 = bytesPerSample[0];
        // - for example, if we have 5 bits R + 6 bits G + 4 bits B, it will be ceil(6/8) = 1 byte;
        // usually the same for all components
        if (bytes0 < 1) {
            throw new FormatException("Invalid format: zero or negative bytes per sample = " + bytes0);
        }
        for (int k = 1; k < bytesPerSample.length; k++) {
            if (bytesPerSample[k] != bytes0) {
                throw new UnsupportedTiffFormatException("Unsupported TIFF IFD: " +
                        "different number of bytes per samples (" +
                        Arrays.toString(bytesPerSample) + "), based on the following number of bits (" +
                        Arrays.toString(getBitsPerSample()) + ")");
            }
            // - note that LibTiff does not support different BitsPerSample values for different components;
            // we do not support different number of BYTES for different components
        }
        return bytes0;
    }

    public boolean isOrdinaryBitDepth() throws FormatException {
        final int bits = tryEqualBitDepth().orElse(-1);
        return bits == 8 || bits == 16 || bits == 32 || bits == 64;
    }

    public boolean isStandardYCbCrNonJpeg() throws FormatException {
        TiffCompression compression = getCompression();
        return isStandard(compression) && !isJpeg(compression) &&
                getPhotometricInterpretation() == TiffPhotometricInterpretation.Y_CB_CR;
    }

    public boolean isStandardCompression() throws FormatException {
        return isStandard(getCompression());
    }

    public boolean isJpeg() throws FormatException {
        return isJpeg(getCompression());
    }

    public boolean isStandardInvertedCompression() throws FormatException {
        TiffCompression compression = getCompression();
        return isStandard(compression) && !isJpeg(compression) && getPhotometricInterpretation().isInvertedBrightness();
    }

    public boolean isThumbnail() {
        return (optInt(NEW_SUBFILE_TYPE, 0) & FILETYPE_REDUCED_IMAGE) != 0;
    }

    public TiffIFD putImageDimensions(int dimX, int dimY) {
        checkImmutable();
        updateImageDimensions(dimX, dimY);
        return this;
    }

    public TiffIFD removeImageDimensions() {
        remove(IMAGE_WIDTH);
        remove(IMAGE_LENGTH);
        return this;
    }

    public TiffIFD putPixelInformation(int numberOfChannels, Class<?> elementType) {
        return putPixelInformation(numberOfChannels, elementType, false);
    }

    public TiffIFD putPixelInformation(int numberOfChannels, Class<?> elementType, boolean signedIntegers) {
        return putPixelInformation(numberOfChannels, TiffSampleType.valueOf(elementType, signedIntegers));
    }

    /**
     * Puts base pixel type and channels information: BitsPerSample, SampleFormat, SamplesPerPixel.
     *
     * @param numberOfChannels number of channels (in other words, number of samples per every pixel).
     * @param sampleType       type of pixel samples.
     * @return a reference to this object.
     */
    public TiffIFD putPixelInformation(int numberOfChannels, TiffSampleType sampleType) {
        Objects.requireNonNull(sampleType, "Null sampleType");
        putNumberOfChannels(numberOfChannels);
        // - note: actual number of channels will be 3 in a case of OLD_JPEG;
        // but it is not a recommended usage (we may set OLD_JPEG compression later)
        putSampleType(sampleType);
        return this;
    }

    public TiffIFD putNumberOfChannels(int numberOfChannels) {
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (numberOfChannels > MAX_NUMBER_OF_CHANNELS) {
            throw new IllegalArgumentException("Very large number of channels " + numberOfChannels + " > " +
                    MAX_NUMBER_OF_CHANNELS + " is not supported");
        }
        put(SAMPLES_PER_PIXEL, numberOfChannels);
        return this;
    }

    public TiffIFD putSampleType(TiffSampleType sampleType) {
        Objects.requireNonNull(sampleType, "Null sampleType");
        final int bytesPerSample = sampleType.bytesPerSample();
        final boolean signed = sampleType.isSigned();
        final boolean floatingPoint = sampleType.isFloatingPoint();
        final int samplesPerPixel;
        try {
            samplesPerPixel = getSamplesPerPixel();
        } catch (FormatException e) {
            throw new IllegalStateException("Cannot set TIFF samples type: SamplesPerPixel tag is invalid", e);
        }
        put(BITS_PER_SAMPLE, nInts(samplesPerPixel, 8 * bytesPerSample));
        if (floatingPoint) {
            put(SAMPLE_FORMAT, nInts(samplesPerPixel, SAMPLE_FORMAT_IEEEFP));
        } else if (signed) {
            put(SAMPLE_FORMAT, nInts(samplesPerPixel, SAMPLE_FORMAT_INT));
        } else {
            remove(SAMPLE_FORMAT);
        }
        return this;
    }

    public TiffIFD putCompression(TiffCompression compression) {
        return putCompression(compression, false);
    }

    public TiffIFD putCompression(TiffCompression compression, boolean putAlsoDefaultUncompressed) {
        if (compression == null && putAlsoDefaultUncompressed) {
            compression = TiffCompression.UNCOMPRESSED;
        }
        if (compression == null) {
            remove(COMPRESSION);
        } else {
            put(COMPRESSION, compression.getCode());
        }
        return this;
    }

    public TiffIFD putPhotometricInterpretation(TiffPhotometricInterpretation photometricInterpretation) {
        Objects.requireNonNull(photometricInterpretation, "Null photometricInterpretation");
        put(PHOTOMETRIC_INTERPRETATION, photometricInterpretation.code());
        return this;
    }

    public TiffIFD putPlanarSeparated(boolean planarSeparated) {
        if (planarSeparated) {
            put(PLANAR_CONFIGURATION, PLANAR_CONFIGURATION_SEPARATE);
        } else {
            remove(PLANAR_CONFIGURATION);
        }
        return this;
    }

    /**
     * Returns <tt>true</tt> if IFD contains tags <tt>TileWidth</tt> and <tt>TileLength</tt>.
     * It means that the image is stored in tiled form, but not separated by strips.
     *
     * <p>If IFD contains <b>only one</b> from these tags &mdash; there is <tt>TileWidth</tt>,
     * but <tt>TileLength</tt> is not specified, or vice versa &mdash; this method throws
     * <tt>FormatException</tt>. It allows to guarantee: if this method returns <tt>true</tt>,
     * than the tile sizes are completely specified by these 2 tags and do not depend on the
     * actual image sizes. Note: when this method returns <tt>false</tt>, the tiles sizes,
     * returned by {@link #getTileSizeY()} methods, are determined by
     * <tt>RowsPerStrip</tt> tag, and {@link #getTileSizeX()} is always equal to the value
     * of <tt>ImageWidth</tt> tag.
     *
     * <p>For comparison, old <tt>isTiled()</tt> methods returns <tt>true</tt>
     * if IFD contains tag <tt>TileWidth</tt> <i>and</i> does not contain tag <tt>StripOffsets</tt>.
     * However, some TIFF files use <tt>StripOffsets</tt> and <tt>StripByteCounts</tt> tags even
     * in a case of tiled image, for example, cramps-tile.tif from the known image set <i>libtiffpic</i>
     * (see https://download.osgeo.org/libtiff/ ).
     *
     * @return whether this IFD contain tile size information.
     * @throws FormatException if one of tags <tt>TileWidth</tt> and <tt>TileLength</tt> is present in IFD,
     *                         but the second is absent.
     */
    public boolean hasTileInformation() throws FormatException {
        final boolean hasWidth = containsKey(TILE_WIDTH);
        final boolean hasLength = containsKey(TILE_LENGTH);
        if (hasWidth != hasLength) {
            throw new FormatException("Inconsistent tiling information: tile width (TileWidth tag) is " +
                    (hasWidth ? "" : "NOT ") + "specified, but tile height (TileLength tag) is " +
                    (hasLength ? "" : "NOT ") + "specified");
        }
        return hasWidth;
    }

    public TiffIFD putTileSizes(int tileSizeX, int tileSizeY) {
        if (tileSizeX <= 0) {
            throw new IllegalArgumentException("Zero or negative tile x-size");
        }
        if (tileSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative tile y-size");
        }
        if ((tileSizeX & 15) != 0 || (tileSizeY & 15) != 0) {
            throw new IllegalArgumentException("Illegal tile sizes " + tileSizeX + "x" + tileSizeY
                    + ": they must be multiples of 16");
        }
        put(TILE_WIDTH, tileSizeX);
        put(TILE_LENGTH, tileSizeY);
        return this;
    }

    public TiffIFD removeTileInformation() {
        remove(TILE_WIDTH);
        remove(TILE_LENGTH);
        return this;
    }

    public boolean hasStripInformation() {
        return containsKey(ROWS_PER_STRIP);
    }

    public TiffIFD putStripSize(int stripSizeY) {
        if (stripSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative strip y-size");
        }
        put(ROWS_PER_STRIP, new long[]{stripSizeY});
        return this;
    }

    public TiffIFD removeStripInformation() {
        remove(ROWS_PER_STRIP);
        return this;
    }

    public void removeDataPositioning() {
        remove(STRIP_OFFSETS);
        remove(STRIP_BYTE_COUNTS);
        remove(TILE_OFFSETS);
        remove(TILE_BYTE_COUNTS);
    }

    /**
     * Puts new values for <tt>ImageWidth</tt> and <tt>ImageLength</tt> tags.
     *
     * <p>Note: this method works even when IFD is frozen by {@link #freeze()} method.
     *
     * @param dimX new TIFF image width (<tt>ImageWidth</tt> tag).
     * @param dimY new TIFF image height (<tt>ImageLength</tt> tag).
     */
    public TiffIFD updateImageDimensions(int dimX, int dimY) {
        if (dimX <= 0) {
            throw new IllegalArgumentException("Zero or negative image width (x-dimension): " + dimX);
        }
        if (dimY <= 0) {
            throw new IllegalArgumentException("Zero or negative image height (y-dimension): " + dimY);
        }
        if (!containsKey(TILE_WIDTH) || !containsKey(TILE_LENGTH)) {
            // - we prefer not to throw FormatException here, like in hasTileInformation method
            checkImmutable("Image dimensions cannot be updated in non-tiled TIFF");
        }
        clearCache();
        removeEntries(IMAGE_WIDTH, IMAGE_LENGTH);
        // - to avoid illegal detection of the type
        map.put(IMAGE_WIDTH, dimX);
        map.put(IMAGE_LENGTH, dimY);
        return this;
    }

    /**
     * Puts new values for <tt>TileOffsets</tt> / <tt>TileByteCounts</tt> tags or
     * <tt>StripOffsets</tt> / <tt>StripByteCounts</tt> tag, depending on result of
     * {@link #hasTileInformation()} methods (<tt>true</tt> or <tt>false</tt> correspondingly).
     *
     * <p>Note: this method works even when IFD is frozen by {@link #freeze()} method.
     *
     * @param offsets    byte offset of each tile/strip in TIFF file.
     * @param byteCounts number of (compressed) bytes in each tile/strip.
     */
    public void updateDataPositioning(long[] offsets, long[] byteCounts) {
        Objects.requireNonNull(offsets, "Null offsets");
        Objects.requireNonNull(byteCounts, "Null byte counts");
        final boolean tiled;
        final long tileCountX;
        final long tileCountY;
        final int numberOfSeparatedPlanes;
        try {
            tiled = hasTileInformation();
            tileCountX = getTileCountX();
            tileCountY = getTileCountY();
            numberOfSeparatedPlanes = isPlanarSeparated() ? getSamplesPerPixel() : 1;
        } catch (FormatException e) {
            throw new IllegalStateException("Illegal IFD: " + e.getMessage(), e);
        }
        final long totalCount = tileCountX * tileCountY * numberOfSeparatedPlanes;
        if (tileCountX * tileCountY > Integer.MAX_VALUE || totalCount > Integer.MAX_VALUE ||
                offsets.length != totalCount || byteCounts.length != totalCount) {
            throw new IllegalArgumentException("Incorrect offsets array (" +
                    offsets.length + " values) or byte-counts array (" + byteCounts.length +
                    " values) not equal to " + totalCount + " - actual number of " +
                    (tiled ? "tiles, " + tileCountX + " x " + tileCountY :
                            "strips, " + tileCountY) +
                    (numberOfSeparatedPlanes == 1 ? "" : " x " + numberOfSeparatedPlanes + " separated channels"));
        }
        clearCache();
        removeEntries(TILE_OFFSETS, STRIP_OFFSETS, TILE_BYTE_COUNTS, STRIP_BYTE_COUNTS);
        // - to avoid illegal detection of the type
        map.put(tiled ? TILE_OFFSETS : STRIP_OFFSETS, offsets);
        map.put(tiled ? TILE_BYTE_COUNTS : STRIP_BYTE_COUNTS, byteCounts);
        // Just in case, let's also remove extra tags:
        map.remove(tiled ? STRIP_OFFSETS : TILE_OFFSETS);
        map.remove(tiled ? STRIP_BYTE_COUNTS : TILE_BYTE_COUNTS);
    }

    public Object put(int key, Object value) {
        checkImmutable();
        removeEntries(key);
        // - necessary to avoid possible bugs with detection of type
        clearCache();
        return map.put(key, value);
    }

    public Object remove(int key) {
        checkImmutable();
        removeEntries(key);
        clearCache();
        return map.remove(key);
    }

    public void clear() {
        checkImmutable();
        clearCache();
        if (detailedEntries != null) {
            detailedEntries.clear();
        }
        map.clear();
    }


    @Override
    public String toString() {
        return toString(StringFormat.BRIEF);
    }

    public String toString(StringFormat format) {
        Objects.requireNonNull(format, "Null format");
        final boolean json = format.isJson();
        final StringBuilder sb = new StringBuilder();
        sb.append(json ?
                "{\n" :
                "IFD");
        final String ifdTypeName = subIFDType == null ? "main" : ifdTagName(subIFDType, false);
        sb.append((json ?
                "  \"ifdType\" : \"%s\",\n" :
                " (%s)").formatted(ifdTypeName));
        long dimX = 0;
        long dimY = 0;
        int channels;
        int tileSizeX = 1;
        int tileSizeY = 1;
        try {
            final TiffSampleType sampleType = sampleType(false);
            sb.append((json ?
                    "  \"elementType\" : \"%s\",\n" :
                    " %s").formatted(
                    sampleType == null ? "???" : sampleType.elementType().getSimpleName()));
            channels = getSamplesPerPixel();
            if (hasImageDimensions()) {
                dimX = getImageDimX();
                dimY = getImageDimY();
                tileSizeX = getTileSizeX();
                tileSizeY = getTileSizeY();
                sb.append((json ?
                        "  \"dimX\" : %d,\n  \"dimY\" : %d,\n  \"channels\" : %d,\n" :
                        "[%dx%dx%d], ").formatted(dimX, dimY, channels));
            } else {
                sb.append((json ?
                        "  \"channels\" : %d,\n" :
                        "[?x?x%d], ").formatted(channels));
            }
        } catch (Exception e) {
            sb.append(json ?
                    "  \"exceptionBasic\" : \"%s\",\n".formatted(TiffTools.escapeJsonString(e.getMessage())) :
                    " [cannot detect basic information: " + e.getMessage() + "] ");
        }
        try {
            final TiffSampleType sampleType = sampleType(false);
            final long tileCountX = (dimX + (long) tileSizeX - 1) / tileSizeX;
            final long tileCountY = (dimY + (long) tileSizeY - 1) / tileSizeY;
            sb.append(json ?
                    ("  \"precision\" : \"%s\",\n" +
                            "  \"littleEndian\" : %s,\n" +
                            "  \"bigTiff\" : %s,\n" +
                            "  \"tiled\" : %s,\n").formatted(
                            sampleType.prettyName(),
                            isLittleEndian(),
                            isBigTiff(),
                            hasTileInformation()) :
                    "%s, precision %s%s, ".formatted(
                            isLittleEndian() ? "little-endian" : "big-endian",
                            sampleType == null ? "???" : sampleType.prettyName(),
                            isBigTiff() ? " [BigTIFF]" : ""));
            if (hasTileInformation()) {
                sb.append(
                        json ?
                                ("  \"tiles\" : {\n" +
                                        "    \"sizeX\" : %d,\n" +
                                        "    \"sizeY\" : %d,\n" +
                                        "    \"countX\" : %d,\n" +
                                        "    \"countY\" : %d,\n" +
                                        "    \"count\" : %d\n" +
                                        "  },\n").formatted(
                                        tileSizeX, tileSizeY, tileCountX, tileCountY,
                                        tileCountX * tileCountY) :
                                "%dx%d=%d tiles %dx%d (last tile %sx%s)".formatted(
                                        tileCountX,
                                        tileCountY,
                                        tileCountX * tileCountY,
                                        tileSizeX,
                                        tileSizeY,
                                        remainderToString(dimX, tileSizeX),
                                        remainderToString(dimY, tileSizeY)));
            } else {
                sb.append(
                        json ?
                                ("  \"strips\" : {\n" +
                                        "    \"sizeY\" : %d,\n" +
                                        "    \"countY\" : %d\n" +
                                        "  },\n").formatted(tileSizeY, tileCountY) :
                                "%d strips per %d lines (last strip %s, virtual \"tiles\" %dx%d)".formatted(
                                        tileCountY,
                                        tileSizeY,
                                        dimY == tileCountY * tileSizeY ?
                                                "full" :
                                                remainderToString(dimY, tileSizeY) + " lines",
                                        tileSizeX,
                                        tileSizeY));
            }
            sb.append(json ?
                    "  \"chunked\" : %s,\n".formatted(isChunked()) :
                    isChunked() ? ", chunked" : ", planar");
        } catch (Exception e) {
            sb.append(json ?
                    "  \"exceptionAdditional\" : \"%s\",\n".formatted(TiffTools.escapeJsonString(e.getMessage())) :
                    " [cannot detect additional information: " + e.getMessage() + "]");
        }
        if (!json) {
            if (hasFileOffsetForReading()) {
                sb.append(", reading offset @%d=0x%X".formatted(fileOffsetForReading, fileOffsetForReading));
            }
            if (hasFileOffsetForWriting()) {
                sb.append(", writing offset @%d=0x%X".formatted(fileOffsetForWriting, fileOffsetForWriting));
            }
            if (hasNextIFDOffset()) {
                sb.append(isLastIFD() ? ", LAST" : ", next IFD at @%d=0x%X".formatted(nextIFDOffset, nextIFDOffset));
            }
        }
        if (format == StringFormat.BRIEF) {
            assert !json;
            return sb.toString();
        }
        if (json) {
            sb.append("  \"map\" : {\n");
        } else {
            sb.append("; ").append(numberOfEntries()).append(" entries:");
        }
        final Map<Integer, TiffEntry> entries = this.detailedEntries;
        final Collection<Integer> keySequence = format.sorted ? new TreeSet<>(map.keySet()) : map.keySet();
        boolean firstEntry = true;
        for (Integer tag : keySequence) {
            final Object v = this.get(tag);
            boolean manyValues = v != null && v.getClass().isArray();
            String tagName = ifdTagName(tag, !json);
            if (json) {
                sb.append(firstEntry ? "" : ",\n");
                firstEntry = false;
                sb.append("    \"%s\" : ".formatted(TiffTools.escapeJsonString(tagName)));
                if (manyValues) {
                    sb.append("[");
                    appendIFDArray(sb, v, false, true);
                    sb.append("]");
                } else if (v instanceof TiffRational) {
                    sb.append("\"").append(v).append("\"");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append("\"");
                    TiffTools.escapeJsonString(sb, String.valueOf(v));
                    sb.append("\"");
                }
            } else {
                sb.append("%n".formatted());
                Object additional = null;
                try {
                    switch (tag) {
                        case PHOTOMETRIC_INTERPRETATION -> additional = getPhotometricInterpretation().prettyName();
                        case COMPRESSION -> additional = prettyCompression(getCompression());
                        case PLANAR_CONFIGURATION -> {
                            if (v instanceof Number number) {
                                switch (number.intValue()) {
                                    case PLANAR_CONFIGURATION_CHUNKED -> additional = "chunky";
                                    case PLANAR_CONFIGURATION_SEPARATE -> additional = "rarely-used planar";
                                }
                            }
                        }
                        case SAMPLE_FORMAT -> {
                            if (v instanceof Number number) {
                                switch (number.intValue()) {
                                    case SAMPLE_FORMAT_UINT -> additional = "unsigned integer";
                                    case SAMPLE_FORMAT_INT -> additional = "signed integer";
                                    case SAMPLE_FORMAT_IEEEFP -> additional = "IEEE float";
                                    case SAMPLE_FORMAT_VOID -> additional = "undefined";
                                    case SAMPLE_FORMAT_COMPLEX_INT -> additional = "complex integer";
                                    case SAMPLE_FORMAT_COMPLEX_IEEEFP -> additional = "complex float";
                                }
                            }
                        }
                        case FILL_ORDER -> {
                            additional = !isReversedBits() ?
                                    "default bits order: highest first (big-endian, 7-6-5-4-3-2-1-0)" :
                                    "reversed bits order: lowest first (little-endian, 0-1-2-3-4-5-6-7)";
                        }
                        case PREDICTOR -> {
                            if (v instanceof Number number) {
                                switch (number.intValue()) {
                                    case PREDICTOR_NONE -> additional = "none";
                                    case PREDICTOR_HORIZONTAL -> additional = "horizontal subtraction";
                                    case PREDICTOR_FLOATING_POINT -> additional = "floating-point subtraction";
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    additional = e;
                }
                sb.append("    ").append(tagName).append(" = ");
                if (manyValues) {
                    sb.append(v.getClass().getComponentType().getSimpleName());
                    sb.append("[").append(Array.getLength(v)).append("]");
                    sb.append(" {");
                    appendIFDArray(sb, v, format.compactArrays, false);
                    sb.append("}");
                } else {
                    sb.append(v);
                }
                if (entries != null) {
                    final TiffEntry tiffEntry = entries.get(tag);
                    if (tiffEntry != null) {
                        sb.append(" : ").append(entryTypeToString(tiffEntry.type()));
                        int valueCount = tiffEntry.valueCount();
                        if (valueCount != 1) {
                            sb.append("[").append(valueCount).append("]");
                        }
                        if (manyValues) {
                            sb.append(" at @").append(tiffEntry.valueOffset());
                        }
                    }
                }
                if (additional != null) {
                    sb.append("   [it means: ").append(additional).append("]");
                }
            }
        }
        if (json) {
            sb.append("\n  }\n}");
        }
        return sb.toString();
    }

    public static String entryTypeToString(int ifdEntryType) {
        return switch (ifdEntryType) {
            case TIFF_BYTE -> "BYTE";
            case TIFF_ASCII -> "ASCII";
            case TIFF_SHORT -> "SHORT";
            case TIFF_LONG -> "LONG";
            case TIFF_RATIONAL -> "RATIONAL";
            case TIFF_SBYTE -> "SBYTE";
            case TIFF_UNDEFINED -> "UNDEFINED";
            case TIFF_SSHORT -> "SSHORT";
            case TIFF_SLONG -> "SLONG";
            case TIFF_SRATIONAL -> "SRATIONAL";
            case TIFF_FLOAT -> "FLOAT";
            case TIFF_DOUBLE -> "DOUBLE";
            case TIFF_IFD -> "IFD";
            case TIFF_LONG8 -> "LONG8";
            case TIFF_SLONG8 -> "SLONG8";
            case TIFF_IFD8 -> "IFD8";
            default -> "Unknown type";
        };
    }

    public static int entryTypeSize(int ifdEntryType) {
        return switch (ifdEntryType) {
            case TIFF_BYTE -> 1;
            case TIFF_ASCII -> 1;
            case TIFF_SHORT -> 2;
            case TIFF_LONG -> 4;
            case TIFF_RATIONAL -> 8;
            case TIFF_SBYTE -> 1;
            case TIFF_UNDEFINED -> 1;
            case TIFF_SSHORT -> 2;
            case TIFF_SLONG -> 4;
            case TIFF_SRATIONAL -> 8;
            case TIFF_FLOAT -> 4;
            case TIFF_DOUBLE -> 8;
            case TIFF_IFD -> 4;
            case TIFF_LONG8 -> 8;
            case TIFF_SLONG8 -> 8;
            case TIFF_IFD8 -> 8;
            default -> 0;
        };
    }

    public static boolean isStandard(TiffCompression compression) {
        Objects.requireNonNull(compression, "Null compression");
        return compression.getCode() <= 10 || compression == TiffCompression.PACK_BITS;
        // - actually maximal supported standard TiffCompression is DEFLATE=8
    }

    public static boolean isJpeg(TiffCompression compression) {
        Objects.requireNonNull(compression, "Null compression");
        return compression == TiffCompression.JPEG
                || compression == TiffCompression.OLD_JPEG
                || compression == TiffCompression.ALT_JPEG;
        // - actually only TiffCompression.JPEG is surely supported
    }

    private void clearCache() {
        cachedTileOrStripByteCounts = null;
        cachedTileOrStripOffsets = null;
    }

    private void checkImmutable() {
        checkImmutable("IFD cannot be modified");
    }

    private void checkImmutable(String nameOfPart) {
        if (frozen) {
            throw new IllegalStateException(nameOfPart + ": it is frozen for future writing TIFF");
        }
    }

    private void removeEntries(int... tags) {
        if (detailedEntries != null) {
            for (int tag : tags) {
                detailedEntries.remove(tag);
            }
        }
    }

    // - For compatibility with old TiffParser feature (can be removed in future versions)
    private OnDemandLongArray getOnDemandStripOffsets() throws FormatException {
        final int tag = hasTileInformation() ? TILE_OFFSETS : STRIP_OFFSETS;
        final Object offsets = get(tag);
        if (offsets instanceof OnDemandLongArray) {
            return (OnDemandLongArray) offsets;
        }
        return null;
    }


    /**
     * Returns user-friendly name of the given TIFF tag.
     * It is used, in particular, in {@link #toString()} function.
     *
     * @param tag            entry Tag value.
     * @param includeNumeric include numeric value into the result.
     * @return user-friendly name in a style of Java constant
     */
    public static String ifdTagName(int tag, boolean includeNumeric) {
        String name = Objects.requireNonNullElse(
                IFDFriendlyNames.IFD_TAG_NAMES.get(tag),
                "UnknownTag" + tag);
        if (!includeNumeric) {
            return name;
        }
        return "%s (%d or 0x%X)".formatted(name, tag, tag);
    }

    private static int truncatedIntValue(Number value) {
        Objects.requireNonNull(value);
        long result = value.longValue();
        if (result > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (result < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) result;
    }

    private static int checkedIntValue(Number value, int tag) throws FormatException {
        Objects.requireNonNull(value);
        long result = value.longValue();
        if (result > Integer.MAX_VALUE) {
            throw new FormatException("Very large " + ifdTagName(tag, true) +
                    " = " + value + " >= 2^31 is not supported");
        }
        if (result < Integer.MIN_VALUE) {
            throw new FormatException("Very large (by absolute value) negative " + ifdTagName(tag, true) +
                    " = " + value + " < -2^31 is not supported");
        }
        return (int) result;
    }

    private static int[] nInts(int count, int filler) {
        final int[] result = new int[count];
        Arrays.fill(result, filler);
        return result;
    }

    private static String prettyCompression(TiffCompression compression) {
        if (compression == null) {
            return "No compression?";
        }
        final String s = compression.toString();
        final String codecName = compression.getCodecName();
        return s.equals(codecName) ? s : s + " (" + codecName + ")";
    }

    private static String remainderToString(long a, long b) {
        long r = a / b;
        if (r * b == a) {
            return String.valueOf(b);
        } else {
            return String.valueOf(a - r * b);
        }
    }

    private static void appendIFDArray(StringBuilder sb, Object v, boolean compact, boolean jsonMode) {
        final boolean numeric = v instanceof byte[] || v instanceof short[] ||
                v instanceof int[] || v instanceof long[] ||
                v instanceof float[] || v instanceof double[] ||
                v instanceof Number[];
        if (!numeric && jsonMode) {
            return;
        }
        if (!jsonMode && v instanceof byte[] bytes) {
            appendIFDHexBytesArray(sb, bytes, compact);
            return;
        }
        final int len = Array.getLength(v);
        final int left = v instanceof short[] ? 25 : 10;
        final int right = v instanceof short[] ? 10 : 5;
        final int mask = v instanceof short[] ? 0xFFFF : 0;
        for (int k = 0; k < len; k++) {
            if (compact && k == left && len >= left + 5 + right) {
                sb.append(", ...");
                k = len - right - 1;
                continue;
            }
            if (k > 0) {
                sb.append(", ");
            }
            Object o = Array.get(v, k);
            if (mask != 0) {
                o = ((Number) o).intValue() & mask;
            }
            if (jsonMode && o instanceof TiffRational) {
                sb.append("\"").append(o).append("\"");
            } else {
                sb.append(o);
            }
        }
    }

    private static void appendIFDHexBytesArray(StringBuilder sb, byte[] v, boolean compact) {
        final int len = v.length;
        for (int k = 0; k < len; k++) {
            if (compact && k == 20 && len >= 35) {
                sb.append(", ...");
                k = len - 11;
                continue;
            }
            if (k > 0) {
                sb.append(", ");
            }
            appendHexByte(sb, v[k] & 0xFF);
        }
    }

    private static void appendHexByte(StringBuilder sb, int v) {
        if (v < 16) {
            sb.append('0');
        }
        sb.append(Integer.toHexString(v));
    }

    // Helper class for internal needs, analog of SCIFIO TiffIFDEntry
    record TiffEntry(int tag, int type, int valueCount, long valueOffset) {
    }
}
