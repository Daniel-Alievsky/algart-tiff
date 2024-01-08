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

import io.scif.FormatException;
import io.scif.UnsupportedCompressionException;
import io.scif.codec.Codec;
import io.scif.codec.CodecOptions;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;
import org.scijava.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * This class implements packbits decompression. Compression is not yet
 * implemented.
 *
 * @author Melissa Linkert
 */
@Plugin(type = Codec.class)
public class PackbitsCodec extends AbstractCodec {
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
	@Override
	public byte[] compress(final byte[] data, final CodecOptions options)
		throws FormatException
	{
		// TODO: Add compression support.
		throw new UnsupportedCompressionException(
			"Packbits Compression not currently supported");
	}

	/**
	 * The CodecOptions parameter should have the following fields set:
	 * {@link CodecOptions#maxBytes maxBytes}
	 *
	 * @see Codec#decompress(DataHandle, CodecOptions)
	 */
	@Override
	public byte[] decompress(final DataHandle<Location> in, CodecOptions options)
		throws FormatException, IOException
	{
		if (options == null) options = CodecOptions.getDefaultOptions();
		if (in == null) throw new IllegalArgumentException(
			"No data to decompress.");
		final long fp = in.offset();
		// Adapted from the TIFF 6.0 specification, page 42.
		final ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
		int nread = 0;
		while (output.size() < options.maxBytes) {
			final byte n = (byte) (in.read() & 0xff);
			nread++;
			if (n >= 0) { // 0 <= n <= 127
				byte[] b = new byte[n + 1];
				in.read(b);
				nread += n + 1;
				output.write(b);
				b = null;
			}
			else if (n != -128) { // -127 <= n <= -1
				final int len = -n + 1;
				final byte inp = (byte) (in.read() & 0xff);
				nread++;
				for (int i = 0; i < len; i++)
					output.write(inp);
			}
		}
		if (fp + nread < in.length()) in.seek(fp + nread);
		return output.toByteArray();
	}
}
