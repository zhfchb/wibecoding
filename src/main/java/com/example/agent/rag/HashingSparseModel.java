package com.example.agent.rag;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于词项哈希的稀疏编码模型（TF-IDF 风格，无全局 IDF 故用 1+log(tf)）。
 * <p>
 * 复用与 {@link LexicalHashEmbeddingModel} 一致的分词规则（拉丁词/中文单字与二元组），
 * 把词项哈希到固定维度的索引空间，产出 {@link SparseEmbedding}。
 * 为控制 Qdrant payload 体积，仅保留权重最高的 {@code maxTerms} 个词项。
 */
public class HashingSparseModel implements SparseEmbeddingModel {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z]+|[0-9]+|[\u4e00-\u9fff]");

    private final int dimension;
    private final int maxTerms;

    public HashingSparseModel() {
        this(1 << 16, 512);
    }

    public HashingSparseModel(int dimension, int maxTerms) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        if (maxTerms <= 0) {
            throw new IllegalArgumentException("maxTerms must be positive");
        }
        this.dimension = dimension;
        this.maxTerms = maxTerms;
    }

    @Override
    public SparseEmbedding embed(String text) {
        if (text == null || text.isBlank()) {
            return SparseEmbedding.empty();
        }
        Map<Integer, Float> weights = new HashMap<>();
        for (String token : tokenize(text)) {
            int index = Math.floorMod(stableHash(token), dimension);
            weights.merge(index, 1f, Float::sum);
        }
        if (weights.isEmpty()) {
            return SparseEmbedding.empty();
        }
        // TF 加权：1 + log(tf)，并按权重截断保留 top maxTerms
        List<Map.Entry<Integer, Float>> ranked = weights.entrySet().stream()
                .peek(e -> e.setValue((float) (1 + Math.log(e.getValue()))))
                .sorted(Comparator.<Map.Entry<Integer, Float>>comparingDouble(e -> -e.getValue()))
                .limit(maxTerms)
                .toList();

        int[] indices = new int[ranked.size()];
        float[] values = new float[ranked.size()];
        for (int i = 0; i < ranked.size(); i++) {
            indices[i] = ranked.get(i).getKey();
            values[i] = ranked.get(i).getValue();
        }
        return new SparseEmbedding(indices, values);
    }

    private List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> latinTokens = new java.util.ArrayList<>();
        List<Character> chineseChars = new java.util.ArrayList<>();
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

        List<String> tokens = new java.util.ArrayList<>();
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
}
