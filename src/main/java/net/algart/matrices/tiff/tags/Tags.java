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

package net.algart.matrices.tiff.tags;

import java.util.Objects;

public class Tags {
    private Tags() {
    }

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

    /*
     * EXIF tags.
     */
    public static final int EXIF = 34665;
    public static final int EXPOSURE_TIME = 33434;
    public static final int F_NUMBER = 33437;
    public static final int EXPOSURE_PROGRAM = 34850;
    public static final int SPECTRAL_SENSITIVITY = 34852;
    public static final int GPS_TAG = 34853;
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
     * Returns user-friendly name of the given TIFF tag.
     * It is used, in particular, in {@link #toString()} function.
     *
     * @param tag            entry Tag value.
     * @param includeNumeric include numeric value into the result.
     * @return user-friendly name in a style of Java constant
     */
    public static String tiffTagName(int tag, boolean includeNumeric) {
        String name = Objects.requireNonNullElse(
                TagFriendlyNames.TAG_NAMES.get(tag),
                "UnknownTag" + tag);
        if (!includeNumeric) {
            return name;
        }
        return "%s (%d or 0x%X)".formatted(name, tag, tag);
    }
}
