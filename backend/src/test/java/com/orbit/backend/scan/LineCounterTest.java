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

    @Test
    void csharpVerbatimStringsKeepBackslashesAndSpanLines() {
        assertThat(LineCounter.countCodeLines("string p = @\"C:\\dir\\\"; // c\nint x;\n", Style.C_SHARP)).isEqualTo(2);
        assertThat(LineCounter.countCodeLines("var s = @\"a \"\"q\"\"\n// text\n/* text */\n\";\n// real\n", Style.C_SHARP)).isEqualTo(4);
    }

    @Test
    void dartNestedBlockCommentsTripleQuotesAndRawStrings() {
        assertThat(LineCounter.countCodeLines("/* a /* b */ still comment */\nvoid main() {}\n", Style.DART)).isEqualTo(1);
        assertThat(LineCounter.countCodeLines("var s = \'\'\'\n// text\n\'\'\';\n/// doc\n", Style.DART)).isEqualTo(3);
        assertThat(LineCounter.countCodeLines("var p = r'C:\\x\\'; // c\nint y = 1;\n", Style.DART)).isEqualTo(2);
    }

    @Test
    void sqlCommentsStringsAndDollarQuoting() {
        assertThat(LineCounter.countCodeLines("-- c\nSELECT 1; -- t\n/* b\n c */\n\nSELECT 2;\n", Style.SQL)).isEqualTo(2);
        assertThat(LineCounter.countCodeLines("SELECT '--not a comment';\n", Style.SQL)).isEqualTo(1);
        assertThat(LineCounter.countCodeLines("SELECT 'it''s', 'C:\\'; -- c\nSELECT 1;\n", Style.SQL)).isEqualTo(2);
        assertThat(LineCounter.countCodeLines("SELECT 'a\n-- inside\nb';\n-- out\n", Style.SQL)).isEqualTo(3);
        assertThat(LineCounter.countCodeLines("CREATE FUNCTION f() AS $$\n-- inside\nSELECT 1;\n$$ LANGUAGE sql;\n-- out\n", Style.SQL)).isEqualTo(4);
        assertThat(LineCounter.countCodeLines("SELECT $1;\n-- c\nSELECT $2;\n", Style.SQL)).isEqualTo(2);
    }

    @Test
    void cPreprocessorLinesAreCode() {
        assertThat(c("#include <stdio.h>\n#define X 1 /* m */\nint main(void) { return 0; } // e\n")).isEqualTo(3);
    }
}
