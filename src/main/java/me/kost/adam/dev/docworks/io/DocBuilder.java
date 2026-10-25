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

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;

/**
 * Builds a {@link DefaultStyledDocument} one paragraph at a time. Usage per paragraph:
 * {@code beginParagraph(); addRun(..)*; endParagraph(attrs);}
 * No trailing blank paragraph is produced: the document's own implicit final newline closes the last one.
 */
public final class DocBuilder {
    private final DefaultStyledDocument doc = DocAttrs.newDocument();
    private boolean first = true;
    private int paragraphStart;

    public void beginParagraph() {
        try {
            if (!first) doc.insertString(doc.getLength(), "\n", new SimpleAttributeSet());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        first = false;
        paragraphStart = doc.getLength();
    }

    public void addRun(String text, AttributeSet attrs) {
        if (text == null || text.isEmpty()) return;
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    public void endParagraph(AttributeSet paragraphAttrs) {
        doc.setParagraphAttributes(paragraphStart, doc.getLength() - paragraphStart, paragraphAttrs, true);
    }

    public DefaultStyledDocument build() {
        return doc;
    }
}
