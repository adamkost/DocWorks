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
import java.io.InputStream;
import java.util.Locale;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/** Reads .docx files: text, run formatting, alignment, indents, spacing, lists and (flattened) tables. */
final class DocxReader {
    private final DocBuilder b = new DocBuilder();
    private int listCounter;

    private DocxReader() {}

    static DefaultStyledDocument read(InputStream in) throws IOException {
        try (XWPFDocument word = new XWPFDocument(in)) {
            DocxReader r = new DocxReader();
            for (IBodyElement e : word.getBodyElements()) {
                if (e instanceof XWPFParagraph) r.paragraph((XWPFParagraph) e);
                else if (e instanceof XWPFTable) r.table((XWPFTable) e);
            }
            return r.b.build();
        }
    }

    private void paragraph(XWPFParagraph p) {
        b.beginParagraph();
        String prefix = null;
        if (p.getNumID() != null) {
            listCounter++;
            String fmt = null;
            try { fmt = p.getNumFmt(); } catch (RuntimeException e) { /* numbering part missing: treat as bullet */ }
            prefix = (fmt == null || "bullet".equals(fmt)) ? "\u2022\t" : listCounter + ".\t";
        } else {
            listCounter = 0;
        }
        if (prefix != null) b.addRun(prefix, DocAttrs.plainChars());
        int heading = headingSize(p.getStyle());
        for (XWPFRun r : p.getRuns()) {
            String text = clean(r.text(), false);
            if (!text.isEmpty()) b.addRun(text, runAttrs(r, heading));
        }
        b.endParagraph(paragraphAttrs(p));
    }

    /** Tables are flattened to one paragraph per row, cells separated by tabs. */
    private void table(XWPFTable t) {
        for (XWPFTableRow row : t.getRows()) {
            b.beginParagraph();
            boolean firstCell = true;
            for (XWPFTableCell cell : row.getTableCells()) {
                if (!firstCell) b.addRun("\t", DocAttrs.plainChars());
                firstCell = false;
                boolean firstPara = true;
                for (XWPFParagraph p : cell.getParagraphs()) {
                    if (!firstPara) b.addRun(" ", DocAttrs.plainChars());
                    firstPara = false;
                    for (XWPFRun r : p.getRuns()) {
                        String text = clean(r.text(), true);
                        if (!text.isEmpty()) b.addRun(text, runAttrs(r, 0));
                    }
                }
            }
            b.endParagraph(DocAttrs.tableRow());
        }
    }

    private static String clean(String raw, boolean inCell) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\t') sb.append(inCell ? ' ' : '\t');
            else if (c == '\n' || c == '\r') sb.append(' ');
            else if (c >= 0x20) sb.append(c);
        }
        return sb.toString();
    }

    private static int headingSize(String style) {
        if (style == null) return 0;
        String s = style.toLowerCase(Locale.ROOT).replace(" ", "");
        switch (s) {
            case "title": return 26;
            case "heading1": return 20;
            case "heading2": return 16;
            case "heading3": return 14;
            default: return 0;
        }
    }

    private static SimpleAttributeSet runAttrs(XWPFRun r, int heading) {
        Double fs = r.getFontSizeAsDouble();
        int size = (fs != null && fs > 0) ? (int) Math.round(fs) : (heading > 0 ? heading : DocAttrs.DEFAULT_SIZE);
        boolean bold = r.isBold() || heading > 0;
        boolean underline = r.getUnderline() != UnderlinePatterns.NONE;
        return DocAttrs.chars(bold, r.isItalic(), underline, r.isStrikeThrough(),
                r.getFontFamily(), size, parseColor(r.getColor()));
    }

    private static Color parseColor(String hex) {
        if (hex == null || hex.length() != 6) return Color.BLACK; // also covers "auto"
        try {
            return new Color(Integer.parseInt(hex, 16));
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }

    private static SimpleAttributeSet paragraphAttrs(XWPFParagraph p) {
        int align = StyleConstants.ALIGN_LEFT;
        ParagraphAlignment pa = p.getAlignment();
        if (pa == ParagraphAlignment.CENTER) align = StyleConstants.ALIGN_CENTER;
        else if (pa == ParagraphAlignment.RIGHT) align = StyleConstants.ALIGN_RIGHT;
        else if (pa == ParagraphAlignment.BOTH || pa == ParagraphAlignment.DISTRIBUTE) align = StyleConstants.ALIGN_JUSTIFIED;
        float left = DocAttrs.clamp(Math.max(0, p.getIndentationLeft()) / 20f, 0f, 400f);
        float before = DocAttrs.clamp(Math.max(0, p.getSpacingBefore()) / 20f, 0f, 72f);
        float after = DocAttrs.clamp(Math.max(0, p.getSpacingAfter()) / 20f, 0f, 72f);
        return DocAttrs.paragraph(align, left, before, after);
    }
}
