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

package net.algart.matrices.tiff.awt;

import java.awt.image.DataBuffer;
import java.awt.image.DataBufferShort;

/**
 * DataBuffer that stores signed shorts. SignedShortBuffer serves the same
 * purpose as java.awt.image.DataBufferShort; the only difference is that
 * SignedShortBuffer's getType() method returns DataBuffer.TYPE_USHORT. This is
 * a workaround for the fact that java.awt.image.BufferedImage does not support
 * DataBuffers with type DataBuffer.TYPE_SHORT.
 */
public class SignedShortBuffer extends DataBuffer {
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

    private final DataBufferShort helper;

    // -- Constructors --

    public SignedShortBuffer(int size) {
        super(DataBuffer.TYPE_USHORT, size);
        helper = new DataBufferShort(size);
    }

    public SignedShortBuffer(int size, int numbanks) {
        super(DataBuffer.TYPE_USHORT, size, numbanks);
        helper = new DataBufferShort(size, numbanks);
    }

    public SignedShortBuffer(short[] data, int size) {
        super(DataBuffer.TYPE_USHORT, size);
        helper = new DataBufferShort(data, size);
    }

    public SignedShortBuffer(short[] data, int size, int offset) {
        super(DataBuffer.TYPE_USHORT, size, 1, offset);
        helper = new DataBufferShort(data, size, offset);
    }

    public SignedShortBuffer(short[][] data, int size) {
        super(DataBuffer.TYPE_USHORT, size, data.length);
        helper = new DataBufferShort(data, size);
    }

    public SignedShortBuffer(short[][] data, int size, int[] offsets) {
        super(DataBuffer.TYPE_USHORT, size, data.length, offsets);
        helper = new DataBufferShort(data, size, offsets);
    }

    // -- DataBuffer API methods --

    public short[] getData() {
        return helper.getData();
    }

    public short[] getData(int bank) {
        return helper.getData(bank);
    }

    @Override
    public int getElem(int bank, int i) {
        return helper.getElem(bank, i);
    }

    @Override
    public void setElem(int bank, int i, int val) {
        helper.setElem(bank, i, val);
    }

}
