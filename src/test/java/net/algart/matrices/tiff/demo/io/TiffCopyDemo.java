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

package net.algart.matrices.tiff.demo.io;

import net.algart.matrices.tiff.TiffCopier;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffCopyDemo {
    static long lastProgressTime = Integer.MIN_VALUE;
    static void updateProgress(TiffCopier.ProgressInformation p) {
        long t = System.currentTimeMillis();
        if (t - lastProgressTime > 500 || p.isLastTileCopied() || p.isCopyingTemporaryFile()) {
            if (p.isCopyingTemporaryFile()) {
                System.out.printf("\rCopying temporary file...%20s", "");
            } else if (p.imageCount() > 0) {
                // - we use methods like compact() or copyAllImages()
                System.out.printf("\rImage %d/%d, tile %d/%d...",
                        p.imageIndex() + 1, p.imageCount(),
                        p.tileIndex() + 1, p.tileCount());
            } else {
                // - we use methods copying a single image
                System.out.printf("\rTile %d/%d...", p.tileIndex() + 1, p.tileCount());
            }
            lastProgressTime = t;
        }
    }

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean repack = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-repack")) {
            repack = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("   [-repack] %s source.tiff target.tiff%n",
                    TiffCopyDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex]);
        if (!repack) {
            System.out.printf("Direct copying %s to %s...", sourceFile, targetFile);
            // - simplest variant of usage:
            TiffCopier.copyFile(targetFile, sourceFile);
        } else {
            System.out.printf("Copying %s to %s with recompression...%n", sourceFile, targetFile);
            final TiffCopier copier = new TiffCopier();
            copier.setProgressUpdater(TiffCopyDemo::updateProgress);
            copier.setDirectCopy(false);
            // - unnecessary (it is the default); true value means the repack copy
            copier.copyAllTiff(targetFile, sourceFile);
        }
        System.out.printf("%nDone%n");
    }
}
