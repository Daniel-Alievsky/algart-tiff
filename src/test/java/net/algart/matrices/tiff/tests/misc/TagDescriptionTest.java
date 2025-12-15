/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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
        System.out.printf("ImagetDescription: %s%n", ifd.optValue(Tags.IMAGE_DESCRIPTION, String.class));
        System.out.printf("getDescription: %s%n", ifd.getDescription());
        System.out.println(ifd.toString(TiffIFD.StringFormat.NORMAL));
        System.out.println(ifd.jsonString());
        System.out.println();
    }

    public static void main(String[] args) throws TiffException {
        TiffIFD ifd = new TiffIFD();
        printDescription(ifd);
        ifd.putDescription("Hello");
        printDescription(ifd);
        printDescription(ifd);
        ifd.putDescription("\u041F\u0440\u0438\u0432\u0435\u0442!");
        // - russian translation of "Hello"
        printDescription(ifd);
        printDescription(ifd);
        ifd.putDescription(TagDescription.EMPTY);
        printDescription(ifd);
        CustomDescription custom = new CustomDescription("Something");
        ifd.putDescription(custom);
        printDescription(ifd);
        ifd.putImageDimensions(100, 100);
        printDescription(ifd);
        if (ifd.getDescription() != custom) {
            throw new AssertionError();
        }
        ifd.putDescription("Hello again");
        printDescription(ifd);
    }
}
