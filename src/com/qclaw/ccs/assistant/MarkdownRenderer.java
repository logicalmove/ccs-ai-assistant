package com.qclaw.ccs.assistant;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Basic Markdown renderer for StyledText using LineStyleListener.
 * Supports: **bold**, `code`, # headings, ```code blocks```, > blockquotes, lists
 */
public class MarkdownRenderer implements LineStyleListener {

    private final Display display;
    private final String fullText;
    private final Color colorBold;
    private final Color colorCode;
    private final Color colorCodeBlock;
    private final Color colorHeading;
    private final Color colorQuote;
    private final Color colorList;
    private final Color colorLink;

    public MarkdownRenderer(Display display, String text) {
        this.display = display;
        this.fullText = text;
        this.colorBold = new Color(display, new RGB(0, 0, 0)); // black bold
        this.colorCode = new Color(display, new RGB(163, 21, 21)); // dark red
        this.colorCodeBlock = new Color(display, new RGB(63, 95, 191)); // blue
        this.colorHeading = new Color(display, new RGB(0, 112, 192)); // dark blue
        this.colorQuote = new Color(display, new RGB(106, 115, 125)); // gray
        this.colorList = new Color(display, new RGB(0, 128, 0)); // green
        this.colorLink = new Color(display, new RGB(0, 90, 156)); // link blue
    }

    @Override
    public void lineGetStyle(LineStyleEvent event) {
        int lineOffset = event.lineOffset;
        String lineText = event.lineText;
        List<StyleRange> styles = new ArrayList<>();

        // Check if this line is inside a code block (``` ... ```)
        int lineStartLine = getLineNumber(fullText, lineOffset);
        if (isInsideCodeBlock(fullText, lineStartLine)) {
            StyleRange style = new StyleRange();
            style.start = lineOffset;
            style.length = lineText.length();
            style.background = new Color(display, new RGB(245, 245, 245));
            style.foreground = colorCodeBlock;
            style.fontStyle = SWT.NORMAL;
            styles.add(style);
            event.styles = styles.toArray(new StyleRange[0]);
            return;
        }

        int pos = lineOffset;

        // Check for heading: ## or # at start of line
        if (lineText.startsWith("#") || lineText.startsWith("## ") || lineText.startsWith("### ")
            || lineText.startsWith("#### ") || lineText.startsWith("##### ")) {
            StyleRange style = new StyleRange();
            style.start = lineOffset;
            style.length = lineText.length();
            style.foreground = colorHeading;
            style.fontStyle = SWT.BOLD;
            style.font = getMonospaceFontIfAvailable();
            styles.add(style);
            event.styles = styles.toArray(new StyleRange[0]);
            return;
        }

        // Check for blockquote: > at start
        if (lineText.startsWith(">") || lineText.startsWith(" >")) {
            StyleRange style = new StyleRange();
            style.start = lineOffset;
            style.length = lineText.length();
            style.foreground = colorQuote;
            style.fontStyle = SWT.ITALIC;
            styles.add(style);
            event.styles = styles.toArray(new StyleRange[0]);
            return;
        }

        // Check for list item: - or * or digit. at start
        if (lineText.matches("^\\s*[\\-*•]\\s") || lineText.matches("^\\s*\\d+[.)]\\s")) {
            StyleRange style = new StyleRange();
            style.start = lineOffset;
            style.length = Math.min(2, lineText.length());
            style.foreground = colorList;
            styles.add(style);
        }

        // Scan for inline styles
        String remaining = lineText;
        int offsetInLine = 0;

        // Match patterns in priority order
        // Pattern: `code`, **bold**, [link](url)
        Pattern boldPattern = Pattern.compile("\\*\\*(.+?)\\*\\*");
        Pattern codePattern = Pattern.compile("`([^`]+)`");
        Pattern linkPattern = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
        Pattern combinedPattern = Pattern.compile(
            "(\\*\\*(.+?)\\*\\*)|(`([^`]+)`)|(\\[([^\\]]+)\\]\\(([^)]+)\\))");

        Matcher matcher = combinedPattern.matcher(remaining);
        while (matcher.find()) {
            int start = lineOffset + matcher.start();
            int end = lineOffset + matcher.end();

            if (matcher.group(2) != null) { // **bold**
                StyleRange style = new StyleRange();
                style.start = start;
                style.length = matcher.end() - matcher.start();
                style.foreground = colorBold;
                style.fontStyle = SWT.BOLD;
                styles.add(style);
            } else if (matcher.group(4) != null) { // `code`
                StyleRange style = new StyleRange();
                style.start = start;
                style.length = matcher.end() - matcher.start();
                style.foreground = colorCode;
                style.background = new Color(display, new RGB(255, 245, 238));
                style.font = getMonospaceFontIfAvailable();
                styles.add(style);
            } else if (matcher.group(5) != null) { // [link](url)
                StyleRange style = new StyleRange();
                style.start = start;
                style.length = matcher.end() - matcher.start();
                style.foreground = colorLink;
                style.underline = true;
                styles.add(style);
            }
        }

        // Match --- or === (horizontal rule) and "You:" / "AI:" / "System:" prefixes
        if (lineText.startsWith("You:") || lineText.startsWith("AI:")) {
            StyleRange style = new StyleRange();
            style.start = lineOffset;
            style.length = 4;
            style.fontStyle = SWT.BOLD;
            style.foreground = new Color(display, new RGB(0, 112, 192));
            styles.add(style);
        } else if (lineText.startsWith("System:")) {
            StyleRange style = new StyleRange();
            style.start = lineOffset;
            style.length = 7;
            style.fontStyle = SWT.BOLD;
            style.foreground = new Color(display, new RGB(128, 128, 128));
            styles.add(style);
        } else if (lineText.trim().matches("^-{3,}$") || lineText.trim().matches("^={3,}$")) {
            StyleRange style = new StyleRange();
            style.start = lineOffset;
            style.length = lineText.length();
            style.foreground = new Color(display, new RGB(200, 200, 200));
            styles.add(style);
        }

        event.styles = styles.toArray(new StyleRange[0]);
    }

    /** Get line number for a character offset in the full text */
    private int getLineNumber(String text, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** Check if a given line number is inside a fenced code block */
    private boolean isInsideCodeBlock(String text, int lineNum) {
        String[] lines = text.split("\\n");
        boolean inBlock = false;
        int count = Math.min(lineNum + 1, lines.length);
        for (int i = 0; i < count; i++) {
            if (lines[i].trim().startsWith("```")) {
                inBlock = !inBlock;
            }
        }
        return inBlock;
    }

    private org.eclipse.swt.graphics.Font monospaceFont;

    private org.eclipse.swt.graphics.Font getMonospaceFontIfAvailable() {
        if (monospaceFont == null) {
            try {
                monospaceFont = new org.eclipse.swt.graphics.Font(display, "Consolas", 10, SWT.NORMAL);
            } catch (Exception e) {
                monospaceFont = new org.eclipse.swt.graphics.Font(display, "Courier New", 10, SWT.NORMAL);
            }
        }
        return monospaceFont;
    }

    /** Dispose colors */
    public void dispose() {
        if (colorBold != null) colorBold.dispose();
        if (colorCode != null) colorCode.dispose();
        if (colorCodeBlock != null) colorCodeBlock.dispose();
        if (colorHeading != null) colorHeading.dispose();
        if (colorQuote != null) colorQuote.dispose();
        if (colorList != null) colorList.dispose();
        if (colorLink != null) colorLink.dispose();
        if (monospaceFont != null) monospaceFont.dispose();
    }
}
