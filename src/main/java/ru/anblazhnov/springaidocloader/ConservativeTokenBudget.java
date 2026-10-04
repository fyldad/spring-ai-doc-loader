package ru.anblazhnov.springaidocloader;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** A deliberately pessimistic bound for byte/subword tokenizers, including normalization. */
final class ConservativeTokenBudget {
    static final String VERSION = "utf8-nfkc-bound-v1";

    static int count(String text) {
        return Math.max(text.getBytes(StandardCharsets.UTF_8).length,
                Normalizer.normalize(text, Normalizer.Form.NFKC).getBytes(StandardCharsets.UTF_8).length) + 2;
    }

    static int end(String source, int start, int limit, int budget) {
        int end = start;
        int cost = 2;
        while (end < limit) {
            int next = end + Character.charCount(source.codePointAt(end));
            // Treat CRLF as one indivisible source boundary.
            if (source.charAt(end) == '\r' && next < limit && source.charAt(next) == '\n') next++;
            int added = count(source.substring(end, next)) - 2;
            if (cost + added > budget) break;
            cost += added;
            end = next;
        }
        if (end == start) throw new IllegalArgumentException("Context header leaves no room for a source character");
        return end;
    }

    static String abbreviate(String text, int budget) {
        if (count(text) <= budget) return text;
        return text.substring(0, end(text, 0, text.length(), budget - 3)) + "...";
    }
}
