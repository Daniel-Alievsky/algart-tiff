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

import java.io.IOException;

/**
 * Mode of creating/opening TIFF file by {@link TiffWriter}.
 * See comments to {@link TiffWriter#TiffWriter(java.nio.file.Path, TiffCreateMode)} constructor.
 */
public enum TiffCreateMode {
    NO_ACTIONS(false, false, false) {
        @Override
        public void customize(TiffWriter writer) {
        }
    },
    CREATE(true, false, false),
    CREATE_BIG(true, true, false),
    CREATE_LE(true, false, true),
    CREATE_LE_BIG(true, true, true),
    OPEN_FOR_APPEND(false, false, false),
    OPEN_EXISTING(false, false, false);

    private final boolean createNewFile;
    private final boolean bigTiff;
    private final boolean littleEndian;

    TiffCreateMode(boolean createNewFile, boolean bigTiff, boolean littleEndian) {
        this.createNewFile = createNewFile;
        this.bigTiff = bigTiff;
        this.littleEndian = littleEndian;
    }

    public boolean isCreateNewFile() {
        return createNewFile;
    }

    public boolean isBigTiff() {
        return bigTiff;
    }

    public boolean isLittleEndian() {
        return littleEndian;
    }

    public static TiffCreateMode createNew(boolean bigTiff, boolean littleEndian) {
        return bigTiff ?
                (littleEndian ? CREATE_LE_BIG : CREATE_BIG) :
                (littleEndian ? CREATE_LE : CREATE);
    }

    void customize(TiffWriter writer) throws IOException {
        if (bigTiff) {
            writer.setBigTiff(true);
        }
        if (littleEndian) {
            writer.setLittleEndian(true);
        }
        if (createNewFile) {
            writer.create();
        } else {
            writer.open(this == OPEN_FOR_APPEND);
        }
    }
}
