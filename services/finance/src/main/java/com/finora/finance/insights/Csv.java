package com.finora.finance.insights;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CSV writing with spreadsheet formula protection. A text cell that starts with = + - @ TAB or CR would be executed as a formula by
 * Excel, LibreOffice and Google Sheets, so it is prefixed with an apostrophe (OWASP CSV-injection guidance). A cell that is exactly a
 * plain decimal number produced by the backend ("-12.50") is left alone: it cannot be a formula and must stay numeric.
 */
public final class Csv {

    private static final Pattern NUMBER = Pattern.compile("^-?\\d{1,15}(\\.\\d{1,4})?$");

    private Csv() {
    }

    public static String cell(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw;
        if (!v.isEmpty() && !NUMBER.matcher(v).matches()) {
            char c = v.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                v = "'" + v;
            }
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf(';') >= 0) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    public static void row(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }
}
