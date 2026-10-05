package com.orbit.backend.scan;

import com.orbit.backend.scan.LineCounter.Style;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineCounterTest {

    private static int c(String text) {
        return LineCounter.countCodeLines(text, Style.C_LIKE);
    }

    private static int py(String text) {
        return LineCounter.countCodeLines(text, Style.HASH);
    }

    @Test
    void blankAndCommentOnlyLinesAreNotCounted() {
        assertThat(c("\n// c\nint a;\n   \n/* x */\nint b; // t\n/* start\n still\n end */\n")).isEqualTo(2);
        assertThat(c("// a\n/* b */\n/**\n * c\n */\n")).isZero();
        assertThat(c("")).isZero();
    }

    @Test
    void codeAroundBlockCommentsIsCounted() {
        assertThat(c("int a; /* open\nstill comment\n*/\n")).isEqualTo(1);
        assertThat(c("/* open\nstill\n*/ int a;\n")).isEqualTo(1);
    }

    @Test
    void commentMarkersInsideStringsAreNotComments() {
        assertThat(c("String g = \"src/**/*.java\";\nint a;\nint b;\n")).isEqualTo(3);
        assertThat(c("String u = \"http://x\";\n")).isEqualTo(1);
        assertThat(c("String s = \"a\\\"//b\";\nint x;\n")).isEqualTo(2);
        assertThat(c("char q = '\"'; // c\nint x; /* y */\n")).isEqualTo(2);
    }

    @Test
    void multiLineStringsAreCode() {
        assertThat(c("String t = \"\"\"\n  // not a comment\n  /* neither */\n  \"\"\";\n// real comment\n")).isEqualTo(4);
        assertThat(c("const t = `\n// inside\n`;\n// out\n")).isEqualTo(3);
    }

    @Test
    void windowsLineEndingsAndBomAreHandled() {
        assertThat(c("﻿// c\r\nint a;\r\n\r\n")).isEqualTo(1);
    }

    @Test
    void pythonHashComments() {
        assertThat(py("# c\nimport os\n\nx = 1  # t\ny = '#not'\n")).isEqualTo(3);
        assertThat(py("def f():\n    \"\"\"doc\n    # inside\n    \"\"\"\n# c\n")).isEqualTo(4);
    }
}
