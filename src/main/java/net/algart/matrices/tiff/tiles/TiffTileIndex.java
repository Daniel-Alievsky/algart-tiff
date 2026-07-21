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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.util.Objects;

/**
 * TIFF tile index, based on row, column, channel plane and IFD reference (not content).
 *
 * <p>Note: for hashing, this object uses IFD identity hash code.
 * Of course, this cannot provide a good universal hash,
 * but it is quite enough for our needs: usually we will not create new IDF.
 */
public final class TiffTileIndex {
    private final TiffMap map;
    private final TiffIFD ifd;
    private final int ifdIdentity;
    private final int xIndex;
    private final int yIndex;
    private final int separatedPlaneIndex;
    private final int fromX;
    private final int fromY;
    private final int toX;
    private final int toY;

    /**
     * Creates a new tile index.
     *
     * @param map                 containing tile map.
     * @param xIndex              x-index of the tile (0, 1, 2, ...).
     * @param yIndex              y-index of the tile (0, 1, 2, ...).
     * @param separatedPlaneIndex channel-plane index (used only in the case of
     *                            {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE},
     *                            always 0 for usual case {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED})
     */
    public TiffTileIndex(TiffMap map, int xIndex, int yIndex, int separatedPlaneIndex) {
        Objects.requireNonNull(map, "Null containing tile map");
        if (!map.isPlanarSeparated()) {
            if (separatedPlaneIndex != 0) {
                throw new IllegalArgumentException("Non-zero separatedPlaneIndex = " + separatedPlaneIndex
                        + " is allowed only in planar-separated images");
            }
        } else {
            assert map.numberOfSeparatedPlanes() == map.numberOfChannels();
            if (separatedPlaneIndex < 0 || separatedPlaneIndex >= map.numberOfChannels()) {
                throw new IllegalArgumentException("Index of separatedPlaneIndex " + separatedPlaneIndex +
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
        this.xIndex = xIndex;
        this.yIndex = yIndex;
        this.separatedPlaneIndex = separatedPlaneIndex;
        this.fromX = (int) fromX;
        this.fromY = (int) fromY;
        this.toX = (int) toX;
        this.toY = (int) toY;
    }

    public TiffMap map() {
        assert map != null;
        return map;
    }

    public boolean isTiled() {
        return map.isTiled();
    }

    public TiffIFD ifd() {
        return ifd;
    }

    public int xIndex() {
        return xIndex;
    }

    public int yIndex() {
        return yIndex;
    }

    public int separatedPlaneIndex() {
        return separatedPlaneIndex;
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

    public int linear() {
        return map.linearIndex(xIndex, yIndex, separatedPlaneIndex);
    }

    public TiffTile existingTile() {
        return map.get(this);
    }

    public boolean isInBounds() {
        assert separatedPlaneIndex < map.numberOfSeparatedPlanes() : "must be checked in the constructor!";
        return xIndex < map.gridCountX() && yIndex < map.gridCountY();
    }

    /**
     * Checks whether a tile or strip with the specified byte count and file offset should be treated
     * as a "missing" (empty) tile, or if these combination of byte count and offset (one of which is zero)
     * is not allowed.
     *
     * <p>For tiled images (when {@link #isTiled()} returns {@code true}),
     * zero value of the {@code byteCount} argument
     * (considered to be a value of the {@code TileByteCounts} tag) may indicate a missing tile.
     * This is possible in "sparse" formats such as <b>Philips TIFF</b> and <b>ARGOS TIFF</b>.
     * In this case, if {@code missingTilesAllowed} is {@code true},
     * this method returns {@code true}, indicating the tile should be treated as empty.</p>

     * <p>If {@code byteCount} or {@code offset} argument is zero in any other cases (for example,
     * when {@link #isTiled()} returns {@code false},
     * or when {@code missingTilesAllowed} is {@code false}), this method
     * throws a {@link TiffException} with a detailed diagnostic message.</p>
     *
     * @param byteCount           the length of the tile or strip data in bytes.
     * @param offset              the offset of the tile or strip data in the stream.
     * @param missingTilesAllowed {@code true} if missing tiles are allowed; {@code false} otherwise.
     * @return {@code true} if this tile or strip is a valid missing tile that should be treated as empty;
     *         {@code false} if it contains valid data to be read from the file.
     * @throws TiffException if a zero byte count or offset is encountered in an invalid context.
     * @see TiffReader#setMissingTilesAllowed(boolean)
     * @see <a href="https://openslide.org/formats/generic-tiff/">OpenSlide: Generic tiled TIFF format</a>
     */
    public boolean checkMissingTile(int byteCount, long offset, boolean missingTilesAllowed) throws TiffException {
        final boolean tiled = isTiled();
        if (byteCount == 0 && tiled && missingTilesAllowed) {
            return true;
        }
        if (byteCount == 0 || offset == 0) {
            final String tileOrStrip = tiled ? "tile" : "strip";
            throw new TiffException("Zero " +
                    tileOrStrip +
                    (byteCount == 0 ? " byte-count" : " offset") +
                    " is not allowed " +
                    (tiled && missingTilesAllowed ?
                            "together with non-zero byte-count = " + byteCount :
                            (tiled ? "unless missingTilesAllowed flag is set" : "")) +
                    " (" + tileOrStrip + " " + this + ")");
        }
        return false;
    }

    public void checkInBounds() {
        if (!isInBounds()) {
            throw new IllegalStateException("Tile index is out of current TIFF map grid sizes " +
                    map.gridCountX() + "x" + map.gridCountY() + ": " + this);
        }
    }

    @Override
    public String toString() {
        return "(" + xIndex + ", " + yIndex + ")" +
                (map.isPlanarSeparated() ? ", channel " + separatedPlaneIndex : "") +
                " = " + linear() +
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
        return separatedPlaneIndex == that.separatedPlaneIndex && xIndex == that.xIndex && yIndex == that.yIndex && ifd == that.ifd;
        // - Important! Comparing references to IFD, not content and not tile map!
        // Different tile maps may refer to the same IFD;
        // on the other hand, we usually do not need to create identical IFDs.
    }

    @Override
    public int hashCode() {
        int result = ifdIdentity;
        result = 31 * result + separatedPlaneIndex;
        result = 31 * result + yIndex;
        result = 31 * result + xIndex;
        return result;
    }
}
