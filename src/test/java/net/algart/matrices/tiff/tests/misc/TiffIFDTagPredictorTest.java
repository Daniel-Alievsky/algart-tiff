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
import net.algart.matrices.tiff.data.TiffPrediction;
import net.algart.matrices.tiff.tags.TagPredictor;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;

public class TiffIFDTagPredictorTest {
    private static void check(TiffIFD ifd, TagPredictor requiredPredictor, int requiredCode, boolean requiredContains) {
        if (ifd.optPredictor() != requiredPredictor) {
            throw new AssertionError("Invalid predictor");
        }
        if (ifd.optPredictorCode() != requiredCode) {
            throw new AssertionError("Invalid code " + ifd.optPredictorCode());
        }
        if (ifd.containsKey(Tags.PREDICTOR) != requiredContains) {
            throw new AssertionError("Invalid containsKey() = " + ifd.containsKey(Tags.PREDICTOR));
        }
        String diagnostic = "O'k";
        try {
            TiffPrediction.subtractPredictionIfRequested(
                    new TiffMap(ifd, true).getOrNew(0, 0).fillWhenEmpty());
        } catch (TiffException e) {
            diagnostic = e.getMessage();
        }
        System.out.printf("%s: %s%s; subtractPredictionIfRequested: %s%n",
                ifd.optPredictorCode(),
                ifd.optPredictor(),
                ifd.containsKey(Tags.PREDICTOR) ? "" : " (no tag)",
                diagnostic);
    }

    public static void main(String[] args) {
        TiffIFD ifd = new TiffIFD().defaultTileSizes();
        check(ifd, TagPredictor.NONE, 1, false);

        ifd.putPredictor(TagPredictor.HORIZONTAL);
        check(ifd, TagPredictor.HORIZONTAL, 2, true);

        ifd.putPredictor(TagPredictor.HORIZONTAL_FLOATING_POINT);
        check(ifd, TagPredictor.HORIZONTAL_FLOATING_POINT, 3, true);

        ifd.putPredictor(TagPredictor.NONE);
        check(ifd, TagPredictor.NONE, 1, false);

        ifd.putPredictor(TagPredictor.UNKNOWN);
        // - must not change the situation!
        check(ifd, TagPredictor.NONE, 1, false);

        ifd.put(Tags.PREDICTOR, 157);
        check(ifd, TagPredictor.UNKNOWN, 157, true);

        ifd.put(Tags.PREDICTOR, -1);
        check(ifd, TagPredictor.UNKNOWN, -1, true);

        ifd.putPredictor(TagPredictor.UNKNOWN);
        check(ifd, TagPredictor.UNKNOWN, -1, true);
    }
}
