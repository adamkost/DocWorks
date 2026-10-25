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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.print.PrinterException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import me.kost.adam.dev.docworks.io.DocAttrs;
import me.kost.adam.dev.docworks.io.DocumentIO;

/** One document window. Static helpers manage the set of open windows. */
public final class AppFrame extends JFrame {
    private static final String APP = "DocWorks";
    private static final String VERSION = "1.0.0";
    private static final String BULLET = "\u2022\t";
    private static final Pattern NUMBERED = Pattern.compile("^\\d+\\.\\t");
    private static final int POLL_MS = 2000;
    private static final String[] SIZES = {"8", "9", "10", "11", "12", "14", "16", "18", "20", "24", "28", "36", "48", "72"};

    private static final List<AppFrame> WINDOWS = new ArrayList<>();
    private static Path lastDirectory;
    private static final RecentFiles RECENT = new RecentFiles(Preferences.userNodeForPackage(AppFrame.class));

    /** Last-modified time and size of a file; two different stamps mean the file changed on disk. */
    private static final class Stamp {
        final long time;
        final long size;
        Stamp(long time, long size) { this.time = time; this.size = size; }
        @Override public boolean equals(Object o) {
            return o instanceof Stamp && ((Stamp) o).time == time && ((Stamp) o).size == size;
        }
        @Override public int hashCode() { return Long.hashCode(time) * 31 + Long.hashCode(size); }
    }

    private static Stamp stampOf(Path p) {
        try {
            return new Stamp(Files.getLastModifiedTime(p).toMillis(), Files.size(p));
        } catch (IOException e) {
            return null; // missing or unreadable right now
        }
    }

    /** Gives access to the protected formatting helpers of {@link StyledEditorKit.StyledTextAction}. */
    private static final class Fmt extends StyledEditorKit.StyledTextAction {
        private static final long serialVersionUID = 1L;
        Fmt() { super("docworks-format"); }
        @Override public void actionPerformed(ActionEvent e) { /* helper only */ }
        void chars(JTextPane ed, AttributeSet a) { setCharacterAttributes(ed, a, false); }
        void paras(JTextPane ed, AttributeSet a) { setParagraphAttributes(ed, a, false); }
    }

    private final JTextPane editor = new JTextPane();
    private final UndoManager undo = new UndoManager();
    private final Fmt fmt = new Fmt();
    private final Timer countTimer;
    private final Timer pollTimer;
    private final JLabel countsLabel = new JLabel(" ");
    private final JLabel positionLabel = new JLabel(" ");
    private final JMenu recentMenu = new JMenu("Open Recent");
    private JMenuItem undoItem;
    private JMenuItem redoItem;
    private JComboBox<String> fontCombo;
    private JComboBox<String> sizeCombo;
    private JToggleButton boldBtn, italicBtn, underlineBtn, strikeBtn;
    private JToggleButton alignLeft, alignCenter, alignRight, alignJustify;
    private FindReplaceDialog findDialog;

    private StyledDocument doc;
    private Path file;
    private boolean modified;
    private boolean fromExisting;
    private boolean overwriteConfirmed;
    private boolean updating;
    private Stamp knownStamp;       // what the file looked like when we last read or wrote it
    private Stamp pendingStamp;     // a changed stamp seen once; it must repeat before we ask (file may still be being written)
    private boolean reloadPrompting;

    // ------------------------------------------------------------------ window management

    /** Opens a file in a window (reusing an untouched blank window). Returns false if it could not be opened. */
    public static boolean openPath(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        for (AppFrame w : WINDOWS) {
            if (abs.equals(w.file)) {
                w.toFront();
                w.requestFocus();
                recordRecent(abs);
                return true;
            }
        }
        if (!Files.isRegularFile(abs)) {
            JOptionPane.showMessageDialog(null, "File not found:\n" + abs, APP, JOptionPane.ERROR_MESSAGE);
            return false;
        }
        StyledDocument loaded;
        try {
            loaded = DocumentIO.load(abs);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, ex.getMessage(), APP + " - cannot open file", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        AppFrame target = null;
        for (AppFrame w : WINDOWS) {
            if (w.file == null && !w.modified) { target = w; break; }
        }
        if (target == null) target = new AppFrame();
        target.setDocument(loaded, abs);
        target.fromExisting = true;
        lastDirectory = abs.getParent();
        recordRecent(abs);
        target.toFront();
        return true;
    }

    public static void openNew() {
        new AppFrame();
    }

    /** Asks about unsaved changes in every window and closes them. False if the user cancelled. */
    public static boolean closeAll() {
        for (AppFrame w : new ArrayList<>(WINDOWS)) {
            if (!w.close()) return false;
        }
        return true;
    }

    private boolean close() {
        if (!confirmDiscard()) return false;
        pollTimer.stop();
        WINDOWS.remove(this);
        if (findDialog != null) findDialog.dispose();
        dispose();
        return true;
    }

    // ------------------------------------------------------------------ construction

    private AppFrame() {
        super(APP);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { close(); }
            @Override public void windowActivated(WindowEvent e) { pollFile(); }
        });
        undo.setLimit(1000);
        countTimer = new Timer(250, e -> updateCounts());
        countTimer.setRepeats(false);
        pollTimer = new Timer(POLL_MS, e -> pollFile());
        pollTimer.start();

        editor.setMargin(new Insets(24, 56, 24, 56));
        editor.addCaretListener(e -> { syncToolbar(); updatePosition(); });
        installContextMenu();

        setJMenuBar(buildMenuBar());
        rebuildRecentMenu();
        add(buildToolBar(), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(editor);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        findDialog = new FindReplaceDialog(this, editor);
        setDocument(DocAttrs.newDocument(), null);
        setSize(1000, 760);
        setLocationByPlatform(true);
        WINDOWS.add(this);
        setVisible(true);
        editor.requestFocusInWindow();
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu f = new JMenu("File");
        f.add(item("New", KeyEvent.VK_N, false, AppFrame::openNew));
        f.add(item("Open...", KeyEvent.VK_O, false, this::openDialog));
        f.add(recentMenu);
        f.addSeparator();
        f.add(item("Save", KeyEvent.VK_S, false, this::save));
        f.add(item("Save As...", KeyEvent.VK_S, true, this::saveAs));
        f.addSeparator();
        f.add(item("Print...", KeyEvent.VK_P, false, this::print));
        f.addSeparator();
        f.add(item("Close", KeyEvent.VK_W, false, this::close));
        f.add(item("Exit", 0, false, () -> { if (closeAll()) System.exit(0); }));
        bar.add(f);

        JMenu e = new JMenu("Edit");
        undoItem = item("Undo", KeyEvent.VK_Z, false, this::undoAction);
        redoItem = item("Redo", KeyEvent.VK_Y, false, this::redoAction);
        e.add(undoItem);
        e.add(redoItem);
        e.addSeparator();
        e.add(item("Cut", KeyEvent.VK_X, false, editor::cut));
        e.add(item("Copy", KeyEvent.VK_C, false, editor::copy));
        e.add(item("Paste", KeyEvent.VK_V, false, editor::paste));
        e.add(item("Select All", KeyEvent.VK_A, false, editor::selectAll));
        e.addSeparator();
        e.add(item("Find and Replace...", KeyEvent.VK_F, false, () -> findDialog.open()));
        bar.add(e);

        JMenu o = new JMenu("Format");
        o.add(item("Bold", KeyEvent.VK_B, false, () -> toggleChar(StyleConstants.Bold)));
        o.add(item("Italic", KeyEvent.VK_I, false, () -> toggleChar(StyleConstants.Italic)));
        o.add(item("Underline", KeyEvent.VK_U, false, () -> toggleChar(StyleConstants.Underline)));
        o.add(item("Strikethrough", 0, false, () -> toggleChar(StyleConstants.StrikeThrough)));
        o.add(item("Font Color...", 0, false, this::chooseColor));
        o.addSeparator();
        o.add(item("Align Left", KeyEvent.VK_L, false, () -> align(StyleConstants.ALIGN_LEFT)));
        o.add(item("Center", KeyEvent.VK_E, false, () -> align(StyleConstants.ALIGN_CENTER)));
        o.add(item("Align Right", KeyEvent.VK_R, false, () -> align(StyleConstants.ALIGN_RIGHT)));
        o.add(item("Justify", KeyEvent.VK_J, false, () -> align(StyleConstants.ALIGN_JUSTIFIED)));
        o.addSeparator();
        o.add(item("Bulleted List", 0, false, () -> toggleList(false)));
        o.add(item("Numbered List", 0, false, () -> toggleList(true)));
        o.add(item("Increase Indent", 0, false, () -> indent(36f)));
        o.add(item("Decrease Indent", 0, false, () -> indent(-36f)));
        o.addSeparator();
        o.add(item("Heading 1", 0, false, () -> paragraphStyle(20, true)));
        o.add(item("Heading 2", 0, false, () -> paragraphStyle(16, true)));
        o.add(item("Heading 3", 0, false, () -> paragraphStyle(14, true)));
        o.add(item("Normal Text", 0, false, () -> paragraphStyle(DocAttrs.DEFAULT_SIZE, false)));
        bar.add(o);

        JMenu i = new JMenu("Insert");
        i.add(item("Table...", 0, false, this::insertTable));
        i.add(item("Remove Table Formatting", 0, false, this::removeTableFormatting));
        i.add(item("Date", 0, false, () -> editor.replaceSelection(
                LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)))));
        bar.add(i);

        JMenu h = new JMenu("Help");
        h.add(item("About " + APP, 0, false, () -> JOptionPane.showMessageDialog(this,
                APP + " " + VERSION + "\nA small word processor that opens .doc, .docx, .rtf and .txt files.\n\n"
                        + "Copyright (C) 2026 Adam Kost\nLicensed under the GNU General Public License, version 3.",
                "About " + APP, JOptionPane.INFORMATION_MESSAGE)));
        bar.add(h);
        return bar;
    }

    private JMenuItem item(String text, int key, boolean shift, Runnable action) {
        JMenuItem m = new JMenuItem(text);
        if (key != 0) {
            int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | (shift ? InputEvent.SHIFT_DOWN_MASK : 0);
            m.setAccelerator(KeyStroke.getKeyStroke(key, mask));
        }
        m.addActionListener(ev -> action.run());
        return m;
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        fontCombo = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        fontCombo.setMaximumSize(new Dimension(190, 28));
        fontCombo.setSelectedItem(DocAttrs.DEFAULT_FONT);
        fontCombo.setToolTipText("Font");
        fontCombo.addActionListener(ev -> {
            if (updating || fontCombo.getSelectedItem() == null) return;
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setFontFamily(a, (String) fontCombo.getSelectedItem());
            applyChars(a);
        });
        tb.add(fontCombo);

        sizeCombo = new JComboBox<>(SIZES);
        sizeCombo.setEditable(true);
        sizeCombo.setMaximumSize(new Dimension(70, 28));
        sizeCombo.setSelectedItem(String.valueOf(DocAttrs.DEFAULT_SIZE));
        sizeCombo.setToolTipText("Font size");
        sizeCombo.addActionListener(ev -> {
            if (updating) return;
            try {
                int size = Integer.parseInt(String.valueOf(sizeCombo.getSelectedItem()).trim());
                if (size >= 1 && size <= 400) {
                    SimpleAttributeSet a = new SimpleAttributeSet();
                    StyleConstants.setFontSize(a, size);
                    applyChars(a);
                }
            } catch (NumberFormatException ex) { /* ignore text that is not a number */ }
        });
        tb.add(sizeCombo);
        tb.addSeparator();

        boldBtn = toggle("B", Font.BOLD, "Bold", () -> toggleChar(StyleConstants.Bold));
        italicBtn = toggle("I", Font.ITALIC, "Italic", () -> toggleChar(StyleConstants.Italic));
        underlineBtn = toggle("U", Font.PLAIN, "Underline", () -> toggleChar(StyleConstants.Underline));
        strikeBtn = toggle("S", Font.PLAIN, "Strikethrough", () -> toggleChar(StyleConstants.StrikeThrough));
        tb.add(boldBtn);
        tb.add(italicBtn);
        tb.add(underlineBtn);
        tb.add(strikeBtn);
        JButton color = new JButton("Color");
        color.setFocusable(false);
        color.setToolTipText("Font color");
        color.addActionListener(ev -> chooseColor());
        tb.add(color);
        tb.addSeparator();

        alignLeft = toggle("Left", Font.PLAIN, "Align left", () -> align(StyleConstants.ALIGN_LEFT));
        alignCenter = toggle("Center", Font.PLAIN, "Center", () -> align(StyleConstants.ALIGN_CENTER));
        alignRight = toggle("Right", Font.PLAIN, "Align right", () -> align(StyleConstants.ALIGN_RIGHT));
        alignJustify = toggle("Justify", Font.PLAIN, "Justify", () -> align(StyleConstants.ALIGN_JUSTIFIED));
        ButtonGroup g = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[] {alignLeft, alignCenter, alignRight, alignJustify}) {
            g.add(b);
            tb.add(b);
        }
        tb.addSeparator();

        JButton bullets = new JButton("\u2022 List");
        bullets.setFocusable(false);
        bullets.setToolTipText("Bulleted list");
        bullets.addActionListener(ev -> toggleList(false));
        JButton numbers = new JButton("1. List");
        numbers.setFocusable(false);
        numbers.setToolTipText("Numbered list");
        numbers.addActionListener(ev -> toggleList(true));
        tb.add(bullets);
        tb.add(numbers);
        return tb;
    }

    private JToggleButton toggle(String label, int fontStyle, String tip, Runnable action) {
        JToggleButton b = new JToggleButton(label);
        b.setFont(b.getFont().deriveFont(fontStyle));
        b.setFocusable(false);
        b.setToolTipText(tip);
        b.addActionListener(ev -> { if (!updating) action.run(); });
        return b;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(3, 8, 3, 8));
        p.add(countsLabel, BorderLayout.WEST);
        p.add(positionLabel, BorderLayout.EAST);
        return p;
    }

    // ------------------------------------------------------------------ recent files

    /** Puts a file at the top of the shared recent list and refreshes the Open Recent menu of every window. */
    private static void recordRecent(Path p) {
        RECENT.add(p);
        refreshRecentMenus();
    }

    private static void refreshRecentMenus() {
        for (AppFrame w : WINDOWS) w.rebuildRecentMenu();
    }

    private void rebuildRecentMenu() {
        recentMenu.removeAll();
        List<Path> list = RECENT.list();
        if (list.isEmpty()) {
            JMenuItem none = new JMenuItem("(No recent files)");
            none.setEnabled(false);
            recentMenu.add(none);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Path p = list.get(i);
            JMenuItem m = item((i + 1) + "  " + p.getFileName(), 0, false, () -> openRecent(p));
            m.setToolTipText(p.toString());
            recentMenu.add(m);
        }
        recentMenu.addSeparator();
        recentMenu.add(item("Clear Recent Files", 0, false, () -> {
            RECENT.clear();
            refreshRecentMenus();
        }));
    }

    private void openRecent(Path p) {
        if (!Files.isRegularFile(p)) {
            JOptionPane.showMessageDialog(this, p + "\nno longer exists and will be removed from the recent list.",
                    APP, JOptionPane.WARNING_MESSAGE);
            RECENT.remove(p);
            refreshRecentMenus();
            return;
        }
        openPath(p);
    }

    // ------------------------------------------------------------------ external change detection

    /**
     * Called every couple of seconds and when the window is activated. If the open file was changed
     * outside DocWorks, asks whether to reload it. Only the active window checks, so background windows
     * never pop up dialogs, and a change must be seen on two consecutive checks so that a file another
     * program is still writing is not read half-finished.
     */
    private void pollFile() {
        if (file == null || reloadPrompting || !isActive()) return;
        Stamp now = stampOf(file);
        if (now == null || now.equals(knownStamp)) {
            pendingStamp = null;
            return;
        }
        if (!now.equals(pendingStamp)) {
            pendingStamp = now;
            return;
        }
        pendingStamp = null;
        knownStamp = now; // answering "Keep My Version" must not ask again until the file changes again
        askReload();
    }

    private void askReload() {
        reloadPrompting = true;
        try {
            String name = file.getFileName().toString();
            String message = name + " was changed by another program.\n\nReload it from disk?"
                    + (modified ? "\nYour unsaved changes in " + APP + " will be discarded." : "");
            String[] options = {"Reload", "Keep My Version"};
            int choice = JOptionPane.showOptionDialog(this, message, APP + " - file changed",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
                    modified ? options[1] : options[0]);
            if (choice == 0) reload();
        } finally {
            reloadPrompting = false;
        }
    }

    /** Replaces the window's content with the file on disk, discarding any edits made here. */
    private void reload() {
        Stamp before = stampOf(file);
        StyledDocument fresh;
        try {
            fresh = DocumentIO.load(file);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), APP + " - cannot reload", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int caret = editor.getCaretPosition();
        setDocument(fresh, file);
        if (before != null) knownStamp = before;
        fromExisting = true;
        editor.setCaretPosition(Math.min(caret, fresh.getLength()));
    }

    // ------------------------------------------------------------------ context menu

    /** Right-click menu with Cut, Copy and Paste. Cut and Copy are enabled only while text is highlighted. */
    private void installContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem cut = item("Cut", 0, false, editor::cut);
        JMenuItem copy = item("Copy", 0, false, editor::copy);
        JMenuItem paste = item("Paste", 0, false, editor::paste);
        menu.add(cut);
        menu.add(copy);
        menu.add(paste);
        editor.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }   // Linux, macOS
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }  // Windows

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                editor.requestFocusInWindow();
                int pos = editor.viewToModel2D(e.getPoint());
                // Right-clicking inside the highlighted text keeps it; elsewhere the caret moves there first
                if (pos >= 0 && (pos < editor.getSelectionStart() || pos > editor.getSelectionEnd())) {
                    editor.setCaretPosition(pos);
                }
                boolean hasSelection = editor.getSelectionStart() != editor.getSelectionEnd();
                cut.setEnabled(hasSelection);
                copy.setEnabled(hasSelection);
                paste.setEnabled(clipboardHasText());
                menu.show(editor, e.getX(), e.getY());
            }
        });
    }

    private static boolean clipboardHasText() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (IllegalStateException | java.awt.HeadlessException ex) {
            return true; // clipboard busy or unavailable: let the paste attempt decide
        }
    }

    // ------------------------------------------------------------------ document handling

    private void setDocument(StyledDocument newDoc, Path path) {
        doc = newDoc;
        undo.discardAllEdits();
        newDoc.addUndoableEditListener(ev -> { undo.addEdit(ev.getEdit()); updateUndoState(); });
        newDoc.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        editor.setDocument(newDoc);
        editor.setCaretPosition(0);
        file = path;
        knownStamp = path == null ? null : stampOf(path);
        pendingStamp = null;
        fromExisting = false;
        overwriteConfirmed = false;
        setModified(false);
        updateUndoState();
        updateCounts();
        updatePosition();
        syncToolbar();
    }

    private void changed() {
        setModified(true);
        countTimer.restart();
    }

    private void setModified(boolean m) {
        modified = m;
        updateTitle();
    }

    private void updateTitle() {
        String name = file == null ? "Untitled" : file.getFileName().toString();
        setTitle((modified ? "* " : "") + name + " - " + APP);
        getRootPane().putClientProperty("Window.documentModified", modified);
    }

    private void updateCounts() {
        try {
            String t = doc.getText(0, doc.getLength());
            int words = 0;
            int chars = 0;
            boolean inWord = false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != '\n') chars++;
                if (Character.isWhitespace(c)) inWord = false;
                else if (!inWord) { inWord = true; words++; }
            }
            countsLabel.setText(String.format("Words: %,d    Characters: %,d", words, chars));
        } catch (BadLocationException e) {
            countsLabel.setText(" ");
        }
    }

    private void updatePosition() {
        if (doc == null) return;
        int pos = editor.getCaretPosition();
        Element root = doc.getDefaultRootElement();
        int para = root.getElementIndex(pos);
        int col = pos - root.getElement(para).getStartOffset() + 1;
        positionLabel.setText("Paragraph " + (para + 1) + ", Column " + col);
    }

    private void updateUndoState() {
        if (undoItem != null) undoItem.setEnabled(undo.canUndo());
        if (redoItem != null) redoItem.setEnabled(undo.canRedo());
    }

    private void undoAction() {
        try { if (undo.canUndo()) undo.undo(); } catch (CannotUndoException ex) { /* nothing to undo */ }
        updateUndoState();
    }

    private void redoAction() {
        try { if (undo.canRedo()) undo.redo(); } catch (CannotRedoException ex) { /* nothing to redo */ }
        updateUndoState();
    }

    // ------------------------------------------------------------------ files

    private Path startDirectory() {
        if (file != null && file.getParent() != null) return file.getParent();
        return lastDirectory;
    }

    private void openDialog() {
        JFileChooser fc = new JFileChooser(startDirectory() == null ? null : startDirectory().toFile());
        fc.setAcceptAllFileFilterUsed(true);
        fc.setFileFilter(new FileNameExtensionFilter("Documents (*.doc, *.docx, *.rtf, *.txt)", "doc", "docx", "rtf", "txt"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openPath(fc.getSelectedFile().toPath());
        }
    }

    private boolean confirmDiscard() {
        if (!modified) return true;
        String name = file == null ? "Untitled" : file.getFileName().toString();
        int r = JOptionPane.showConfirmDialog(this, "Save changes to " + name + "?", APP,
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) return save();
        return r == JOptionPane.NO_OPTION;
    }

    private boolean save() {
        if (file == null || !DocumentIO.canSave(file)) return saveAs();
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fromExisting && !overwriteConfirmed && (n.endsWith(".docx") || n.endsWith(".rtf"))) {
            int r = JOptionPane.showConfirmDialog(this,
                    APP + " saves from its own simplified model of the document. Images, headers, footers, footnotes\n"
                            + "and other features it does not support yet would be lost from the original file.\n\n"
                            + "Overwrite the original?  Choose No to save a separate copy instead.",
                    APP, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return false;
            if (r == JOptionPane.NO_OPTION) return saveAs();
            overwriteConfirmed = true;
        }
        return writeTo(file);
    }

    private boolean saveAs() {
        JFileChooser fc = new JFileChooser(startDirectory() == null ? null : startDirectory().toFile());
        FileNameExtensionFilter docx = new FileNameExtensionFilter("Word Document (*.docx)", "docx");
        FileNameExtensionFilter rtf = new FileNameExtensionFilter("Rich Text Format (*.rtf)", "rtf");
        FileNameExtensionFilter txt = new FileNameExtensionFilter("Plain Text (*.txt)", "txt");
        fc.setAcceptAllFileFilterUsed(false);
        fc.addChoosableFileFilter(docx);
        fc.addChoosableFileFilter(rtf);
        fc.addChoosableFileFilter(txt);
        fc.setFileFilter(docx);
        String base = file == null ? "Untitled" : file.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        fc.setSelectedFile(new File(base));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;

        File chosen = fc.getSelectedFile();
        String ext = fc.getFileFilter() == rtf ? "rtf" : fc.getFileFilter() == txt ? "txt" : "docx";
        String lower = chosen.getName().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".docx") || lower.endsWith(".rtf") || lower.endsWith(".txt"))) {
            String name = lower.endsWith(".doc") ? chosen.getName().substring(0, chosen.getName().length() - 4) : chosen.getName();
            chosen = new File(chosen.getParentFile(), name + "." + ext);
        }
        if (chosen.exists() && JOptionPane.showConfirmDialog(this, chosen.getName() + " already exists. Replace it?",
                APP, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return false;
        }
        boolean ok = writeTo(chosen.toPath());
        if (ok) fromExisting = false;
        return ok;
    }

    private boolean writeTo(Path target) {
        try {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            DocumentIO.save(target, doc);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), APP + " - cannot save", JOptionPane.ERROR_MESSAGE);
            return false;
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
        file = target.toAbsolutePath().normalize();
        lastDirectory = file.getParent();
        knownStamp = stampOf(file); // our own save must not look like an outside change
        pendingStamp = null;
        recordRecent(file);
        setModified(false);
        return true;
    }

    private void print() {
        try {
            editor.print();
        } catch (PrinterException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), APP + " - cannot print", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ formatting

    /** Character attributes in effect at the caret or, for a selection, at its start. */
    private AttributeSet currentCharAttrs() {
        if (editor.getSelectionStart() == editor.getSelectionEnd()) return editor.getInputAttributes();
        return doc.getCharacterElement(editor.getSelectionStart()).getAttributes();
    }

    /** Looks a key up in the current attributes, falling back to the character before the caret (for inherited defaults). */
    private Object attrValue(AttributeSet a, Object key) {
        Object v = a.getAttribute(key);
        if (v == null) {
            v = doc.getCharacterElement(Math.max(0, editor.getCaretPosition() - 1)).getAttributes().getAttribute(key);
        }
        return v;
    }

    private void applyChars(AttributeSet a) {
        fmt.chars(editor, a);
        editor.requestFocusInWindow();
        syncToolbar();
    }

    private void toggleChar(Object key) {
        boolean on = !Boolean.TRUE.equals(currentCharAttrs().getAttribute(key));
        SimpleAttributeSet a = new SimpleAttributeSet();
        a.addAttribute(key, on);
        applyChars(a);
    }

    private void chooseColor() {
        Color current = StyleConstants.getForeground(currentCharAttrs());
        Color c = JColorChooser.showDialog(this, "Font Color", current);
        if (c != null) {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, c);
            applyChars(a);
        }
    }

    private void align(int alignment) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setAlignment(a, alignment);
        fmt.paras(editor, a);
        editor.requestFocusInWindow();
        syncToolbar();
    }

    /** Index range [first, last] of the paragraphs touched by the selection. */
    private int[] paragraphRange() {
        Element root = doc.getDefaultRootElement();
        int s = editor.getSelectionStart();
        int e = editor.getSelectionEnd();
        int first = root.getElementIndex(s);
        int last = root.getElementIndex(e > s ? e - 1 : e);
        return new int[] {first, last};
    }

    private void indent(float delta) {
        int[] r = paragraphRange();
        Element root = doc.getDefaultRootElement();
        for (int i = r[0]; i <= r[1]; i++) {
            Element p = root.getElement(i);
            float cur = StyleConstants.getLeftIndent(p.getAttributes());
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setLeftIndent(a, Math.max(0f, cur + delta));
            doc.setParagraphAttributes(p.getStartOffset(), 0, a, false);
        }
        editor.requestFocusInWindow();
    }

    private void paragraphStyle(int size, boolean bold) {
        int s = doc.getParagraphElement(editor.getSelectionStart()).getStartOffset();
        int e = doc.getParagraphElement(editor.getSelectionEnd()).getEndOffset();
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontSize(a, size);
        StyleConstants.setBold(a, bold);
        doc.setCharacterAttributes(s, Math.max(0, Math.min(e, doc.getLength()) - s), a, false);
        editor.getInputAttributes().addAttributes(a);
        editor.requestFocusInWindow();
        syncToolbar();
    }

    private String listPrefixAt(int offset) throws BadLocationException {
        int end = Math.min(offset + 16, doc.getLength());
        if (end <= offset) return null;
        String t = doc.getText(offset, end - offset);
        if (t.startsWith(BULLET)) return BULLET;
        Matcher m = NUMBERED.matcher(t);
        return m.find() ? m.group() : null;
    }

    /** Toggles bullets or numbers on the selected paragraphs. The markers are ordinary text at the paragraph start. */
    private void toggleList(boolean numbered) {
        int[] r = paragraphRange();
        Element root = doc.getDefaultRootElement();
        List<Integer> starts = new ArrayList<>();
        for (int i = r[0]; i <= r[1]; i++) starts.add(root.getElement(i).getStartOffset());
        try {
            boolean allSameKind = true;
            for (int off : starts) {
                String pre = listPrefixAt(off);
                if (pre == null || NUMBERED.matcher(pre).matches() != numbered) { allSameKind = false; break; }
            }
            for (int i = starts.size() - 1; i >= 0; i--) { // back to front so earlier offsets stay valid
                int off = starts.get(i);
                String pre = listPrefixAt(off);
                if (pre != null) doc.remove(off, pre.length());
                if (!allSameKind) {
                    String marker = numbered ? (i + 1) + ".\t" : BULLET;
                    doc.insertString(off, marker, new SimpleAttributeSet(doc.getCharacterElement(off).getAttributes()));
                }
            }
        } catch (BadLocationException ex) { /* offsets were computed from the live document */ }
        editor.requestFocusInWindow();
    }

    private void insertTable() {
        JSpinner rows = new JSpinner(new SpinnerNumberModel(3, 1, 100, 1));
        JSpinner cols = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.add(new JLabel("Rows:"));
        p.add(rows);
        p.add(new JLabel("Columns:"));
        p.add(cols);
        if (JOptionPane.showConfirmDialog(this, p, "Insert Table", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        int nRows = (Integer) rows.getValue();
        int nCols = (Integer) cols.getValue();
        try {
            int pos = editor.getCaretPosition();
            Element para = doc.getParagraphElement(pos);
            if (pos != para.getStartOffset()) {
                doc.insertString(pos, "\n", null);
                pos++;
            }
            StringBuilder sb = new StringBuilder();
            String row = "\t".repeat(nCols - 1) + "\n";
            for (int i = 0; i < nRows; i++) sb.append(row);
            doc.insertString(pos, sb.toString(), null);
            // length - 1 keeps the paragraph after the table out of the range
            doc.setParagraphAttributes(pos, sb.length() - 1, DocAttrs.tableRow(), false);
            editor.setCaretPosition(pos);
        } catch (BadLocationException ex) { /* position came from the live caret */ }
        editor.requestFocusInWindow();
    }

    private void removeTableFormatting() {
        int[] r = paragraphRange();
        Element root = doc.getDefaultRootElement();
        for (int i = r[0]; i <= r[1]; i++) {
            Element p = root.getElement(i);
            if (!DocAttrs.isTableRow(p.getAttributes())) continue;
            SimpleAttributeSet plain = DocAttrs.paragraph(StyleConstants.ALIGN_LEFT, 0, 0, 0);
            doc.setParagraphAttributes(p.getStartOffset(), 0, plain, true);
        }
        editor.requestFocusInWindow();
    }

    /** Reflects the formatting at the caret in the toolbar without triggering the toolbar's own actions. */
    private void syncToolbar() {
        if (doc == null || boldBtn == null) return;
        updating = true;
        try {
            AttributeSet a = currentCharAttrs();
            boldBtn.setSelected(Boolean.TRUE.equals(a.getAttribute(StyleConstants.Bold)));
            italicBtn.setSelected(Boolean.TRUE.equals(a.getAttribute(StyleConstants.Italic)));
            underlineBtn.setSelected(Boolean.TRUE.equals(a.getAttribute(StyleConstants.Underline)));
            strikeBtn.setSelected(Boolean.TRUE.equals(a.getAttribute(StyleConstants.StrikeThrough)));
            Object family = attrValue(a, StyleConstants.FontFamily);
            if (family != null) fontCombo.setSelectedItem(family.toString());
            Object size = attrValue(a, StyleConstants.FontSize);
            if (size != null) sizeCombo.setSelectedItem(size.toString());
            int al = StyleConstants.getAlignment(doc.getParagraphElement(editor.getSelectionStart()).getAttributes());
            alignLeft.setSelected(al == StyleConstants.ALIGN_LEFT);
            alignCenter.setSelected(al == StyleConstants.ALIGN_CENTER);
            alignRight.setSelected(al == StyleConstants.ALIGN_RIGHT);
            alignJustify.setSelected(al == StyleConstants.ALIGN_JUSTIFIED);
        } finally {
            updating = false;
        }
    }
}
