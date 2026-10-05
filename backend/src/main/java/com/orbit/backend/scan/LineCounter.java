package com.orbit.backend.scan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

/**
 * Counts the <em>source-code lines</em> of one file: every line that holds at least one character of code. Blank
 * lines and lines that contain only a comment are not counted.
 *
 * <p>The scan is a small hand-written lexer, not a regular expression, so comment markers inside string literals
 * (for example {@code "src/**}{@code /*.java"}) are not mistaken for comments. The {@link Style} says which comment and
 * string rules apply:
 * <ul>
 *   <li>{@link Style#C_LIKE}: {@code //} and block comments; {@code "..."}, {@code '...'}, multi-line
 *       {@code `...`} and {@code """..."""} strings (Java, JavaScript, TypeScript, C, C++).</li>
 *   <li>{@link Style#C_SHARP}: like C-like, plus verbatim strings {@code @"..."} (a doubled quote is a quote and a
 *       backslash is an ordinary character).</li>
 *   <li>{@link Style#DART}: like C-like, plus {@code '''...'''} strings, raw strings {@code r'...'} and block
 *       comments that nest.</li>
 *   <li>{@link Style#HASH}: {@code #} comments and triple-quoted strings (Python). Docstrings are string literals,
 *       so they are counted as code.</li>
 *   <li>{@link Style#SQL}: {@code --} and block comments; strings and quoted identifiers that may span lines, with
 *       a doubled quote as the escape; PostgreSQL dollar quoting ({@code $$...$$}).</li>
 * </ul>
 * Known limitation: a JavaScript regular-expression literal that contains a backtick or an odd quote can confuse
 * the lexer for the rest of that file. The effect is limited to a slightly different line count.
 */
public final class LineCounter {

    public enum Style { C_LIKE, C_SHARP, DART, HASH, SQL }

    /** How the end of a multi-line string is found. */
    private enum Mode { ESCAPES, RAW, DOUBLING }

    private LineCounter() {
    }

    public static int countCodeLines(Reader reader, Style style) throws IOException {
        BufferedReader in = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        int count = 0;
        int blockDepth = 0;           // > 0: inside a block comment
        String multi = null;          // inside a multi-line string: its closing delimiter
        Mode mode = Mode.ESCAPES;
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
                if (blockDepth > 0) {
                    int open = style == Style.DART ? line.indexOf("/*", i) : -1;
                    int close = line.indexOf("*/", i);
                    if (open >= 0 && (close < 0 || open < close)) {
                        blockDepth++;
                        i = open + 2;
                    } else if (close >= 0) {
                        blockDepth--;
                        i = close + 2;
                    } else {
                        i = n;
                    }
                    continue;
                }
                if (multi != null) {
                    code = true;
                    int end = findClosing(line, i, multi, mode);
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
                } else if (startsLineComment(line, i, style)) {
                    i = n; // the rest of the line is a comment
                } else if (style != Style.HASH && c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                    blockDepth = 1;
                    i += 2;
                } else if (style == Style.SQL && (c == '\'' || c == '"')) {
                    code = true; // SQL strings and quoted identifiers may continue on the next line
                    multi = String.valueOf(c);
                    mode = Mode.DOUBLING;
                    i++;
                } else if (style == Style.SQL && c == '$' && dollarTag(line, i) != null) {
                    code = true;
                    multi = dollarTag(line, i);
                    mode = Mode.RAW;
                    i += multi.length();
                } else if (style == Style.C_SHARP && c == '@' && verbatimStart(line, i) > 0) {
                    code = true;
                    multi = "\"";
                    mode = Mode.DOUBLING;
                    i += verbatimStart(line, i);
                } else if ((c == '"' || c == '\'') && tripleAllowed(style, c) && line.startsWith("" + c + c + c, i)) {
                    code = true;
                    multi = "" + c + c + c;
                    mode = style == Style.DART && isRawPrefix(line, i) ? Mode.RAW : Mode.ESCAPES;
                    i += 3;
                } else if (c == '`' && (style == Style.C_LIKE)) {
                    code = true;
                    multi = "`";
                    mode = Mode.ESCAPES;
                    i++;
                } else if (c == '"' || c == '\'') {
                    code = true;
                    i = endOfQuoted(line, i + 1, c, style == Style.DART && isRawPrefix(line, i));
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

    /** Convenience for tests and small inputs. */
    public static int countCodeLines(String text, Style style) {
        try {
            return countCodeLines(new StringReader(text), style);
        } catch (IOException e) {
            throw new IllegalStateException(e); // a StringReader cannot fail
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static boolean startsLineComment(String line, int i, Style style) {
        return switch (style) {
            case HASH -> line.charAt(i) == '#';
            case SQL -> line.startsWith("--", i);
            default -> line.startsWith("//", i);
        };
    }

    /** {@code """} opens a multi-line string in most languages; {@code '''} only in Dart and Python. */
    private static boolean tripleAllowed(Style style, char quote) {
        return quote == '"' ? style != Style.SQL : style == Style.DART || style == Style.HASH;
    }

    /** True when the quote at {@code i} follows a Dart raw-string marker {@code r}. */
    private static boolean isRawPrefix(String line, int i) {
        return i > 0 && line.charAt(i - 1) == 'r' && (i == 1 || !Character.isLetterOrDigit(line.charAt(i - 2)));
    }

    /** Length of {@code @"} / {@code @$"} (C# verbatim string start) at {@code i}, or 0. */
    private static int verbatimStart(String line, int i) {
        if (line.startsWith("@\"", i)) {
            return 2;
        }
        return line.startsWith("@$\"", i) ? 3 : 0;
    }

    /** {@code $$} or {@code $tag$} at {@code i} (PostgreSQL dollar quoting), or null; {@code $1} is not one. */
    private static String dollarTag(String line, int i) {
        int j = i + 1;
        while (j < line.length() && (Character.isLetter(line.charAt(j)) || line.charAt(j) == '_')) {
            j++;
        }
        return j < line.length() && line.charAt(j) == '$' ? line.substring(i, j + 1) : null;
    }

    /** Index just after the closing {@code quote} of a single-line string that starts at {@code from}. */
    private static int endOfQuoted(String line, int from, char quote, boolean raw) {
        int i = from;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\' && !raw) {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return n; // unterminated: the string ends with the line
    }

    /** Index of the closing delimiter at or after {@code from}, or -1 when the string goes on. */
    private static int findClosing(String line, int from, String delimiter, Mode mode) {
        int n = line.length();
        if (mode == Mode.RAW) {
            return line.indexOf(delimiter, from);
        }
        int i = from;
        while (i < n) {
            char c = line.charAt(i);
            if (mode == Mode.ESCAPES && c == '\\') {
                i += 2;
            } else if (mode == Mode.DOUBLING && c == delimiter.charAt(0)) {
                if (i + 1 < n && line.charAt(i + 1) == c) {
                    i += 2; // a doubled quote is a quote character inside the string
                } else {
                    return i;
                }
            } else if (mode == Mode.ESCAPES && line.startsWith(delimiter, i)) {
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
