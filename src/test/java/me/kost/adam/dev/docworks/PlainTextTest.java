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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlainTextTest {
    @Test
    void lineBreaksAreNormalisedToNewline() {
        assertEquals("a\nb\nc\nd\ne", PlainText.clean("a\r\nb\rc\u2028d\u2029e"));
    }

    @Test
    void tabsAreKeptAndOtherControlCharactersDropped() {
        assertEquals("x\ty z", PlainText.clean("x\ty\u0000 \u0007z\u007F"));
    }

    @Test
    void ordinaryAndNonLatinTextIsUntouched() {
        assertEquals("Caf\u00e9 \u65e5\u672c\u8a9e \u2022 100%", PlainText.clean("Caf\u00e9 \u65e5\u672c\u8a9e \u2022 100%"));
    }

    @Test
    void nullAndEmptyGiveEmpty() {
        assertEquals("", PlainText.clean(null));
        assertEquals("", PlainText.clean(""));
    }
}
