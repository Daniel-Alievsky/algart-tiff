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

package net.algart.matrices.tiff.codecs;

import java.util.Arrays;

/**
 * Options for compressing and decompressing JPEG-2000 data.
 */
public class JPEG2000CodecOptions extends TiffCodec.Options {

	// -- Fields --

	/**
	 * The maximum code-block size to use per tile-component as it would be
	 * provided to: {@code J2KImageWriteParam#setCodeBlockSize(int[])} (WRITE).
	 */
	int[] codeBlockSize = null;

	/**
	 * The number of decomposition levels as would be provided to:
	 * {@code J2KImageWriteParam#setNumDecompositionLevels(int)} (WRITE). Leaving
	 * this value {@code null} signifies that when a JPEG 2000 parameter set is
	 * created for the purposes of compression the number of decomposition levels
	 * will be left as the default.
	 */
	Integer numDecompositionLevels = null;

	/**
	 * The resolution level as would be provided to:
	 * {@code J2KImageWriteParam#setResolution(int)} (READ). Leaving this value
	 * {@code null} signifies that when a JPEG 2000 parameter set is created for
	 * the purposes of compression the number of decomposition levels will be left
	 * as the default.
	 */
	Integer resolution = null;

	// -- Constructors --

	/** Creates a new instance. */
	public JPEG2000CodecOptions() {
		super();
	}

	public int[] getCodeBlockSize() {
		return codeBlockSize == null ? null : codeBlockSize.clone();
	}

	public JPEG2000CodecOptions setCodeBlockSize(int[] codeBlockSize) {
		this.codeBlockSize = codeBlockSize == null ? null : codeBlockSize.clone();
		return this;
	}

	public Integer getNumDecompositionLevels() {
		return numDecompositionLevels;
	}

	public JPEG2000CodecOptions setNumDecompositionLevels(Integer numDecompositionLevels) {
		this.numDecompositionLevels = numDecompositionLevels;
		return this;
	}

	public Integer getResolution() {
		return resolution;
	}

	public JPEG2000CodecOptions setResolution(Integer resolution) {
		this.resolution = resolution;
		return this;
	}

	public static JPEG2000CodecOptions getDefaultOptions(final TiffCodec.Options options) {
		final JPEG2000CodecOptions j2kOptions = new JPEG2000CodecOptions();
		j2kOptions.setTo(options);

		j2kOptions.setQuality(j2kOptions.lossless ? Double.MAX_VALUE : 10);
		j2kOptions.codeBlockSize = new int[] { 64, 64 };

		return j2kOptions;
	}

	@Override
	public String toString() {
		return super.toString() +
				", codeBlockSize=" + Arrays.toString(codeBlockSize) +
				", numDecompositionLevels=" + numDecompositionLevels +
				", resolution=" + resolution;
	}

	@Override
	public JPEG2000CodecOptions clone() {
		JPEG2000CodecOptions result = (JPEG2000CodecOptions) super.clone();
		result.codeBlockSize = this.codeBlockSize.clone();
		return result;
	}
}
