/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.matrices.tiff.TiffIFD;

import java.util.Objects;

/**
 * TIFF tile index, based of row, column and IFD reference (not content).
 *
 * <p>Note: for hashing this object uses IFD identity hash code.
 * Of course, this  cannot provide a good universal hash,
 * but it is quite enough for our needs: usually we will not create new IDF.
 *
 * @author Denial Alievsky
 */
public final class TiffTileIndex {
    private final TiffMap map;
    private final TiffIFD ifd;
    private final int ifdIdentity;
    private final int channelPlane;
    private final int xIndex;
    private final int yIndex;
    private final int fromX;
    private final int fromY;
    private final int toX;
    private final int toY;

    /**
     * Creates new tile index.
     *
     * @param map          containing tile map.
     * @param channelPlane channel-plane index (used only in a case of
     *                     {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE},
     *                     always 0 for usual case {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED})
     * @param xIndex       x-index of the tile (0, 1, 2, ...).
     * @param yIndex       y-index of the tile (0, 1, 2, ...).
     */
    public TiffTileIndex(TiffMap map, int channelPlane, int xIndex, int yIndex) {
        Objects.requireNonNull(map, "Null containing tile map");
        if (!map.isPlanarSeparated()) {
            if (channelPlane != 0) {
                throw new IllegalArgumentException("Non-zero channelPlane = " + channelPlane
                        + " is allowed only in planar-separated images");
            }
        } else {
            assert map.numberOfSeparatedPlanes() == map.numberOfChannels();
            if (channelPlane < 0 || channelPlane >= map.numberOfChannels()) {
                throw new IllegalArgumentException("Index of channelPlane " + channelPlane +
                        " is out of range 0.." + (map.numberOfChannels() - 1));
            }
        }
        if (xIndex < 0) {
            throw new IllegalArgumentException("Negative x-index = " + xIndex);
        }
        if (yIndex < 0) {
            throw new IllegalArgumentException("Negative y-index = " + yIndex);
        }
        if (xIndex > TiffMap.MAX_TILE_INDEX) {
            throw new IllegalArgumentException("Too large x-index = " + xIndex + " > " + TiffMap.MAX_TILE_INDEX);
        }
        if (yIndex > TiffMap.MAX_TILE_INDEX) {
            throw new IllegalArgumentException("Too large y-index = " + yIndex + " > " + TiffMap.MAX_TILE_INDEX);
        }
        final long fromX = (long) xIndex * map.tileSizeX();
        final long fromY = (long) yIndex * map.tileSizeY();
        final long toX = fromX + map.tileSizeX();
        final long toY = fromY + map.tileSizeY();
        if (toX > Integer.MAX_VALUE || toY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large tile indexes (" + xIndex + ", " + yIndex +
                    ") lead to too large coordinates (" + (toX - 1) + ", " + (toY - 1) +
                    ") of right-bottom pixel of the tile: coordinates >= 2^31 are not supported");
        }
        // Note: we could check here also that x and y are in range of all tiles for this IFD, but it is a bad idea:
        // while building new TIFF, we probably do not know actual images sizes until getting all tiles.
        this.map = map;
        this.ifd = map.ifd();
        this.ifdIdentity = System.identityHashCode(ifd);
        // - not a universal solution, but suitable for our optimization needs:
        // usually we do not create new IFD instances without necessity
        this.channelPlane = channelPlane;
        this.xIndex = xIndex;
        this.yIndex = yIndex;
        this.fromX = (int) fromX;
        this.fromY = (int) fromY;
        this.toX = (int) toX;
        this.toY = (int) toY;
    }

    public TiffMap map() {
        return map;
    }

    public TiffIFD ifd() {
        return ifd;
    }

    public int channelPlane() {
        return channelPlane;
    }

    public int xIndex() {
        return xIndex;
    }

    public int yIndex() {
        return yIndex;
    }

    public int fromX() {
        return fromX;
    }

    public int fromY() {
        return fromY;
    }

    public int toX() {
        return toX;
    }

    public int toY() {
        return toY;
    }

    public int linearIndex() {
        return map.linearIndex(channelPlane, xIndex, yIndex);
    }

    public boolean isInBounds() {
        assert channelPlane < map.numberOfSeparatedPlanes() : "must be checked in the constructor!";
        return xIndex < map.gridTileCountX() && yIndex < map.gridTileCountY();
    }

    public void checkInBounds() {
        if (!isInBounds()) {
            throw new IllegalStateException("Tile index is out of current TIFF map grid sizes " +
                    map.gridTileCountX() + "x" + map.gridTileCountY() + ": " + this);
        }
    }

    @Override
    public String toString() {
        return "(" + xIndex + ", " + yIndex + ")" +
                (map.isPlanarSeparated() ? ", channel " + channelPlane : "") +
                " [coordinates (" + fromX + ", " + fromY + ")" +
                " in IFD @" + Integer.toHexString(ifdIdentity) + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TiffTileIndex that = (TiffTileIndex) o;
        return channelPlane == that.channelPlane && xIndex == that.xIndex && yIndex == that.yIndex && ifd == that.ifd;
        // - Important! Comparing references to IFD, not content and not tile map!
        // Different tile maps may refer to the same IFD;
        // on the other hand, we usually do not need to create identical IFDs.
    }

    @Override
    public int hashCode() {
        int result = ifdIdentity;
        result = 31 * result + channelPlane;
        result = 31 * result + yIndex;
        result = 31 * result + xIndex;
        return result;
    }
}
