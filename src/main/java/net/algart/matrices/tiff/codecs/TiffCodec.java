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

package net.algart.matrices.tiff.codecs;

import io.scif.codec.CodecOptions;
import net.algart.matrices.tiff.TiffException;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.io.IOException;

/**
 * This class is an analog of SCIFIO Codec interface, simplifying to use for TIFF encoding inside this library
 */
public interface TiffCodec {

	/**
	 * Compresses a block of data.
	 *
	 * @param data The data to be compressed.
	 * @param options Options to be used during compression, if appropriate.
	 * @return The compressed data.
	 * @throws TiffException If input is not a compressed data block of the
	 *           appropriate type.
	 */
	byte[] compress(byte[] data, CodecOptions options) throws TiffException;

	/**
	 * Decompresses a block of data.
	 *
	 * @param data the data to be decompressed
	 * @param options Options to be used during decompression.
	 * @return the decompressed data.
	 * @throws TiffException If data is not valid.
	 */
	byte[] decompress(byte[] data, CodecOptions options) throws TiffException;

	/**
	 * Decompresses data from the given DataHandle.
	 *
	 * @param in The stream from which to read compressed data.
	 * @param options Options to be used during decompression.
	 * @return The decompressed data.
	 * @throws TiffException If data is not valid compressed data for this
	 *           decompressor.
	 */
	byte[] decompress(DataHandle<Location> in, CodecOptions options) throws IOException;

}
