
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

import io.scif.AbstractSCIFIOPlugin;
import io.scif.FormatException;
import io.scif.codec.Codec;
import io.scif.codec.CodecOptions;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.util.Objects;

/**
 * BaseCodec contains default implementation and testing for classes
 * implementing the Codec interface, and acts as a base class for any of the
 * compression classes. Base 1D compression and decompression methods are not
 * implemented here, and are left as abstract. 2D methods do simple
 * concatenation and call to the 1D methods
 *
 * @author Eric Kjellman
 */
public abstract class AbstractCodec extends AbstractSCIFIOPlugin implements Codec
{
	// (It is placed here to avoid autocorrection by IntelliJ IDEA)
	/*
	 * #%L
	 * SCIFIO library for reading and converting scientific file formats.
	 * %%
	 * Copyright (C) 2011 - 2023 SCIFIO developers.
	 * %%
	 * Redistribution and use in source and binary forms, with or without
	 * modification, are permitted provided that the following conditions are met:
	 *
	 * 1. Redistributions of source code must retain the above copyright notice,
	 *    this list of conditions and the following disclaimer.
	 * 2. Redistributions in binary form must reproduce the above copyright notice,
	 *    this list of conditions and the following disclaimer in the documentation
	 *    and/or other materials provided with the distribution.
	 *
	 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
	 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
	 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
	 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
	 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
	 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
	 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
	 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
	 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
	 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
	 * POSSIBILITY OF SUCH DAMAGE.
	 * #L%
	 */

	// -- BaseCodec API methods --

	/**
	 * 2D data block encoding default implementation. This method simply
	 * concatenates data[0] + data[1] + ... + data[i] into a 1D block of data,
	 * then calls the 1D version of compress.
	 *
	 * @param data The data to be compressed.
	 * @param options Options to be used during compression, if appropriate.
	 * @return The compressed data.
	 * @throws FormatException If input is not a compressed data block of the
	 *           appropriate type.
	 */
	@Override
	public byte[] compress(final byte[][] data, final CodecOptions options)
		throws FormatException
	{
		int len = 0;
		for (int i = 0; i < data.length; i++) {
			len += data[i].length;
		}
		final byte[] toCompress = new byte[len];
		int curPos = 0;
		for (int i = 0; i < data.length; i++) {
			System.arraycopy(data[i], 0, toCompress, curPos, data[i].length);
			curPos += data[i].length;
		}
		return compress(toCompress, options);
	}

	@Override
	public byte[] decompress(final byte[] data) throws FormatException {
		return decompress(data, null);
	}

	@Override
	public byte[] decompress(final byte[][] data) throws FormatException {
		return decompress(data, null);
	}

	@Override
	public byte[] decompress(final byte[] data, final CodecOptions options)
		throws FormatException
	{
		try (DataHandle<Location> handle = getBytesHandle(new BytesLocation(data))) {
			return decompress(handle, options);
		}
		catch (final IOException e) {
			throw new FormatException(e);
		}
	}

	/**
	 * 2D data block decoding default implementation. This method simply
	 * concatenates data[0] + data[1] + ... + data[i] into a 1D block of data,
	 * then calls the 1D version of decompress.
	 *
	 * @param data The data to be decompressed.
	 * @return The decompressed data.
	 * @throws FormatException If input is not a compressed data block of the
	 *           appropriate type.
	 */
	@Override
	public byte[] decompress(final byte[][] data, final CodecOptions options)
		throws FormatException
	{
		if (data == null) throw new IllegalArgumentException(
			"No data to decompress.");
		int len = 0;
		for (final byte[] aData1 : data) {
			len += aData1.length;
		}
		final byte[] toDecompress = new byte[len];
		int curPos = 0;
		for (final byte[] aData : data) {
			System.arraycopy(aData, 0, toDecompress, curPos, aData.length);
			curPos += aData.length;
		}
		return decompress(toDecompress, options);
	}

	@SuppressWarnings("rawtypes, unchecked")
	private static DataHandle<Location> getBytesHandle(BytesLocation bytesLocation) {
		Objects.requireNonNull(bytesLocation, "Null bytesLocation");
		BytesHandle bytesHandle = new BytesHandle(bytesLocation);
		return (DataHandle) bytesHandle;
	}

}
