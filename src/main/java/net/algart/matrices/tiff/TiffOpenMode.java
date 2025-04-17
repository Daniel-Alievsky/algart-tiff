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

package net.algart.matrices.tiff;

import org.scijava.io.handle.DataHandle;

/**
 * Mode of opening a TIFF file by {@link TiffReader}.
 * See comments to {@link TiffReader#TiffReader(DataHandle, TiffOpenMode)} constructor.
 */
public enum TiffOpenMode {
    /**
     * All exceptions are caught and not thrown, but can be retrieved using {@link TiffReader#openingException()}.
     * Usually not recommended.
     */
    NO_CHECKS(false, false),
    /**
     * No exceptions for non-existing file or non-TIFF file;
     * {@link TiffReader#isTiff()} should be checked before usage.
     */
    ALLOW_NON_TIFF(false, true),
    /**
     * The file must be a valid existing TIFF file, any problem leads to an exception.
     */
    VALID_TIFF(true, true);

    private final boolean requireValidTiff;
    private final boolean anythingChecked;

    TiffOpenMode(boolean requireValidTiff, boolean requireValid) {
        this.requireValidTiff = requireValidTiff;
        this.anythingChecked = requireValid;
    }

    /**
     * Equivalent to <code>requireValidTiff ? VALID_TIFF : ALLOW_NON_TIFF</code>.
     *
     * @param requireValidTiff whether the file must be a valid existing TIFF file.
     * @return {@link #VALID_TIFF} if <code>requireValidTiff</code> is <code>true</code>,
     * otherwise {@link #ALLOW_NON_TIFF}.
     */
    public static TiffOpenMode of(boolean requireValidTiff) {
        return requireValidTiff ? VALID_TIFF : ALLOW_NON_TIFF;
    }

    public boolean isRequireValidTiff() {
        return requireValidTiff;
    }

    public boolean isAnythingChecked() {
        return anythingChecked;
    }
}
