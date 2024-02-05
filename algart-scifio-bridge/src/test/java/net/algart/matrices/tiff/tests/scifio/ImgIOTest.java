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
import io.scif.config.SCIFIOConfig;
import io.scif.img.ImgOpener;
import io.scif.img.ImgSaver;
import io.scif.img.SCIFIOImgPlus;
import org.scijava.Context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ImgIOTest {
    private static final int MAX_IMAGE_SIZE = 6000;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + ImgIOTest.class.getName()
                + " source_image_file result_image_file");

            return;
        }
        final Path imgFile = Paths.get(args[0]);
        final Path resultFile = Paths.get(args[1]);

        System.out.printf("Opening %s...%n", imgFile);
        try (Context context = new SCIFIO().getContext()) {
            ImgOpener imgOpener = new ImgOpener(context);
            List<SCIFIOImgPlus<?>> imgPluses = imgOpener.openImgs(imgFile.toString());

            System.out.printf("Saving %s...%n", resultFile);
            Files.deleteIfExists(resultFile);
            ImgSaver imgSaver = new ImgSaver(context);
            SCIFIOConfig scifioConfig = new SCIFIOConfig(context);
//            scifioConfig.writerSetCompression(CompressionType.JPEG);
            imgSaver.saveImg(resultFile.toString(), imgPluses.get(0), 0, scifioConfig);

            System.out.println("Done");
        }
    }
}
