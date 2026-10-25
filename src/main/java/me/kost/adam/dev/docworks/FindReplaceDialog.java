/*
 * DocWorks - a small word processor
 * Copyright (C) 2026 Adam Kost
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the
 * Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.kost.adam.dev.docworks;

import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyledDocument;

/** Non-modal Find and Replace window for one editor. Matching is literal text. */
final class FindReplaceDialog extends JDialog {
    private final JTextPane editor;
    private final JTextField findField = new JTextField(24);
    private final JTextField replaceField = new JTextField(24);
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JLabel status = new JLabel(" ");

    FindReplaceDialog(JFrame owner, JTextPane editor) {
        super(owner, "Find and Replace", false);
        this.editor = editor;

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; form.add(new JLabel("Find:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(findField, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(new JLabel("Replace with:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(replaceField, c);
        c.gridx = 1; c.gridy = 2; c.fill = GridBagConstraints.NONE; form.add(matchCase, c);
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; form.add(status, c);

        JButton next = new JButton("Find Next");
        JButton replace = new JButton("Replace");
        JButton replaceAll = new JButton("Replace All");
        JButton close = new JButton("Close");
        next.addActionListener(e -> findNext());
        replace.addActionListener(e -> replaceCurrent());
        replaceAll.addActionListener(e -> replaceAll());
        close.addActionListener(e -> setVisible(false));
        findField.addActionListener(e -> findNext());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(next);
        buttons.add(replace);
        buttons.add(replaceAll);
        buttons.add(close);

        setLayout(new java.awt.BorderLayout());
        add(form, java.awt.BorderLayout.CENTER);
        add(buttons, java.awt.BorderLayout.SOUTH);
        getRootPane().setDefaultButton(next);
        pack();
        setLocationRelativeTo(owner);
    }

    void open() {
        String sel = editor.getSelectedText();
        if (sel != null && !sel.isEmpty() && sel.indexOf('\n') < 0) findField.setText(sel);
        status.setText(" ");
        if (!isVisible()) setLocationRelativeTo(getOwner());
        setVisible(true);
        toFront();
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    private Pattern pattern() {
        String q = findField.getText();
        if (q.isEmpty()) {
            status.setText("Enter the text to find.");
            return null;
        }
        int flags = matchCase.isSelected() ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return Pattern.compile(Pattern.quote(q), flags);
    }

    private String documentText() {
        Document d = editor.getDocument();
        try {
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    private boolean findNext() {
        Pattern p = pattern();
        if (p == null) return false;
        Matcher m = p.matcher(documentText());
        boolean wrapped = false;
        boolean found = m.find(Math.min(editor.getSelectionEnd(), documentText().length()));
        if (!found) {
            found = m.find(0);
            wrapped = found;
        }
        if (!found) {
            status.setText("Not found.");
            return false;
        }
        editor.select(m.start(), m.end());
        editor.getCaret().setSelectionVisible(true);
        status.setText(wrapped ? "Reached the end; continued from the top." : " ");
        return true;
    }

    private void replaceCurrent() {
        Pattern p = pattern();
        if (p == null) return;
        String sel = editor.getSelectedText();
        if (sel != null && p.matcher(sel).matches()) {
            editor.replaceSelection(replaceField.getText());
        }
        findNext();
    }

    private void replaceAll() {
        Pattern p = pattern();
        if (p == null) return;
        Matcher m = p.matcher(documentText());
        List<int[]> hits = new ArrayList<>();
        while (m.find()) hits.add(new int[] {m.start(), m.end()});
        if (hits.isEmpty()) {
            status.setText("Not found.");
            return;
        }
        StyledDocument doc = editor.getStyledDocument();
        String replacement = replaceField.getText();
        try {
            for (int i = hits.size() - 1; i >= 0; i--) { // back to front so earlier offsets stay valid
                int start = hits.get(i)[0];
                int len = hits.get(i)[1] - start;
                AttributeSet attrs = new SimpleAttributeSet(doc.getCharacterElement(start).getAttributes());
                ((AbstractDocument) doc).replace(start, len, replacement, attrs);
            }
        } catch (BadLocationException e) {
            status.setText("Replace failed.");
            return;
        }
        status.setText("Replaced " + hits.size() + (hits.size() == 1 ? " occurrence." : " occurrences."));
    }
}
