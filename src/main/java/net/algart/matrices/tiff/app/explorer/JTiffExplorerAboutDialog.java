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
import java.awt.*;

public class JTiffExplorerAboutDialog extends JDialog {
    public static final String ALGART_TIFF_WEBSITE = "https://algart.net/java/AlgART-TIFF/";

    public JTiffExplorerAboutDialog(Frame frame) {
        super(frame, "About TIFF Explorer", true);
        this.setLayout(new BorderLayout(10, 10));
        this.setMinimumSize(new Dimension(100, 200));
        this.setResizable(false);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 50, 10, 50));

        JLabel icon = new JLabel(new ImageIcon(TiffExplorer.reqResource("TiffExplorer_icon_32.png")));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(icon);
        content.add(Box.createVerticalStrut(16));

        JLabel title = centeredLabel("AlgART TIFF Explorer");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        content.add(title);
        content.add(Box.createVerticalStrut(8));

        content.add(centeredLabel("By Daniel Alievsky (AlgART Laboratory)"));
        content.add(centeredLabel("Version 1.5.2"));
        content.add(Box.createVerticalStrut(8));

        JLabel link = centeredLabel("<html><a href=\"\">" + ALGART_TIFF_WEBSITE + "</a></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new java.net.URI(ALGART_TIFF_WEBSITE));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(JTiffExplorerAboutDialog.this,
                            "Cannot open browser:\n" + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        content.add(Box.createVerticalStrut(10));
        content.add(link);

        this.add(content, BorderLayout.CENTER);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> this.dispose());
        JPanel btnPanel = new JPanel();
        btnPanel.add(ok);

        this.add(btnPanel, BorderLayout.SOUTH);

        this.pack();
        TiffExplorer.addCloseOnEscape(this);
        this.setLocationRelativeTo(frame);
    }

    private static JLabel centeredLabel(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }
}
