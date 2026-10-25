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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The most recently used files, newest first, kept in the user's preferences so the list survives restarts.
 * It does not check that the files still exist; the caller decides what to do about stale entries.
 */
final class RecentFiles {
    static final int MAX = 10;
    private static final String KEY = "recent.";

    private final Preferences prefs;
    private final List<Path> items = new ArrayList<>();

    RecentFiles(Preferences prefs) {
        this.prefs = prefs;
        for (int i = 0; i < MAX; i++) {
            String s = prefs.get(KEY + i, null);
            if (s == null || s.isEmpty()) continue;
            try {
                Path p = Paths.get(s);
                if (!items.contains(p)) items.add(p);
            } catch (InvalidPathException e) {
                // a path stored by another platform or a corrupt entry: skip it
            }
        }
    }

    /** A snapshot of the list, most recent first. */
    List<Path> list() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** Moves the file to the top of the list, adding it if needed. */
    void add(Path p) {
        Path n = p.toAbsolutePath().normalize();
        items.remove(n);
        items.add(0, n);
        while (items.size() > MAX) items.remove(items.size() - 1);
        save();
    }

    void remove(Path p) {
        if (items.remove(p.toAbsolutePath().normalize())) save();
    }

    void clear() {
        items.clear();
        save();
    }

    private void save() {
        for (int i = 0; i < MAX; i++) {
            if (i < items.size()) prefs.put(KEY + i, items.get(i).toString());
            else prefs.remove(KEY + i);
        }
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            // preferences are a convenience; keep working with the in-memory list
        }
    }
}
