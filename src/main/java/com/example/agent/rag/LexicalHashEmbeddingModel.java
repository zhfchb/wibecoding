package com.example.agent.rag;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LexicalHashEmbeddingModel implements EmbeddingModel {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z]+|[0-9]+|[\u4e00-\u9fff]");

    private final int dimension;

    public LexicalHashEmbeddingModel(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        this.dimension = dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokenize(text)) {
            counts.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int index = Math.floorMod(stableHash(entry.getKey()), dimension);
            vector[index] += (float) (1 + Math.log(entry.getValue()));
        }
        normalize(vector);
        return vector;
    }

    private List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> latinTokens = new ArrayList<>();
        List<Character> chineseChars = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            char first = token.charAt(0);
            if (first >= 'a' && first <= 'z' || first >= '0' && first <= '9') {
                latinTokens.add(token);
            } else {
                chineseChars.add(first);
            }
        }

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < latinTokens.size(); i++) {
            tokens.add(latinTokens.get(i));
            if (i + 1 < latinTokens.size()) {
                tokens.add(latinTokens.get(i) + "_" + latinTokens.get(i + 1));
            }
        }
        for (int i = 0; i < chineseChars.size(); i++) {
            tokens.add(String.valueOf(chineseChars.get(i)));
            if (i + 1 < chineseChars.size()) {
                tokens.add("" + chineseChars.get(i) + chineseChars.get(i + 1));
            }
        }
        return tokens;
    }

    private int stableHash(String value) {
        int hash = 0;
        for (int i = 0; i < value.length(); i++) {
            hash = 31 * hash + value.charAt(i);
        }
        return hash & Integer.MAX_VALUE;
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum <= 0) {
            return;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
