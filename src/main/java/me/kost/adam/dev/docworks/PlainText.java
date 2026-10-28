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

/** Turns clipboard text into something safe to insert as plain text into a document. */
final class PlainText {
    private PlainText() {}

    /**
     * Normalises every kind of line break (CRLF, CR, Unicode line and paragraph separators) to a single
     * newline, keeps tabs, and drops all other control characters.
     */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') i++;
                sb.append('\n');
            } else if (c == '\u2028' || c == '\u2029') {
                sb.append('\n');
            } else if (c == '\n' || c == '\t') {
                sb.append(c);
            } else if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
