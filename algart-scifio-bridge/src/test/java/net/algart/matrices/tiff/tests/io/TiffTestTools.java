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

package net.algart.matrices.tiff.tests.io;

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.SimpleMemoryModel;
import net.algart.external.ExternalAlgorithmCaller;
import net.algart.external.ImageConversions;
import net.algart.matrices.tiff.TiffTools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

class TiffTestTools {
    private TiffTestTools() {
    }

    public static void writeImageFile(Path file, Matrix<? extends PArray> matrix) throws IOException {
        writeImageFile(file, matrix, false);
    }

    public static void writeImageFile(Path file, Matrix<? extends PArray> matrix, boolean interleaved)
            throws IOException {
        if (matrix.size() > 0) {
            // - BufferedImage cannot have zero sizes
            List<Matrix<? extends PArray>> image = interleaved ?
                    ImageConversions.unpack2DBandsFromSequentialSamples(null, intsToBytes(matrix)) :
                    TiffTools.splitChannels(intsToBytes(matrix));
            ExternalAlgorithmCaller.writeImage(file.toFile(), image);
        }
    }

    private static Matrix<? extends PArray> intsToBytes(Matrix<? extends PArray> matrix) {
        if (matrix.elementType() == int.class) {
            // - standard method ExternalAlgorithmCaller.writeImage uses AlgART interpretation: 2^31 is white;
            // it is incorrect for TIFF files
            final int[] ints = new int[(int) matrix.size()];
            matrix.array().getData(0, ints);
            byte[] bytes = new byte[ints.length];
            for (int k = 0; k < bytes.length; k++) {
                bytes[k] = (byte) (ints[k] >>> 24);
            }
            return matrix.matrix(SimpleMemoryModel.asUpdatableByteArray(bytes));
        }
        return matrix;
    }
}
