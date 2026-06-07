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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.tags.TagType;

public class TagTypeTest {
    private static void checkType(TagType type) {
        System.out.printf("TagType: %s, code %d, %d bits, %s%s%s%s%n",
                type,
                type.typeCode(),
                type.bitsPerElement(),
                type.isSigned() ? "signed" : "unsigned",
                type.isBigTiffOnly() ? ", Big-TIFF only" : "",
                type.bigTiffVersion() == type ? "" : ", Big-TIFF version: " + type.bigTiffVersion(),
                type.nonBigTiffVersion() == type ? "" : ", non-Big-TIFF version: " + type.nonBigTiffVersion());
        Class<?> javaType = type.javaType();
        Object javaArray = java.lang.reflect.Array.newInstance(javaType, 4);
        System.out.printf("  mapped to Java type: %s, array: %s%n",
                javaType.getSimpleName(),
                javaArray.getClass().getSimpleName());
        if (TagType.fromTypeCode(type.typeCode()).orElseThrow() != type) {
            throw new AssertionError("Error in fromTypeCode");
        }
        TagType backType = TagType.fromJavaType(javaType, type.isBigTiffOnly()).orElseThrow();
        // For SLONG8, the second parameter is not necessary;
        // but for LONG8, without it backType will be LONG
        if (backType != type) {
            throw new AssertionError("Error in fromJavaType: " + backType);
        }
        backType = TagType.fromJavaType(javaArray.getClass(), type.isBigTiffOnly()).orElseThrow();
        if (backType != type) {
            throw new AssertionError("Error in fromJavaType (array): " + backType);
        }
    }

    public void test() throws Exception {
        //noinspection Convert2MethodRef
        TestPOJOTools.runTest(args -> main(args));
    }

    public static void main(String[] args) {
        for (TagType type : TagType.values()) {
            checkType(type);
        }
        System.out.println();
        checkType(TagType.fromJavaType(Byte.class, false).orElseThrow());
        checkType(TagType.fromJavaType(Short.class, false).orElseThrow());
        checkType(TagType.fromJavaType(Integer.class, false).orElseThrow());
        checkType(TagType.fromJavaType(Long.class, false).orElseThrow());
        checkType(TagType.fromJavaType(Float.class, false).orElseThrow());
        checkType(TagType.fromJavaType(Double.class, false).orElseThrow());
        if (TagType.fromJavaType(Boolean.class, false).isPresent()) {
            throw new AssertionError();
        }
    }
}
