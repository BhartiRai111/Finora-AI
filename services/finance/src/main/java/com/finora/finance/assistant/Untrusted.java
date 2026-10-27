package com.finora.finance.assistant;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Text that a person, an import or an OCR run put into the data (category names, payees, vendor names, descriptions) is data, never an
 * instruction. It is cleaned before display, it never enters a prompt (the assistant sends placeholders such as "item 3" instead), and text
 * that looks like an attempt to instruct a model is counted and logged as a security event without its content.
 */
final class Untrusted {

    private static final Pattern INSTRUCTIONLIKE = Pattern.compile(
            "(?i)(ignore|disregard|forget|override)\\s+(all\\s+|any\\s+|the\\s+|your\\s+|previous\\s+|prior\\s+|above\\s+)*(instructions?|rules?|prompts?)"
                    + "|system\\s+prompt|you\\s+are\\s+now|reveal\\s+(the\\s+|your\\s+)?(secret|key|password|prompt)|api[_\\s-]?key|<\\s*/?\\s*(system|assistant|tool)\\b"
                    + "|\\b(delete|drop)\\s+(all|every)\\b");

    private Untrusted() {
    }

    /** One line, no control characters, bounded. */
    static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("[\\p{Cntrl}\\p{Cf}]+", " ").replaceAll("\\s{2,}", " ").trim();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }

    static boolean suspicious(String text) {
        return text != null && INSTRUCTIONLIKE.matcher(text).find();
    }

    static long suspiciousCount(List<String> texts) {
        return texts.stream().filter(Untrusted::suspicious).count();
    }
}
