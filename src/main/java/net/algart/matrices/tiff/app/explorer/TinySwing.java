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

package net.algart.matrices.tiff.app.explorer;

import javax.swing.*;
import javax.swing.text.View;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.net.URL;
import java.util.Objects;

public class TinySwing {
    static URL reqResource(String name) {
        final URL result = TiffExplorer.class.getResource(name);
        Objects.requireNonNull(result, "Resource " + name + " not found");
        return result;
    }

    static JLabel leftLabel(String text) {
        JLabel label = newLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    static JLabel newLabel(String text) {
        JLabel label = new JLabel(text);
        final int maxWidth = (int) (Toolkit.getDefaultToolkit().getScreenSize().width * 0.9);
        // - limit the width to 90% of the screen to prevent the window from going off-screen
        Dimension sizes = label.getPreferredSize();
        if (sizes.width > maxWidth) {
            View view = (View) label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
            // - access the underlying HTML rendering engine (View)
            if (view != null) {
                // - for HTML, force the engine to wrap text within maxWidth;
                view.setSize(maxWidth, 0);
                // - height (0) is ignored as it's a dependent result in flow layout
                float height = view.getPreferredSpan(View.Y_AXIS);
                label.setPreferredSize(new Dimension(maxWidth, (int) height));
            } else {
                // - plain text: just clip the width
                label.setPreferredSize(new Dimension(maxWidth, sizes.height));
            }
        }
        return label;
    }

    static JComponent leftIndent(JComponent component, int gap) {
        Box box = Box.createHorizontalBox();
        box.add(Box.createHorizontalStrut(gap));
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(component);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        return box;
    }

    static void setWaitCursor(JFrame frame, boolean wait) {
        final Component glassPane = frame.getGlassPane();
        if (wait) {
            glassPane.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            glassPane.setVisible(true);
        } else {
            glassPane.setVisible(false);
            glassPane.setCursor(Cursor.getDefaultCursor());
        }
    }

    static void addActionOnEscape(JDialog dialog, Runnable action) {
        dialog.getRootPane().registerKeyboardAction(
                e -> action.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    static void addCloseOnEscape(JDialog dialog) {
        addActionOnEscape(dialog, () -> {
            if (dialog.getDefaultCloseOperation() == JDialog.DISPOSE_ON_CLOSE) {
                dialog.dispose();
            }
        });
    }

    static String smartHtmlLines(String htmlContent) {
        String[] lines = htmlContent.trim().split("<br>", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append("<div%s>%s</div>".formatted(i == 0 ? "" : " style=\"margin-top: 5px\"", lines[i]));
        }
        return "<html><body>" + sb + "</body></html>";
    }

    static Color getUIColor(String name, Color defaultValue) {
        final Color color = UIManager.getColor(name);
        return color != null ? color : defaultValue;
    }

    static File chooseFile(JFrame frame, JFileChooser chooser) {
        int result = chooser.showSaveDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return null;
        }
        if (file.exists()) {
            int answer = JOptionPane.showConfirmDialog(
                    frame,
                    "File already exists:\n" + file.getAbsolutePath() + "\nDo you want to replace it?",
                    "Confirm overwrite",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.YES_OPTION) {
                return null;
            }
        }
        return file;
    }
}
