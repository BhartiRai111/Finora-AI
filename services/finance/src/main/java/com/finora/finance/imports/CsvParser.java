package com.finora.finance.imports;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A strict, bounded CSV reader (RFC 4180 quoting, UTF-8 with optional BOM, comma, semicolon or tab delimiter). It refuses anything that is
 * not plain text instead of guessing: binary data, a spreadsheet package, invalid UTF-8, too many rows, columns or very long cells. Cell
 * text is only ever treated as data; nothing in a file is interpreted.
 */
public final class CsvParser {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 5000;
    public static final int MAX_COLUMNS = 40;
    public static final int MAX_CELL = 1000;

    public record Parsed(List<String> headers, List<List<String>> rows, char delimiter) {
    }

    private CsvParser() {
    }

    private static ApiException bad(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    public static Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw bad("The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than " + (MAX_BYTES / 1024 / 1024) + " MB");
        }
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
            throw bad("Excel (.xlsx) files are not supported. Export the sheet as CSV and upload that");
        }
        if (bytes.length >= 4 && bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF) {
            throw bad("Excel (.xls) files are not supported. Export the sheet as CSV and upload that");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw bad("The file is not valid UTF-8 text. Save it as CSV (UTF-8) and try again");
        }
        if (text.indexOf('\0') >= 0) {
            throw bad("The file contains binary data and is not a CSV file");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char delimiter = detect(text);
        List<List<String>> all = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty() && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (c == delimiter) {
                row.add(finish(cell, wasQuoted));
                cell.setLength(0);
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(finish(cell, wasQuoted));
                cell.setLength(0);
                wasQuoted = false;
                addRow(all, row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
            if (cell.length() > MAX_CELL) {
                throw bad("A cell is longer than " + MAX_CELL + " characters (row " + (all.size() + 1) + ")");
            }
        }
        if (quoted) {
            throw bad("A quoted value is never closed; check the quotes in the file");
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(finish(cell, wasQuoted));
            addRow(all, row);
        }
        if (all.isEmpty()) {
            throw bad("The file has no header row");
        }
        List<String> headers = all.get(0).stream().map(String::trim).toList();
        if (headers.size() > MAX_COLUMNS) {
            throw bad("The file has more than " + MAX_COLUMNS + " columns");
        }
        if (headers.stream().anyMatch(String::isEmpty)) {
            throw bad("Every column needs a header name");
        }
        if (headers.stream().map(String::toLowerCase).distinct().count() != headers.size()) {
            throw bad("Column headers must be unique");
        }
        List<List<String>> data = all.subList(1, all.size());
        if (data.isEmpty()) {
            throw bad("The file has a header row but no data rows");
        }
        if (data.size() > MAX_ROWS) {
            throw bad("The file has more than " + MAX_ROWS + " data rows; split it into smaller files");
        }
        return new Parsed(headers, List.copyOf(data), delimiter);
    }

    private static String finish(StringBuilder cell, boolean wasQuoted) {
        String v = cell.toString();
        return wasQuoted ? v : v.trim();
    }

    private static void addRow(List<List<String>> all, List<String> row) {
        if (row.size() == 1 && row.get(0).isEmpty()) {
            return; // blank line
        }
        if (all.size() > MAX_ROWS + 1) {
            throw bad("The file has more than " + MAX_ROWS + " data rows; split it into smaller files");
        }
        all.add(row);
    }

    private static char detect(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        int commas = 0;
        int semis = 0;
        int tabs = 0;
        boolean q = false;
        for (char c : first.toCharArray()) {
            if (c == '"') {
                q = !q;
            } else if (!q) {
                if (c == ',') {
                    commas++;
                } else if (c == ';') {
                    semis++;
                } else if (c == '\t') {
                    tabs++;
                }
            }
        }
        if (tabs > commas && tabs > semis) {
            return '\t';
        }
        return semis > commas ? ';' : ',';
    }
}
