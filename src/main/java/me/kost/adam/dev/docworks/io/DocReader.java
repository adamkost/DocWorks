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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;

/**
 * Reads legacy Word 97-2003 (.doc) files. Text, character formatting (bold, italic, underline,
 * strike-through, font, size, colour), alignment, indents, spacing, bullet paragraphs and table
 * rows are imported. Images, headers, footers, footnotes and fields' code text are not.
 */
final class DocReader {
    private static final char CELL_MARK = '\u0007';

    private final DocBuilder b = new DocBuilder();
    /** One entry per open field; TRUE while we are inside its instruction part (which is not shown). */
    private final Deque<Boolean> fields = new ArrayDeque<>();
    private boolean rowOpen;
    private boolean cellStart = true;
    private int cellIndex;

    private DocReader() {}

    static DefaultStyledDocument read(InputStream in) throws IOException {
        try (HWPFDocument word = new HWPFDocument(in)) {
            DocReader r = new DocReader();
            Range range = word.getRange();
            int n = range.numParagraphs();
            for (int i = 0; i < n; i++) r.paragraph(range.getParagraph(i));
            r.finishRow();
            return r.b.build();
        }
    }

    private void paragraph(Paragraph p) {
        if (p.isInTable()) {
            tableParagraph(p);
            return;
        }
        finishRow();
        b.beginParagraph();
        if (p.isInList()) b.addRun("\u2022\t", DocAttrs.plainChars());
        appendRuns(p, false);
        b.endParagraph(paragraphAttrs(p));
    }

    private void tableParagraph(Paragraph p) {
        if (!rowOpen) {
            b.beginParagraph();
            rowOpen = true;
        }
        if (p.isTableRowEnd()) {
            finishRow();
            return;
        }
        if (cellStart) {
            if (cellIndex > 0) b.addRun("\t", DocAttrs.plainChars());
            cellStart = false;
        } else {
            b.addRun(" ", DocAttrs.plainChars()); // a second paragraph inside the same cell
        }
        boolean endsCell = p.text().endsWith(String.valueOf(CELL_MARK));
        appendRuns(p, true);
        if (endsCell) {
            cellIndex++;
            cellStart = true;
        }
    }

    private void finishRow() {
        if (!rowOpen) return;
        b.endParagraph(DocAttrs.tableRow());
        rowOpen = false;
        cellStart = true;
        cellIndex = 0;
    }

    private void appendRuns(Paragraph p, boolean inCell) {
        int runs = p.numCharacterRuns();
        for (int j = 0; j < runs; j++) {
            CharacterRun r = p.getCharacterRun(j);
            String text = visibleText(r.text(), inCell); // always filter: field state must be tracked
            if (text.isEmpty() || r.isVanished()) continue;
            b.addRun(text, charAttrs(r));
        }
    }

    /** Strips Word's control characters and the instruction text of fields. */
    private String visibleText(String raw, boolean inCell) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 0x13) { fields.push(Boolean.TRUE); continue; }                 // field begin
            if (c == 0x14) { if (!fields.isEmpty()) { fields.pop(); fields.push(Boolean.FALSE); } continue; } // separator
            if (c == 0x15) { if (!fields.isEmpty()) fields.pop(); continue; }       // field end
            if (fields.contains(Boolean.TRUE)) continue;                            // inside field instructions
            if (c == '\t') { sb.append(inCell ? ' ' : '\t'); continue; }
            if (c == 0x0B) { sb.append(' '); continue; }                            // soft line break
            if (c == 0x1E) { sb.append('-'); continue; }                            // non-breaking hyphen
            if (c < 0x20) continue;                                                 // paragraph/cell marks, pictures, etc.
            sb.append(c);
        }
        return sb.toString();
    }

    private static SimpleAttributeSet charAttrs(CharacterRun r) {
        int size = r.getFontSize() / 2; // half-points -> points
        if (size <= 0) size = DocAttrs.DEFAULT_SIZE;
        return DocAttrs.chars(r.isBold(), r.isItalic(), r.getUnderlineCode() != 0, r.isStrikeThrough(),
                r.getFontName(), size, DocAttrs.icoToColor(r.getColor()));
    }

    private static SimpleAttributeSet paragraphAttrs(Paragraph p) {
        int align = StyleConstants.ALIGN_LEFT;
        switch (p.getJustification()) {
            case 1: align = StyleConstants.ALIGN_CENTER; break;
            case 2: align = StyleConstants.ALIGN_RIGHT; break;
            case 3:
            case 4: align = StyleConstants.ALIGN_JUSTIFIED; break;
            default: break;
        }
        float left = DocAttrs.clamp(p.getIndentFromLeft() / 20f, 0f, 400f);
        float before = DocAttrs.clamp(p.getSpacingBefore() / 20f, 0f, 72f);
        float after = DocAttrs.clamp(p.getSpacingAfter() / 20f, 0f, 72f);
        return DocAttrs.paragraph(align, left, before, after);
    }
}
