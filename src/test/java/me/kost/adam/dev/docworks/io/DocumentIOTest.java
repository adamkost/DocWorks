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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.text.AttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentIOTest {
    @TempDir
    Path dir;

    private static String text(StyledDocument d) throws Exception {
        return d.getText(0, d.getLength());
    }

    private static StyledDocument sample() {
        DocBuilder b = new DocBuilder();
        b.beginParagraph();
        b.addRun("Hello ", DocAttrs.plainChars());
        b.addRun("World", DocAttrs.chars(true, false, true, false, "Arial", 14, Color.RED));
        b.endParagraph(DocAttrs.paragraph(StyleConstants.ALIGN_LEFT, 0, 0, 0));
        b.beginParagraph();
        b.addRun("Centered", DocAttrs.plainChars());
        b.endParagraph(DocAttrs.paragraph(StyleConstants.ALIGN_CENTER, 0, 0, 0));
        b.beginParagraph();
        b.addRun("A\tB", DocAttrs.plainChars());
        b.endParagraph(DocAttrs.tableRow());
        b.beginParagraph();
        b.addRun("C\tD", DocAttrs.plainChars());
        b.endParagraph(DocAttrs.tableRow());
        return b.build();
    }

    @Test
    void docxRoundTripKeepsTextFormattingAndTables() throws Exception {
        Path f = dir.resolve("sample.docx");
        DocumentIO.save(f, sample());

        assertEquals(DocumentIO.Format.DOCX, DocumentIO.detect(f));
        StyledDocument back = DocumentIO.load(f);

        // the writer adds an empty paragraph after a trailing table, which Word requires
        assertEquals("Hello World\nCentered\nA\tB\nC\tD", text(back).stripTrailing());

        AttributeSet world = back.getCharacterElement(6).getAttributes();
        assertTrue(StyleConstants.isBold(world));
        assertTrue(StyleConstants.isUnderline(world));
        assertFalse(StyleConstants.isItalic(world));
        assertEquals("Arial", StyleConstants.getFontFamily(world));
        assertEquals(14, StyleConstants.getFontSize(world));
        assertEquals(Color.RED, StyleConstants.getForeground(world));

        int centered = text(back).indexOf("Centered");
        assertEquals(StyleConstants.ALIGN_CENTER,
                StyleConstants.getAlignment(back.getParagraphElement(centered).getAttributes()));
        assertFalse(DocAttrs.isTableRow(back.getParagraphElement(centered).getAttributes()));

        int a = text(back).indexOf("A\tB");
        int c = text(back).indexOf("C\tD");
        assertTrue(DocAttrs.isTableRow(back.getParagraphElement(a).getAttributes()));
        assertTrue(DocAttrs.isTableRow(back.getParagraphElement(c).getAttributes()));
    }

    @Test
    void textRoundTrip() throws Exception {
        Path f = dir.resolve("note.txt");
        Files.write(f, "line one\r\nline two\r\n\r\nlast \u00e9".getBytes(StandardCharsets.UTF_8));
        StyledDocument d = DocumentIO.load(f);
        assertEquals("line one\nline two\n\nlast \u00e9", text(d));

        Path out = dir.resolve("copy.txt");
        DocumentIO.save(out, d);
        assertEquals("line one\nline two\n\nlast \u00e9", new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
    }

    @Test
    void emptyTextFileOpensAsOneEmptyParagraph() throws Exception {
        Path f = dir.resolve("empty.txt");
        Files.write(f, new byte[0]);
        StyledDocument d = DocumentIO.load(f);
        assertEquals("", text(d));
        assertEquals(1, d.getDefaultRootElement().getElementCount());
    }

    @Test
    void savingLegacyDocIsRefusedAndLeavesNoFile() {
        Path f = dir.resolve("old.doc");
        assertThrows(IOException.class, () -> DocumentIO.save(f, sample()));
        assertFalse(Files.exists(f));
        assertFalse(DocumentIO.canSave(f));
        assertTrue(DocumentIO.canSave(dir.resolve("x.DOCX")));
    }

    @Test
    void formatIsDetectedFromContentNotName() throws Exception {
        Path ole = dir.resolve("misnamed.txt");
        Files.write(ole, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        assertEquals(DocumentIO.Format.DOC, DocumentIO.detect(ole));

        Path rtf = dir.resolve("misnamed.doc");
        Files.write(rtf, "{\\rtf1\\ansi Hello}".getBytes(StandardCharsets.US_ASCII));
        assertEquals(DocumentIO.Format.RTF, DocumentIO.detect(rtf));
    }

    @Test
    void corruptWordFileGivesReadableError() throws Exception {
        Path f = dir.resolve("broken.docx");
        Files.write(f, new byte[] {'P', 'K', 1, 2, 3, 4, 5, 6});
        IOException e = assertThrows(IOException.class, () -> DocumentIO.load(f));
        assertTrue(e.getMessage().contains("broken.docx"));
    }
}
