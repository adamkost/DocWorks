# DocWorks

A small desktop word processor written in Java 11 and Swing. It opens legacy Word `.doc` files
(and `.docx`, `.rtf`, `.txt`) and saves `.docx`, `.rtf` and `.txt`.

Copyright (C) 2026 Adam Kost. Licensed under the GNU General Public License, version 3 (see `LICENSE`).

## Build and run

Requires JDK 11 or newer and Maven.

```
mvn package
java -jar target/docworks.jar [file.doc ...]
```

On Windows, after building, double-click `docworks.bat` (or run `docworks.bat letter.doc`).

Or, without building a jar: `mvn compile exec:java -Dexec.args="letter.doc"`.

`mvn test` runs the headless I/O tests (no display needed).

## What works

- **Open:** `.doc` (Word 97-2003, via Apache POI HWPF), `.docx` (XWPF), `.rtf`, `.txt`. The format is
  detected from the file content, so a misnamed file still opens.
- **Save:** `.docx`, `.rtf`, `.txt`. Saves go to a temporary file first, so a failed save never damages the original.
- **Editing:** undo/redo, cut/copy/paste (also on the right-click menu), select all, find and replace (with match case), word and character counts.
- **Formatting:** font, size, bold, italic, underline, strikethrough, colour, left/center/right/justify,
  indents, bulleted and numbered lists, Heading 1-3.
- **Tables:** Insert > Table. Tables are rows of tab-separated cells; they are saved as real tables in `.docx`.
- **Printing** through the system print dialog.

## Known limitations

- **Saving legacy `.doc` is not supported.** Apache POI cannot write a Word binary file from scratch.
  Use Save As and choose `.docx`.
- DocWorks works from its own simplified document model. When it reads a file it keeps text, character
  formatting, alignment, indents, spacing, lists and tables. It does **not yet** keep images, headers and
  footers, footnotes, page layout, styles, track changes, comments or field codes (fields show their result text).
  Overwriting an existing `.docx` or `.rtf` therefore asks for confirmation first.
- Table cells have no borders on screen, and column widths are fixed tab stops.
- Soft line breaks inside a paragraph are read as spaces.
- Word 6/95 files and password-protected files are rejected with an error message.
- No zoom, page view, spell check or PDF export yet.

## Layout

| Package | Contents |
|---|---|
| `me.kost.adam.dev.docworks` | `Main`, `AppFrame` (window, menus, toolbar), `FindReplaceDialog` |
| `me.kost.adam.dev.docworks.io` | `DocumentIO` (format detection, load, save), `DocReader` (.doc), `DocxReader`, `DocxWriter`, `DocBuilder`, `DocAttrs` |
