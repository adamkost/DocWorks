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
package me.kost.adam.dev.docworks.io;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/** Writes a Swing {@link StyledDocument} as a .docx file. */
final class DocxWriter {
    private DocxWriter() {}

    private static final class Seg {
        final String text;
        final AttributeSet attrs;
        Seg(String text, AttributeSet attrs) { this.text = text; this.attrs = attrs; }
    }

    static void write(StyledDocument doc, OutputStream out) throws IOException {
        try (XWPFDocument word = new XWPFDocument()) {
            Element root = doc.getDefaultRootElement();
            int n = root.getElementCount();
            boolean lastWasTable = false;
            int i = 0;
            while (i < n) {
                if (DocAttrs.isTableRow(root.getElement(i).getAttributes())) {
                    List<Element> rows = new ArrayList<>();
                    while (i < n && DocAttrs.isTableRow(root.getElement(i).getAttributes())) rows.add(root.getElement(i++));
                    table(doc, word, rows);
                    lastWasTable = true;
                } else {
                    paragraph(doc, word.createParagraph(), root.getElement(i++));
                    lastWasTable = false;
                }
            }
            // Word requires a paragraph after a table at the end of the body
            if (lastWasTable || n == 0) word.createParagraph();
            word.write(out);
        }
    }

    private static void paragraph(StyledDocument doc, XWPFParagraph p, Element para) {
        AttributeSet pa = para.getAttributes();
        int align = StyleConstants.getAlignment(pa);
        if (align == StyleConstants.ALIGN_CENTER) p.setAlignment(ParagraphAlignment.CENTER);
        else if (align == StyleConstants.ALIGN_RIGHT) p.setAlignment(ParagraphAlignment.RIGHT);
        else if (align == StyleConstants.ALIGN_JUSTIFIED) p.setAlignment(ParagraphAlignment.BOTH);
        else p.setAlignment(ParagraphAlignment.LEFT);

        float left = StyleConstants.getLeftIndent(pa);
        if (left > 0) p.setIndentationLeft(Math.round(left * 20));
        float above = StyleConstants.getSpaceAbove(pa);
        if (above > 0) p.setSpacingBefore(Math.round(above * 20));
        float below = StyleConstants.getSpaceBelow(pa);
        if (below > 0) p.setSpacingAfter(Math.round(below * 20));

        for (int k = 0; k < para.getElementCount(); k++) {
            Element leaf = para.getElement(k);
            String text = leafText(doc, leaf);
            if (!text.isEmpty()) run(p, leaf.getAttributes(), text);
        }
    }

    private static void table(StyledDocument doc, XWPFDocument word, List<Element> rows) {
        List<List<List<Seg>>> data = new ArrayList<>();
        int cols = 1;
        for (Element row : rows) {
            List<List<Seg>> cells = splitCells(doc, row);
            data.add(cells);
            cols = Math.max(cols, cells.size());
        }
        XWPFTable table = word.createTable(rows.size(), cols);
        for (int r = 0; r < data.size(); r++) {
            List<List<Seg>> cells = data.get(r);
            for (int c = 0; c < cells.size(); c++) {
                XWPFTableCell cell = table.getRow(r).getCell(c);
                List<XWPFParagraph> ps = cell.getParagraphs();
                XWPFParagraph cp = ps.isEmpty() ? cell.addParagraph() : ps.get(0);
                for (Seg s : cells.get(c)) run(cp, s.attrs, s.text);
            }
        }
    }

    /** Splits one table-row paragraph at its tab characters into cells, keeping the formatting of each piece. */
    private static List<List<Seg>> splitCells(StyledDocument doc, Element row) {
        List<List<Seg>> cells = new ArrayList<>();
        cells.add(new ArrayList<>());
        for (int k = 0; k < row.getElementCount(); k++) {
            Element leaf = row.getElement(k);
            String text = leafText(doc, leaf);
            int start = 0;
            while (true) {
                int tab = text.indexOf('\t', start);
                String piece = tab < 0 ? text.substring(start) : text.substring(start, tab);
                if (!piece.isEmpty()) cells.get(cells.size() - 1).add(new Seg(piece, leaf.getAttributes()));
                if (tab < 0) break;
                cells.add(new ArrayList<>());
                start = tab + 1;
            }
        }
        return cells;
    }

    private static void run(XWPFParagraph p, AttributeSet a, String text) {
        XWPFRun r = p.createRun();
        r.setBold(StyleConstants.isBold(a));
        r.setItalic(StyleConstants.isItalic(a));
        if (StyleConstants.isUnderline(a)) r.setUnderline(UnderlinePatterns.SINGLE);
        r.setStrikeThrough(StyleConstants.isStrikeThrough(a));
        r.setFontFamily(StyleConstants.getFontFamily(a));
        r.setFontSize(StyleConstants.getFontSize(a));
        Color c = StyleConstants.getForeground(a);
        int rgb = c.getRGB() & 0xFFFFFF;
        if (rgb != 0) r.setColor(String.format("%06X", rgb));
        emit(r, text);
    }

    private static void emit(XWPFRun r, String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                flush(r, sb);
                r.addTab();
            } else if (c == '\n') {
                flush(r, sb);
                r.addBreak();
            } else if (c >= 0x20) {
                sb.append(c);
            } // other control characters are not legal in XML and are dropped
        }
        flush(r, sb);
    }

    private static void flush(XWPFRun r, StringBuilder sb) {
        if (sb.length() > 0) {
            r.setText(sb.toString());
            sb.setLength(0);
        }
    }

    /** Text of a leaf element without the paragraph-ending newline. */
    private static String leafText(StyledDocument doc, Element leaf) {
        int s = leaf.getStartOffset();
        int e = Math.min(leaf.getEndOffset(), doc.getLength() + 1);
        if (e <= s) return "";
        try {
            String t = doc.getText(s, e - s);
            return t.endsWith("\n") ? t.substring(0, t.length() - 1) : t;
        } catch (BadLocationException ex) {
            return "";
        }
    }
}
