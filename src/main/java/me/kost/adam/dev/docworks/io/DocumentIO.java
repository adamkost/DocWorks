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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyledDocument;
import javax.swing.text.rtf.RTFEditorKit;

/** Opens and saves documents. The format of a file being opened is detected from its content, not its name. */
public final class DocumentIO {
    public enum Format { DOC, DOCX, RTF, TEXT }

    private interface Sink { void write(OutputStream out) throws IOException; }

    private DocumentIO() {}

    public static Format detect(Path path) throws IOException {
        byte[] h = new byte[8];
        int n;
        try (InputStream in = Files.newInputStream(path)) {
            n = in.readNBytes(h, 0, h.length);
        }
        if (n >= 4 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0) return Format.DOC;
        if (n >= 4 && h[0] == 'P' && h[1] == 'K') return Format.DOCX;
        if (n >= 5 && h[0] == '{' && h[1] == '\\' && h[2] == 'r' && h[3] == 't' && h[4] == 'f') return Format.RTF;
        return Format.TEXT;
    }

    public static StyledDocument load(Path path) throws IOException {
        Format f = detect(path);
        try {
            if (f == Format.TEXT) return fromText(decode(Files.readAllBytes(path)));
            try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                switch (f) {
                    case DOC: return DocReader.read(in);
                    case DOCX: return DocxReader.read(in);
                    default: return fromRtf(in);
                }
            }
        } catch (IOException | RuntimeException e) {
            // POI signals encrypted, corrupt or too-old files with a mix of checked and unchecked exceptions
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException("Cannot read " + path.getFileName() + ": " + why, e);
        }
    }

    /** True if the file name has an extension DocWorks can write (.docx, .rtf, .txt). */
    public static boolean canSave(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".docx") || n.endsWith(".rtf") || n.endsWith(".txt");
    }

    public static void save(Path path, StyledDocument doc) throws IOException {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".docx")) {
            writeSafely(path, out -> DocxWriter.write(doc, out));
        } else if (n.endsWith(".rtf")) {
            writeSafely(path, out -> {
                try {
                    new RTFEditorKit().write(out, doc, 0, doc.getLength());
                } catch (BadLocationException e) {
                    throw new IOException(e);
                }
            });
        } else if (n.endsWith(".txt")) {
            String text;
            try {
                text = doc.getText(0, doc.getLength());
            } catch (BadLocationException e) {
                throw new IOException(e);
            }
            writeSafely(path, out -> out.write(text.getBytes(StandardCharsets.UTF_8)));
        } else {
            throw new IOException("DocWorks can save .docx, .rtf and .txt files. "
                    + "Saving legacy .doc files is not supported; use Save As and choose .docx.");
        }
    }

    /** Writes to a temporary file next to the target and then moves it into place, so a failure never destroys the original. */
    private static void writeSafely(Path target, Sink sink) throws IOException {
        Path abs = target.toAbsolutePath();
        Path tmp = abs.resolveSibling(abs.getFileName() + ".docworks-tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                sink.write(out);
            }
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static DefaultStyledDocument fromText(String text) {
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        DocBuilder b = new DocBuilder();
        for (String line : text.split("\n", -1)) {
            b.beginParagraph();
            b.addRun(line, DocAttrs.plainChars());
            b.endParagraph(DocAttrs.paragraph(javax.swing.text.StyleConstants.ALIGN_LEFT, 0, 0, 0));
        }
        return b.build();
    }

    private static DefaultStyledDocument fromRtf(InputStream in) throws IOException {
        DefaultStyledDocument d = DocAttrs.newDocument();
        try {
            new RTFEditorKit().read(in, d, 0);
        } catch (BadLocationException e) {
            throw new IOException(e);
        }
        return d;
    }

    private static String decode(byte[] bytes) {
        int off = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, off, bytes.length - off, Charset.forName("windows-1252"));
        }
    }
}
