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

package net.algart.matrices.tiff.tests.scifio;

import io.scif.SCIFIO;
import io.scif.formats.tiff.TiffParser;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.io.File;

public class FileHandleGenericsBug {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + FileHandleGenericsBug.class.getName() + " some_tiff_file");
            return;
        }
        final File file = new File(args[0]);

        Context context = new SCIFIO().getContext();
        TiffParser parser = new TiffParser(context, new FileLocation(file));
        DataHandle<Location> stream = parser.getStream();
        System.out.println("Successfully opened: " + stream.getLocation());

        BytesLocation bytesLocation = new BytesLocation(1000);
        stream.set(bytesLocation); // - crash!! java.lang.ClassCastException

        System.out.println("This operator will not be performed");
        context.close();
    }
}
