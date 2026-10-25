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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentFilesTest {
    @TempDir
    Path dir;

    private Preferences node;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("docworks-test-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    private Path f(String name) {
        return dir.resolve(name).toAbsolutePath().normalize();
    }

    @Test
    void newestFirstAndNoDuplicates() {
        RecentFiles r = new RecentFiles(node);
        r.add(f("a.docx"));
        r.add(f("b.doc"));
        r.add(f("a.docx")); // reopening a file moves it to the top instead of adding a second entry
        assertEquals(Arrays.asList(f("a.docx"), f("b.doc")), r.list());
    }

    @Test
    void keepsOnlyTheTenNewest() {
        RecentFiles r = new RecentFiles(node);
        for (int i = 0; i < 15; i++) r.add(f("file" + i + ".docx"));
        assertEquals(RecentFiles.MAX, r.list().size());
        assertEquals(f("file14.docx"), r.list().get(0));
        assertEquals(f("file5.docx"), r.list().get(RecentFiles.MAX - 1));
    }

    @Test
    void survivesRestart() {
        RecentFiles first = new RecentFiles(node);
        first.add(f("one.docx"));
        first.add(f("two.rtf"));
        RecentFiles second = new RecentFiles(node); // same preferences node = next program run
        assertEquals(Arrays.asList(f("two.rtf"), f("one.docx")), second.list());
    }

    @Test
    void removeAndClearArePersisted() {
        RecentFiles r = new RecentFiles(node);
        r.add(f("one.docx"));
        r.add(f("two.docx"));
        r.remove(f("one.docx"));
        assertEquals(Arrays.asList(f("two.docx")), new RecentFiles(node).list());
        r.clear();
        assertTrue(new RecentFiles(node).list().isEmpty());
    }
}
