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

package net.algart.matrices.tiff.tags;

import net.algart.matrices.tiff.TiffWriter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Data types in IFD entries supported by the TIFF and BigTIFF specifications.
 */
public enum TagType {
    /**
     * 8-bit unsigned integer.
     */
    BYTE(1, 1, false, short.class, short[].class, Short.class),

    /**
     * 8-bit byte that contains a 7-bit ASCII code; the last byte must be NUL (binary zero).
     */
    ASCII(2, 1, false, String.class, String[].class),

    /**
     * 16-bit (2-byte) unsigned integer.
     */
    SHORT(3, 2, false, int.class, int[].class, Integer.class),

    /**
     * 32-bit (4-byte) unsigned integer.
     */
    LONG(4, 4, false, long.class, long[].class, Long.class),

    /**
     * Two LONGs: the first represents the numerator of a fraction; the second, the denominator.
     */
    RATIONAL(5, 8, false, TagValue.Rational.class, TagValue.Rational[].class),

    /**
     * An 8-bit signed (2's complement) integer.
     */
    SBYTE(6, 1, true, TagValue.SByte.class, TagValue.SByte[].class),

    /**
     * An 8-bit byte that may contain anything, depending on the definition of the field.
     */
    UNDEFINED(7, 1, false, byte.class, byte[].class, Byte.class),

    /**
     * A 16-bit (2-byte) signed (2's complement) integer.
     */
    SSHORT(8, 2, true, TagValue.SShort.class, TagValue.SShort[].class),

    /**
     * A 32-bit (4-byte) signed (2's complement) integer.
     */
    SLONG(9, 4, true, TagValue.SLong.class, TagValue.SLong[].class),

    /**
     * Two SLONGs: the first represents the numerator; the second, the denominator.
     */
    SRATIONAL(10, 8, true, TagValue.SRational.class, TagValue.SRational[].class),

    /**
     * Single precision (4-byte) IEEE format floating point value.
     */
    FLOAT(11, 4, true, float.class, float[].class, Float.class),

    /**
     * Double precision (8-byte) IEEE format floating point value.
     */
    DOUBLE(12, 8, true, double.class, double[].class, Double.class),
    /**
     * 32-bit unsigned integer, used for storing IFD offsets.
     */
    IFD(13, 4, false, TagValue.IFD.class, TagValue.IFD[].class),

    /**
     * 64-bit unsigned integer (BigTIFF only).
     */
    LONG8(16, 8, false, long.class, long[].class, Long.class),

    /**
     * 64-bit signed integer (BigTIFF only).
     */
    SLONG8(17, 8, true, TagValue.SLong8.class, TagValue.SLong8[].class),

    /**
     * 64-bit unsigned integer, used for storing IFD offsets (BigTIFF only).
     */
    IFD8(18, 8, false, TagValue.IFD.class, TagValue.IFD[].class);
    // Important: in the list above, Big-TIFF types MUST be after non-Big-TIFF analogs!

    private static final Map<Class<?>, TagType> CLASS_LOOKUP = new HashMap<>();

    static {
        for (TagType tagType : values()) {
            for (Class<?> javaType : tagType.javaTypes) {
                final TagType previous = CLASS_LOOKUP.putIfAbsent(javaType, tagType);
                assert previous == null || tagType.isBigTiffOnly() : "Invalid order of TagType enums";
            }
        }
    }

    private static final Map<Integer, TagType> CODE_LOOKUP =
            Arrays.stream(values()).collect(Collectors.toMap(TagType::typeCode, v -> v));

    private final int typeCode;
    private final int bytesPerElement;
    private final boolean signed;
    private final Class<?>[] javaTypes;

    TagType(int id, int bytesPerElement, boolean signed, Class<?>... javaTypes) {
        Objects.requireNonNull(javaTypes);
        assert javaTypes.length >= 2;
        assert javaTypes[1].getComponentType() == javaTypes[0];
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (bytesPerElement <= 0) {
            throw new IllegalArgumentException("sizeOf must be positive");
        }
        this.typeCode = id;
        this.bytesPerElement = bytesPerElement;
        this.signed = signed;
        this.javaTypes = javaTypes;
    }

    public int typeCode() {
        return typeCode;
    }

    public int bytesPerElement() {
        return bytesPerElement;
    }

    public int bitsPerElement() {
        return 8 * bytesPerElement;
    }

    /**
     * Returns {@code true} for signed types: {@link #SBYTE}, {@link #SSHORT},
     * {@link #SLONG}, {@link #SLONG8}, and also for floating-point types {@link #FLOAT} and {@link #DOUBLE}.
     *
     * @return whether this type is signed.
     */
    public boolean isSigned() {
        return signed;
    }

    /**
     * Returns {@code true} if this type is automatically adjusted
     * depending on the BigTIFF mode (whether the target file is BigTIFF or not).
     *
     * <p>When {@link net.algart.matrices.tiff.TiffWriter} writes an IFD value of these types,
     * it automatically selects between the 32-bit and 64-bit representations
     * ({@link #LONG} vs {@link #LONG8}, or {@link #IFD} vs {@link #IFD8}).
     * This selection is strictly driven by the Big-TIFF state: a standard Classic TIFF
     * forces 32-bit types, while BigTIFF mode switches them to 64-bit variants.
     * There are exceptions for some the specific tags, for example,
     * {@code ImageWidth} / {@code ImageLength} are usually written as 32-bit {@link #LONG} type
     * even for Big-TIFF files.
     * In any case, the actual type of the value returned by {@link net.algart.matrices.tiff.TiffIFD#get(int)}
     * does not influence this selection.</p>
     *
     * <p>In contrast, the types {@link #SLONG} and {@link #SLONG8} are never
     * automatically adjusted based on the BigTIFF mode; their bit-width is strictly determined
     * by the explicit Java class used ({@link TagValue.SLong} vs {@link TagValue.SLong8}).</p>
     *
     * <p>For types that depend on the BigTIFF mode, the {@link #fromJavaType} method performs
     * automatic resolution to the appropriate version when the second
     * parameter is {@code true}.</p>
     *
     * @return {@code true} for {@link #LONG}, {@link #LONG8}, {@link #IFD}, and {@link #IFD8}.
     */
    public boolean isAutomaticallyAdjustedDependingOnBigTiffMode() {
        return this == LONG || this == LONG8 || this == IFD || this == IFD8;
    }

    public TagType bigTiffVersion() {
        return switch (this) {
            case LONG -> LONG8;
            case SLONG -> SLONG8;
            case IFD -> IFD8;
            default -> this;
        };
    }

    public TagType nonBigTiffVersion() {
        return switch (this) {
            case LONG8 -> LONG;
            case SLONG8 -> SLONG;
            case IFD8 -> IFD;
            default -> this;
        };
    }

    public boolean isBigTiffOnly() {
        return this == LONG8 || this == SLONG8 || this == IFD8;
    }

    public Class<?> javaType() {
        return javaTypes[0];
    }

    public static String toString(int type) {
        return fromTypeCode(type).map(Enum::name).orElseGet(() -> "Unknown type (" + type + ")");
    }

    public static Optional<TagType> fromJavaType(Class<?> javaType) {
        return fromJavaType(javaType, false);
    }

    public static Optional<TagType> fromJavaType(Class<?> javaType, boolean smartSelectDependingOnBigTiffMode) {
        Objects.requireNonNull(javaType, "Null javaType");
        TagType tagType = CLASS_LOOKUP.get(javaType);
        if (smartSelectDependingOnBigTiffMode && tagType != null && tagType.isAutomaticallyAdjustedDependingOnBigTiffMode()) {
            // LONG (unsigned int32), represented by Java long[], should be replaced with LONG8 in BigTIFF;
            // IFD (unsigned int32), represented by Java TagValue.IFD[], should be replaced with IFD8 in BigTIFF;
            // but for SLONG (signed int32) it is not necessary
            tagType = tagType.bigTiffVersion();
        }
        return Optional.ofNullable(tagType);
    }

    /**
     * Returns an {@link Optional} containing the {@link TagType} with the given {@link #typeCode()}.
     * <p>If no data kind with the specified name exists, an empty optional is returned.
     *
     * @param type the type code.
     * @return optional tag type.
     */
    public static Optional<TagType> fromTypeCode(int type) {
        return Optional.ofNullable(CODE_LOOKUP.get(type));
    }
}
