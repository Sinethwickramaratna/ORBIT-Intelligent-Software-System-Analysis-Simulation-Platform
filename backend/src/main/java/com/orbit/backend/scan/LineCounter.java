package com.orbit.backend.scan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Counts the <em>source-code lines</em> of one file: every line that holds at least one character of code. Blank
 * lines and lines that contain only a comment are not counted.
 *
 * <p>The scan is a small hand-written lexer, not a regular expression, so comment markers inside string literals
 * (for example {@code "src/**}{@code /*.java"}) are not mistaken for comments. Two comment styles are known:
 * <ul>
 *   <li>{@link Style#C_LIKE}: {@code // line} and {@code /* block *}{@code /} comments, with {@code "..."},
 *       {@code '...'} and multi-line {@code `...`} / {@code """..."""} strings (Java, JavaScript, TypeScript, ...).</li>
 *   <li>{@link Style#HASH}: {@code # line} comments, with {@code "..."}, {@code '...'} and triple-quoted strings
 *       (Python). Docstrings are string literals, so they are counted as code.</li>
 * </ul>
 * Known limitation: a JavaScript regular-expression literal that contains a backtick or an odd quote can confuse
 * the lexer for the rest of that file. The effect is limited to a slightly different line count.
 */
public final class LineCounter {

    public enum Style { C_LIKE, HASH }

    private LineCounter() {
    }

    public static int countCodeLines(Reader reader, Style style) throws IOException {
        BufferedReader in = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        return style == Style.HASH ? countHash(in) : countCLike(in);
    }

    /** Convenience for tests and small inputs. */
    public static int countCodeLines(String text, Style style) {
        try {
            return countCodeLines(new java.io.StringReader(text), style);
        } catch (IOException e) {
            throw new IllegalStateException(e); // a StringReader cannot fail
        }
    }

    // ------------------------------------------------------------------------------------------------ C-like

    private static int countCLike(BufferedReader in) throws IOException {
        int count = 0;
        boolean inBlock = false;      // inside /* ... */
        String multi = null;          // inside a multi-line string: "`" or "\"\"\""
        boolean first = true;
        String line;
        while ((line = in.readLine()) != null) {
            if (first) {
                line = stripBom(line);
                first = false;
            }
            boolean code = false;
            int i = 0;
            int n = line.length();
            while (i < n) {
                if (inBlock) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) {
                        i = n;
                    } else {
                        inBlock = false;
                        i = end + 2;
                    }
                    continue;
                }
                if (multi != null) {
                    code = true;
                    int end = findClosing(line, i, multi);
                    if (end < 0) {
                        i = n;
                    } else {
                        i = end + multi.length();
                        multi = null;
                    }
                    continue;
                }
                char c = line.charAt(i);
                if (c <= ' ' || Character.isWhitespace(c)) {
                    i++;
                } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                    i = n; // rest of the line is a comment
                } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                    inBlock = true;
                    i += 2;
                } else if (c == '"' && line.startsWith("\"\"\"", i)) {
                    code = true;
                    multi = "\"\"\"";
                    i += 3;
                } else if (c == '`') {
                    code = true;
                    multi = "`";
                    i++;
                } else if (c == '"' || c == '\'') {
                    code = true;
                    i = endOfQuoted(line, i + 1, c);
                } else {
                    code = true;
                    i++;
                }
            }
            if (code) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------------ hash

    private static int countHash(BufferedReader in) throws IOException {
        int count = 0;
        String triple = null;         // inside a triple-quoted string: "'''" or "\"\"\""
        boolean first = true;
        String line;
        while ((line = in.readLine()) != null) {
            if (first) {
                line = stripBom(line);
                first = false;
            }
            boolean code = false;
            int i = 0;
            int n = line.length();
            while (i < n) {
                if (triple != null) {
                    code = true;
                    int end = findClosing(line, i, triple);
                    if (end < 0) {
                        i = n;
                    } else {
                        triple = null;
                        i = end + 3;
                    }
                    continue;
                }
                char c = line.charAt(i);
                if (c <= ' ' || Character.isWhitespace(c)) {
                    i++;
                } else if (c == '#') {
                    i = n;
                } else if ((c == '"' || c == '\'') && line.startsWith("" + c + c + c, i)) {
                    code = true;
                    triple = "" + c + c + c;
                    i += 3;
                } else if (c == '"' || c == '\'') {
                    code = true;
                    i = endOfQuoted(line, i + 1, c);
                } else {
                    code = true;
                    i++;
                }
            }
            if (code) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Index just after the closing {@code quote} of a single-line string that starts at {@code from}. */
    private static int endOfQuoted(String line, int from, char quote) {
        int i = from;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return n; // unterminated: the string ends with the line
    }

    /** Index of the closing delimiter (unescaped) at or after {@code from}, or -1. */
    private static int findClosing(String line, int from, String delimiter) {
        int i = from;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (line.startsWith(delimiter, i)) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static String stripBom(String line) {
        return !line.isEmpty() && line.charAt(0) == '﻿' ? line.substring(1) : line;
    }
}
