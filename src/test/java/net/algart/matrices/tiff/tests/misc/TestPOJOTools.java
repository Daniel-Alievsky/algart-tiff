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

package net.algart.matrices.tiff.tests.misc;

import java.io.OutputStream;
import java.io.PrintStream;

public class TestPOJOTools {
    @FunctionalInterface
    public interface MainMethod {
        void main(String[] args) throws Exception;
    }
    /**
     * Runs the standard {@code main(String[] args)} method with temporary disabling {@code System.out}.
     * Used in {@code xxxTest()} methods called by Maven test phase (POJO mode).
     * Please remember that {@code xxxTest()} methods must <b>not</b> be static.
     *
     * @param mainMethod standard Java {@code main} method.
     */
    public static void runTest(MainMethod mainMethod) throws Exception {
        System.out.println("Maven-style: calling main...");
        PrintStream oldOut = System.out;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            mainMethod.main(new String[0]);
        } finally {
            System.setOut(oldOut);
        }
    }
}
