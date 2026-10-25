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
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;

/** Attribute keys, defaults and small factories shared by the readers, the writer and the UI. */
public final class DocAttrs {
    public static final String DEFAULT_FONT = "Calibri";
    public static final int DEFAULT_SIZE = 11;

    /**
     * Paragraph attribute marking a paragraph as one table row. The cells of the row are the
     * tab-separated pieces of the paragraph text.
     */
    public static final Object TABLE_ROW = new Object() {
        @Override public String toString() { return "docworks.tableRow"; }
    };

    private static final float TABLE_TAB = 120f;
    private static final int TABLE_TAB_COUNT = 24;

    private DocAttrs() {}

    /** A new empty document whose default style is the DocWorks default font. */
    public static DefaultStyledDocument newDocument() {
        DefaultStyledDocument d = new DefaultStyledDocument();
        Style def = d.getStyle(StyleContext.DEFAULT_STYLE);
        StyleConstants.setFontFamily(def, DEFAULT_FONT);
        StyleConstants.setFontSize(def, DEFAULT_SIZE);
        return d;
    }

    /** A complete character attribute set (every key set explicitly so nothing is inherited by accident). */
    public static SimpleAttributeSet chars(boolean bold, boolean italic, boolean underline, boolean strike,
                                           String family, int size, Color color) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setBold(a, bold);
        StyleConstants.setItalic(a, italic);
        StyleConstants.setUnderline(a, underline);
        StyleConstants.setStrikeThrough(a, strike);
        StyleConstants.setFontFamily(a, family == null || family.isEmpty() ? DEFAULT_FONT : family);
        StyleConstants.setFontSize(a, clamp(size, 1, 400));
        StyleConstants.setForeground(a, color == null ? Color.BLACK : color);
        return a;
    }

    public static SimpleAttributeSet plainChars() {
        return chars(false, false, false, false, null, DEFAULT_SIZE, null);
    }

    /** A complete paragraph attribute set. Sizes are in points. */
    public static SimpleAttributeSet paragraph(int alignment, float leftIndent, float spaceAbove, float spaceBelow) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setAlignment(a, alignment);
        StyleConstants.setLeftIndent(a, leftIndent);
        StyleConstants.setSpaceAbove(a, spaceAbove);
        StyleConstants.setSpaceBelow(a, spaceBelow);
        return a;
    }

    /** Paragraph attributes for a table row: marker attribute plus fixed column tab stops. */
    public static SimpleAttributeSet tableRow() {
        SimpleAttributeSet a = paragraph(StyleConstants.ALIGN_LEFT, 0, 0, 0);
        a.addAttribute(TABLE_ROW, Boolean.TRUE);
        TabStop[] stops = new TabStop[TABLE_TAB_COUNT];
        for (int i = 0; i < stops.length; i++) stops[i] = new TabStop((i + 1) * TABLE_TAB);
        StyleConstants.setTabSet(a, new TabSet(stops));
        return a;
    }

    public static boolean isTableRow(AttributeSet a) {
        return a != null && Boolean.TRUE.equals(a.getAttribute(TABLE_ROW));
    }

    public static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    public static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Maps a legacy Word colour index ("ico") to an RGB colour. 0 (automatic) maps to black. */
    public static Color icoToColor(int ico) {
        switch (ico) {
            case 2: return new Color(0, 0, 255);
            case 3: return new Color(0, 255, 255);
            case 4: return new Color(0, 255, 0);
            case 5: return new Color(255, 0, 255);
            case 6: return new Color(255, 0, 0);
            case 7: return new Color(255, 255, 0);
            case 8: return Color.WHITE;
            case 9: return new Color(0, 0, 128);
            case 10: return new Color(0, 128, 128);
            case 11: return new Color(0, 128, 0);
            case 12: return new Color(128, 0, 128);
            case 13: return new Color(128, 0, 0);
            case 14: return new Color(128, 128, 0);
            case 15: return new Color(128, 128, 128);
            case 16: return new Color(192, 192, 192);
            default: return Color.BLACK;
        }
    }
}
