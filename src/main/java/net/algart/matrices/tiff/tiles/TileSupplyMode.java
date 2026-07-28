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

package net.algart.matrices.tiff.tiles;

/**
 * The mode specifying how to supply tiles from the image while loading samples
 * in methods such as {@link TiffIOMap#loadSampleBytes(int, int, int, int, boolean)}.
 */
public enum TileSupplyMode {
    /**
     * Default mode: if a non-empty tile already exists in the map, it is reused;
     * otherwise, it is supplied via the current {@link TileSupplier}.
     * This is the best choice for most situations.
     */
    IF_ABSENT,

    /**
     * The tile is always reloaded from the source image via the current {@link TileSupplier}.
     * If a tile with the same index already exists in the map, it is replaced with the newly loaded one.
     */
    ALWAYS;

    public boolean isReusingExisting() {
        return this == IF_ABSENT;
    }
}
