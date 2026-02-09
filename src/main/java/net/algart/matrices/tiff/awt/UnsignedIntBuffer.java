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

/**
 * DataBuffer that stores unsigned ints.
 *
 * @author Melissa Linkert
 */
public class UnsignedIntBuffer extends DataBuffer {
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

    private final int[][] bankData;

    /**
     * Construct a new buffer of unsigned ints using the given int array.
     */
    public UnsignedIntBuffer(int[] dataArray, int size) {
        super(DataBuffer.TYPE_INT, size);
        bankData = new int[1][];
        bankData[0] = dataArray;
    }

    /**
     * Construct a new buffer of unsigned ints using the given 2D int array.
     */
    public UnsignedIntBuffer(int[][] dataArray, int size) {
//      super(DataBuffer.TYPE_INT, size); // SCIFIO BUG!
        super(DataBuffer.TYPE_INT, size, dataArray.length);
        bankData = dataArray;
    }

    public int[] getData() {
        return bankData[0];
    }

    public int[] getData(int bank) {
        return bankData[bank];
    }

    @Override
    public int getElem(int i) {
        return getElem(0, i);
    }

    @Override
    public int getElem(int bank, int i) {
        final int value = bankData[bank][i + getOffsets()[bank]];
        return (int) (value & 0xffffffffL);
    }

    @Override
    public float getElemFloat(int i) {
        return getElemFloat(0, i);
    }

    @Override
    public float getElemFloat(int bank, int i) {
        return (getElem(bank, i) & 0xffffffffL);
    }

    @Override
    public double getElemDouble(int i) {
        return getElemDouble(0, i);
    }

    @Override
    public double getElemDouble(int bank, int i) {
        return (getElem(bank, i) & 0xffffffffL);
    }

    @Override
    public void setElem(int i, int val) {
        setElem(0, i, val);
    }

    @Override
    public void setElem(int bank, int i, int val) {
        bankData[bank][i + getOffsets()[bank]] = val;
    }

    @Override
    public void setElemFloat(int i, float val) {
        setElemFloat(0, i, val);
    }

    @Override
    public void setElemFloat(int bank, int i, float val) {
        bankData[bank][i + getOffsets()[bank]] = (int) val;
    }

    @Override
    public void setElemDouble(int i, double val) {
        setElemDouble(0, i, val);
    }

    @Override
    public void setElemDouble(int bank, int i, double val) {
        bankData[bank][i + getOffsets()[bank]] = (int) val;
    }

}
