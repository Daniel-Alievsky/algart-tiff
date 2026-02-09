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

package net.algart.matrices.tiff;

import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@FunctionalInterface
public interface TempTiffFileCreator {
    DataHandle<?> createTempFile(TiffReader existingTiffReader) throws IOException;

    class Default implements TempTiffFileCreator {
        private long maxTiffFileSizeForMemory = 1024 * 1024 * 1024L;

        public long getMaxTiffFileSizeForMemory() {
            return maxTiffFileSizeForMemory;
        }

        public Default setMaxTiffFileSizeForMemory(long maxTiffFileSizeForMemory) {
            if (maxTiffFileSizeForMemory < 0) {
                throw new IllegalArgumentException("Negative maxTiffFileSizeForMemory = " + maxTiffFileSizeForMemory);
            }
            this.maxTiffFileSizeForMemory = maxTiffFileSizeForMemory;
            return this;
        }

        @Override
        public DataHandle<?> createTempFile(TiffReader existingTiffReader) throws IOException {
            if (existingTiffReader.fileLength() < maxTiffFileSizeForMemory) {
                return new BytesHandle();

            }
            Path tempFile = Files.createTempFile(null, null);
            tempFile.toFile().deleteOnExit();
            return TiffIO.getFileHandle(tempFile);
        }
    }
}
