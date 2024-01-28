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

import net.algart.matrices.tiff.TiffException;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.awt.image.ColorModel;
import java.io.IOException;
import java.util.HashMap;

// Reduced version of analogous SCIFIO class (for compatibility).
class HuffmanCodecReduced {
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


	// Reduced version of analogous SCIFIO class (for compatibility).
	static class HuffmanCodecOptions {
		int width;
		int height;
		int channels;
		int bitsPerSample;
		boolean littleEndian;
		boolean interleaved;
		boolean signed;
		int maxBytes;
		boolean lossless;
		ColorModel colorModel;
		double quality;
		boolean ycbcr;

		short[] table;
	}


	/**
	 * A class for reading arbitrary numbers of bits from a byte array.
	 *
	 * @author Eric Kjellman
	 */
	static class BitBuffer {

		// -- Constants --

		/** Various bitmasks for the 0000xxxx side of a byte. */
		private static final int[] BACK_MASK = { 0x00, // 00000000
				0x01, // 00000001
				0x03, // 00000011
				0x07, // 00000111
				0x0F, // 00001111
				0x1F, // 00011111
				0x3F, // 00111111
				0x7F // 01111111
		};

		/** Various bitmasks for the xxxx0000 side of a byte. */
		private static final int[] FRONT_MASK = { 0x0000, // 00000000
				0x0080, // 10000000
				0x00C0, // 11000000
				0x00E0, // 11100000
				0x00F0, // 11110000
				0x00F8, // 11111000
				0x00FC, // 11111100
				0x00FE // 11111110
		};

		private final byte[] byteBuffer;

		private int currentByte;

		private int currentBit;

		private final int eofByte;

		private boolean eofFlag;

		/** Default constructor. */
		public BitBuffer(final byte[] byteBuffer) {
			this.byteBuffer = byteBuffer;
			currentByte = 0;
			currentBit = 0;
			eofByte = byteBuffer.length;
		}

		public int getBits(int bitsToRead) {
			if (bitsToRead < 0) {
				throw new IllegalArgumentException("Bits to read may not be negative");
			}
			if (bitsToRead == 0) return 0;
			if (eofFlag) return -1; // Already at end of file
			int toStore = 0;
			while (bitsToRead != 0 && !eofFlag) {
				if (currentBit < 0 || currentBit > 7) {
					throw new IllegalStateException("byte=" + currentByte + ", bit = " +
							currentBit);
				}

				// if we need to read from more than the current byte in the
				// buffer...
				final int bitsLeft = 8 - currentBit;
				if (bitsToRead >= bitsLeft) {
					toStore <<= bitsLeft;
					bitsToRead -= bitsLeft;
					final int cb = byteBuffer[currentByte];
					if (currentBit == 0) {
						// we can read in a whole byte, so we'll do that.
						toStore += cb & 0xff;
					}
					else {
						// otherwise, only read the appropriate number of bits off
						// the back
						// side of the byte, in order to "finish" the current byte
						// in the
						// buffer.
						toStore += cb & BACK_MASK[bitsLeft];
						currentBit = 0;
					}
					currentByte++;
				}
				else {
					// We will be able to finish using the current byte.
					// read the appropriate number of bits off the front side of the
					// byte,
					// then push them into the int.
					toStore = toStore << bitsToRead;
					final int cb = byteBuffer[currentByte] & 0xff;
					toStore += (cb & (0x00FF - FRONT_MASK[currentBit])) >> (bitsLeft -
							bitsToRead);
					currentBit += bitsToRead;
					bitsToRead = 0;
				}
				// If we reach the end of the buffer, return what we currently have.
				if (currentByte == eofByte) {
					eofFlag = true;
					return toStore;
				}
			}
			return toStore;
		}
	}

	/**
	 * A class for writing arbitrary numbers of bits to a byte array.
	 *
	 * @author Curtis Rueden
	 */
	static class BitWriter {

		// -- Fields --

		/** Buffer storing all bits written thus far. */
		private byte[] buf;

		/** Byte index into the buffer. */
		private int index;

		/** Bit index into current byte of the buffer. */
		private int bit;

		// -- Constructors --

		/** Constructs a new bit writer. */
		public BitWriter() {
			this(10);
		}

		/** Constructs a new bit writer with the given initial buffer size. */
		public BitWriter(final int size) {
			buf = new byte[size];
		}

		// -- BitWriter API methods --

		/** Writes the given value using the given number of bits. */
		public void write(int value, final int numBits) {
			if (numBits <= 0) return;
			final byte[] bits = new byte[numBits];
			for (int i = 0; i < numBits; i++) {
				bits[i] = (byte) (value & 0x0001);
				value >>= 1;
			}
			for (int i = numBits - 1; i >= 0; i--) {
				final int b = bits[i] << (7 - bit);
				buf[index] |= b;
				bit++;
				if (bit > 7) {
					bit = 0;
					index++;
					if (index >= buf.length) {
						// buffer is full; increase the size
						final byte[] newBuf = new byte[buf.length * 2];
						System.arraycopy(buf, 0, newBuf, 0, buf.length);
						buf = newBuf;
					}
				}
			}
		}

		/** Gets an array containing all bits written thus far. */
		public byte[] toByteArray() {
			int size = index;
			if (bit > 0) size++;
			final byte[] b = new byte[size];
			System.arraycopy(buf, 0, b, 0, size);
			return b;
		}
	}

	static class ByteVector {

		private byte[] data;

		private int size;

		public ByteVector() {
			data = new byte[10];
			size = 0;
		}

		public ByteVector(final int initialSize) {
			data = new byte[initialSize];
			size = 0;
		}

		public ByteVector(final byte[] byteBuffer) {
			data = byteBuffer;
			size = 0;
		}

		public void add(final byte x) {
			while (size >= data.length)
				doubleCapacity();
			data[size++] = x;
		}

		public int size() {
			return size;
		}

		public byte get(final int index) {
			return data[index];
		}

		public void add(final byte[] array) {
			add(array, 0, array.length);
		}

		public void add(final byte[] array, final int off, final int len) {
			while (data.length < size + len)
				doubleCapacity();
			if (len == 1) data[size] = array[off];
			else if (len < 35) {
				// for loop is faster for small number of elements
				for (int i = 0; i < len; i++)
					data[size + i] = array[off + i];
			}
			else System.arraycopy(array, off, data, size, len);
			size += len;
		}

		void doubleCapacity() {
			final byte[] tmp = new byte[data.length * 2 + 1];
			System.arraycopy(data, 0, tmp, 0, data.length);
			data = tmp;
		}

		public void clear() {
			size = 0;
		}

		public byte[] toByteArray() {
			final byte[] bytes = new byte[size];
			System.arraycopy(data, 0, bytes, 0, size);
			return bytes;
		}

	}
	// -- Constants --

	private static final int LEAVES_OFFSET = 16;

	// -- Fields --

	private int leafCounter;

	private final HashMap<short[], Decoder> cachedDecoders = new HashMap<>();

	// -- Codec API methods --

	public byte[] decompress(final DataHandle<Location> in,
		final HuffmanCodecOptions options) throws IOException
	{
		if (in == null) throw new IllegalArgumentException(
			"No data to decompress.");
		if (options == null) {
			throw new TiffException("Options must be an instance of " +
				"loci.formats.codec.HuffmanCodecOptions.");
		}

		final HuffmanCodecOptions huffman = options;
		final byte[] pix = new byte[huffman.maxBytes];
		in.read(pix);

		final BitBuffer bb = new BitBuffer(pix);

		final int nSamples = (huffman.maxBytes * 8) / huffman.bitsPerSample;
		int bytesPerSample = huffman.bitsPerSample / 8;
		if ((huffman.bitsPerSample % 8) != 0) bytesPerSample++;

		final BitWriter out = new BitWriter();

		for (int i = 0; i < nSamples; i++) {
			final int sample = getSample(bb, options);
			out.write(sample, bytesPerSample * 8);
		}

		return out.toByteArray();
	}

	// -- HuffmanCodec API methods --

	public int getSample(final BitBuffer bb, final HuffmanCodecOptions options)
		throws TiffException
	{
		if (bb == null) {
			throw new IllegalArgumentException("No data to handle.");
		}
		if (options == null) {
			throw new TiffException("Options must be an instance of " +
				"loci.formats.codec.HuffmanCodecOptions.");
		}

		final HuffmanCodecOptions huffman = options;
		Decoder decoder = cachedDecoders.get(huffman.table);
		if (decoder == null) {
			decoder = new Decoder(huffman.table);
			cachedDecoders.put(huffman.table, decoder);
		}

		int bitCount = decoder.decode(bb);
		if (bitCount == 16) {
			return 0x8000;
		}
		if (bitCount < 0) bitCount = 0;
		int v = bb.getBits(bitCount) & ((int) Math.pow(2, bitCount) - 1);
		if ((v & (1 << (bitCount - 1))) == 0) {
			v -= (1 << bitCount) - 1;
		}

		return v;
	}

	// -- Helper class --

	class Decoder {

		public Decoder[] branch = new Decoder[2];

		private int leafValue = -1;

		public Decoder() {}

		public Decoder(final short[] source) {
			leafCounter = 0;
			createDecoder(this, source, 0, 0);
		}

		private Decoder createDecoder(final short[] source, final int start,
			final int level)
		{
			final Decoder dest = new Decoder();
			createDecoder(dest, source, start, level);
			return dest;
		}

		private void createDecoder(final Decoder dest, final short[] source,
			final int start, final int level)
		{
			int next = 0;
			int i = 0;
			while (i <= leafCounter && next < LEAVES_OFFSET) {
				i += source[start + next++] & 0xff;
			}

			if (level < next && next < LEAVES_OFFSET) {
				dest.branch[0] = createDecoder(source, start, level + 1);
				dest.branch[1] = createDecoder(source, start, level + 1);
			}
			else {
				i = start + LEAVES_OFFSET + leafCounter++;
				if (i < source.length) {
					dest.leafValue = source[i] & 0xff;
				}
			}
		}

		public int decode(final BitBuffer bb) {
			Decoder d = this;
			while (d.branch[0] != null) {
				final int v = bb.getBits(1);
				if (v < 0) break; // eof
				d = d.branch[v];
			}
			return d.leafValue;
		}

	}
}
