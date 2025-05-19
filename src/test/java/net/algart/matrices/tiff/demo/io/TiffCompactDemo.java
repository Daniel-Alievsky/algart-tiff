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

package net.algart.matrices.tiff.demo.io;

import net.algart.matrices.tiff.TiffCopier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffCompactDemo {
    private static final int DEFAULT_MAX_IN_MEMORY_TEMP_FILE_SIZE = 256 * 1024 * 1024; // 256 MB
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean repack = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-repack")) {
            repack = true;
            startArgIndex++;
        }
        boolean inMemory = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-inMemory")) {
            inMemory = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.printf("   [-repack] [-inMemory] %s file-to-compact.tiff%n",
                    TiffCompactDemo.class.getName());
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        System.out.printf("Compacting %s...%n", tiffFile);
        final TiffCopier copier = new TiffCopier();
        copier.setProgressUpdater(p -> {
            if (p.isCopyingTemporaryFile()) {
                System.out.printf("\rCopying temporary file...%20s", "");
            } else {
                System.out.printf("\rImage %d/%d, tile %d/%d...",
                        p.imageIndex() + 1, p.imageCount(),
                        p.tileIndex() + 1, p.tileCount());
            }
        });
        // copier.setTemporaryFileCreator(() -> Files.createTempFile(Path.of("/tmp/"), "my-", ".tmp"));
        // - uncomment if you want to change the way of creating temporary files
        copier.setDirectCopy(!repack);
        if (inMemory) {
            copier.setMaxInMemoryTempFileSize(DEFAULT_MAX_IN_MEMORY_TEMP_FILE_SIZE);
        }
        long t1 = System.nanoTime();
        copier.compact(tiffFile);
        long t2 = System.nanoTime();
        System.out.printf("%nDone in %.3f seconds%n", (t2 - t1) * 1e-9);
    }
}
