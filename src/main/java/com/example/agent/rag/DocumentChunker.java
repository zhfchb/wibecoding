package com.example.agent.rag;

import java.util.ArrayList;
import java.util.List;

public class DocumentChunker {

    private static final String BOUNDARIES = "。！？；，、.!?;,:：";

    private final int maxChars;
    private final int overlapChars;

    public DocumentChunker(int maxChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars must be >= 0 and < maxChars");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.length() <= maxChars) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : splitParagraphs(normalized)) {
            for (String piece : splitIfTooLong(paragraph)) {
                appendPiece(chunks, current, piece);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    paragraphs.add(current.toString());
                    current.setLength(0);
                }
            } else {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(line.trim());
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(current.toString());
        }
        return paragraphs;
    }

    private List<String> splitIfTooLong(String text) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }

        List<String> pieces = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > maxChars) {
            int cut = findCut(remaining);
            pieces.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(Math.max(0, cut - overlapChars)).trim();
            if (remaining.isEmpty()) {
                break;
            }
        }
        if (!remaining.isBlank()) {
            pieces.add(remaining.trim());
        }
        return pieces;
    }

    private int findCut(String text) {
        int cut = maxChars;
        for (int i = maxChars - 1; i > maxChars / 2; i--) {
            if (isBoundary(text.charAt(i))) {
                cut = i + 1;
                break;
            }
        }
        return Math.min(cut, text.length());
    }

    private boolean isBoundary(char c) {
        return Character.isWhitespace(c) || BOUNDARIES.indexOf(c) >= 0;
    }

    private void appendPiece(List<String> chunks, StringBuilder current, String piece) {
        if (current.isEmpty()) {
            current.append(piece);
            return;
        }

        if (current.length() + 1 + piece.length() <= maxChars) {
            current.append('\n').append(piece);
            return;
        }

        String previous = current.toString();
        chunks.add(previous.trim());
        current.setLength(0);
        String overlap = tail(previous);
        if (!overlap.isEmpty() && overlap.length() + 1 + piece.length() <= maxChars) {
            current.append(overlap).append('\n');
        }
        current.append(piece);
    }

    private String tail(String text) {
        if (text.length() <= overlapChars) {
            return text;
        }
        return text.substring(text.length() - overlapChars);
    }
}
