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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.tags.TagDescription;
import net.algart.matrices.tiff.tags.Tags;

public class TagDescriptionTest {
    static class CustomDescription extends TagDescription {
        public CustomDescription(String description) {
            super(description);
        }

        @Override
        public String application() {
            return "Test-app";
        }

        @Override
        public String formatName(boolean pretty) {
            return "test";
        }

        @Override
        public String toString() {
            return "Custom test description";
        }
    }

    private static void printDescription(TiffIFD ifd) {
        System.out.printf("optDescription: %s%n", ifd.optDescription());
        System.out.printf("getDescription: %s%n", ifd.getDescription());
        System.out.println(ifd.toString(TiffIFD.StringFormat.NORMAL));
        System.out.println(ifd.jsonString());
        System.out.println();
    }

    public static void main(String[] args) throws TiffException {
        TiffIFD ifd = new TiffIFD();
        printDescription(ifd);
        System.out.printf("--- \"Hello\" ---%n");
        ifd.putDescription("Hello");
        printDescription(ifd);
        printDescription(ifd);

        System.out.printf("--- \"\u041F\u0440\u0438\u0432\u0435\u0442!\" ---%n");
        ifd.putDescription("\u041F\u0440\u0438\u0432\u0435\u0442!");
        // - russian translation of "Hello"
        printDescription(ifd);
        printDescription(ifd);
        System.out.printf("%n--- Removing description: EMPTY ---%n");
        ifd.putDescription(TagDescription.EMPTY);
        printDescription(ifd);

        System.out.printf("--- Adding custom description ---%n");
        CustomDescription custom = new CustomDescription("Something");
        ifd.putDescription(custom);
        printDescription(ifd);
        ifd.putImageDimensions(100, 100);
        printDescription(ifd);
        if (ifd.getDescription() != custom) {
            throw new AssertionError();
        }

        System.out.printf("--- \"Hello again\" ---%n");
        ifd.putDescription("Hello again");
        printDescription(ifd);

        System.out.printf("--- \"Hello1\", \"Hello2\" ---%n");
        ifd.put(Tags.IMAGE_DESCRIPTION, new String[] {"Hello1", "Hello2"});
        printDescription(ifd);

        System.out.printf("--- Removing description: null ---%n");
        ifd.putDescription((String) null);
        printDescription(ifd);
    }
}
