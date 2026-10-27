package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.error.ApiException;
import com.finora.finance.imports.CsvParser;
import com.finora.finance.insights.Csv;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The CSV writer (formula protection) and the CSV reader (strict, bounded). */
class CsvToolsTest {

    @Test
    void textCellsThatCouldBeFormulasAreNeutralisedAndNumbersStayNumbers() {
        for (String evil : List.of("=HYPERLINK(\"http://evil\",\"x\")", "+1+1", "-2+3", "@SUM(A1)", "\tcmd", "\rcmd", "=1+1", "-1 OR 1", "+cmd|' /C calc'!A0")) {
            String cell = Csv.cell(evil);
            assertThat(cell).as(evil).matches("(?s)^\"?'.*");
            assertThat(cell.replace("\"", "")).startsWith("'");
        }
        assertThat(Csv.cell("-12.50")).isEqualTo("-12.50");
        assertThat(Csv.cell("1234.5600")).isEqualTo("1234.5600");
        assertThat(Csv.cell("Acme, Inc.")).isEqualTo("\"Acme, Inc.\"");
        assertThat(Csv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Csv.cell(null)).isEmpty();
        StringBuilder out = new StringBuilder();
        Csv.row(out, List.of("a", "=b", "1.00"));
        assertThat(out.toString()).isEqualTo("a,'=b,1.00\r\n");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsQuotedValuesDelimitersBomAndBlankLines() {
        CsvParser.Parsed p = CsvParser.parse(bytes("﻿Date;Amount;Note\r\n2026-03-01;10,50;\"a;b\"\r\n\r\n2026-03-02;5;\"say \"\"hi\"\"\nsecond line\"\r\n"));
        assertThat(p.delimiter()).isEqualTo(';');
        assertThat(p.headers()).containsExactly("Date", "Amount", "Note");
        assertThat(p.rows()).hasSize(2);
        assertThat(p.rows().get(0)).containsExactly("2026-03-01", "10,50", "a;b");
        assertThat(p.rows().get(1).get(2)).isEqualTo("say \"hi\"\nsecond line");
        assertThat(CsvParser.parse(bytes("a\tb\n1\t2\n")).delimiter()).isEqualTo('\t');
    }

    @Test
    void refusesWhatIsNotPlainBoundedCsv() {
        record Bad(String name, byte[] data, String message) {
        }
        StringBuilder many = new StringBuilder("a\n");
        for (int i = 0; i <= CsvParser.MAX_ROWS; i++) {
            many.append(i).append('\n');
        }
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i <= CsvParser.MAX_COLUMNS; i++) {
            wide.append(i == 0 ? "" : ",").append('c').append(i);
        }
        for (Bad b : List.of(
                new Bad("empty", new byte[0], "empty"),
                new Bad("xlsx", new byte[] {'P', 'K', 3, 4, 0, 0}, "Excel"),
                new Bad("xls", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}, "Excel"),
                new Bad("binary", new byte[] {'a', ',', 'b', '\n', 0, 1, 2}, "binary"),
                new Bad("invalid utf8", new byte[] {'a', '\n', (byte) 0xC3, (byte) 0x28}, "UTF-8"),
                new Bad("header only", bytes("a,b\n"), "no data rows"),
                new Bad("unclosed quote", bytes("a,b\n\"1,2\n"), "never closed"),
                new Bad("duplicate headers", bytes("a,A\n1,2\n"), "unique"),
                new Bad("blank header", bytes("a,\n1,2\n"), "header name"),
                new Bad("too many rows", bytes(many.toString()), "more than " + CsvParser.MAX_ROWS),
                new Bad("too many columns", bytes(wide + "\n1\n"), "columns"),
                new Bad("huge cell", bytes("a\n" + "x".repeat(CsvParser.MAX_CELL + 5) + "\n"), "longer than"))) {
            assertThatThrownBy(() -> CsvParser.parse(b.data())).as(b.name()).isInstanceOf(ApiException.class).hasMessageContaining(b.message());
        }
        assertThatThrownBy(() -> CsvParser.parse(new byte[CsvParser.MAX_BYTES + 1])).hasMessageContaining("larger than");
    }
}
