/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class RussianSurnameTokenFilterTests extends ESTestCase {
    private Analyzer analyzer(boolean search, boolean uppercaseOnly, int maxExpansions, int maxTokenLength) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                WhitespaceTokenizer tokenizer = new WhitespaceTokenizer();
                return new TokenStreamComponents(tokenizer, new RussianSurnameTokenFilter(tokenizer,
                    new RussianSurnameOptions(search, uppercaseOnly, maxExpansions, maxTokenLength), RussianSurnameRules.getInstance()));
            }
        };
    }

    private record Token(String term, int position, int start, int end, int length) {}

    private List<Token> analyze(Analyzer analyzer, String text) throws IOException {
        List<Token> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            PositionIncrementAttribute position = stream.addAttribute(PositionIncrementAttribute.class);
            OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
            PositionLengthAttribute length = stream.addAttribute(PositionLengthAttribute.class);
            stream.reset();
            int current = -1;
            while (stream.incrementToken()) {
                current += position.getPositionIncrement();
                tokens.add(new Token(term.toString(), current, offset.startOffset(), offset.endOffset(), length.getPositionLength()));
            }
            stream.end();
        }
        return tokens;
    }

    public void testIndexSkipsTokensWithoutEndingTheStreamAndPreservesOffsets() throws IOException {
        try (Analyzer analyzer = analyzer(false, true, 64, 128)) {
            assertEquals(List.of(new Token("__surname__:нилову", 1, 6, 12, 1),
                new Token("__surname__:федоров", 3, 15, 22, 1)), analyze(analyzer, "текст Нилову и Фёдоров"));
            assertEquals(List.of(), analyze(analyzer, "текст и нилову"));
            assertEquals(List.of(new Token("__surname__:ким", 0, 0, 3, 1)), analyze(analyzer, "Ким"));
        }
    }

    public void testSearchEmitsUniqueAlternativesAtSamePosition() throws IOException {
        try (Analyzer analyzer = analyzer(true, false, 64, 128)) {
            List<Token> tokens = analyze(analyzer, "нилову мантуровым");
            assertEquals("__surname__:нилову", tokens.get(0).term());
            assertTrue(tokens.stream().anyMatch(t -> t.term().equals("__surname__:нилов") && t.position() == 0));
            assertTrue(tokens.stream().anyMatch(t -> t.term().equals("__surname__:мантуров") && t.position() == 1));
            assertEquals(tokens.size(), new HashSet<>(tokens).size());
            for (Token token : tokens) {
                assertEquals(1, token.length());
                assertEquals(token.position() == 0 ? 0 : 7, token.start());
                assertEquals(token.position() == 0 ? 6 : 17, token.end());
            }
        }
    }

    public void testCapAndReuseAfterPartiallyConsumedStream() throws IOException {
        try (Analyzer analyzer = analyzer(true, false, 3, 128)) {
            assertEquals(3, analyze(analyzer, "нилову").size());
            try (TokenStream stream = analyzer.tokenStream("text", "нилову")) {
                stream.reset();
                assertTrue(stream.incrementToken());
            }
            assertEquals(List.of(new Token("__surname__:шевченко", 0, 0, 8, 1)), analyze(analyzer, "Шевченко"));
        }
    }

    public void testTrailingSkippedPositions() throws IOException {
        try (Analyzer analyzer = analyzer(false, true, 64, 128);
             TokenStream stream = analyzer.tokenStream("text", "Нилов слово и")) {
            PositionIncrementAttribute position = stream.addAttribute(PositionIncrementAttribute.class);
            stream.reset();
            assertTrue(stream.incrementToken());
            assertFalse(stream.incrementToken());
            stream.end();
            assertEquals(2, position.getPositionIncrement());
        }
    }

    public void testCandidateControls() throws IOException {
        try (Analyzer analyzer = analyzer(false, false, 64, 6)) {
            List<Token> tokens = analyze(analyzer, "И И.И.Нилов Ivanov Нилов123 Мантурову нилов");
            assertEquals(List.of(new Token("__surname__:нилов", 5, 38, 43, 1)), tokens);
        }
    }

    public void testShortForeignAndAmbiguousSurnamesAreKept() throws IOException {
        try (Analyzer analyzer = analyzer(false, true, 64, 128)) {
            List<Token> tokens = analyze(analyzer, "КИМ ЦОЙ Ли Белый Толстой Шмидт");
            assertEquals(List.of("__surname__:ким", "__surname__:цой", "__surname__:ли", "__surname__:белый",
                "__surname__:толстой", "__surname__:шмидт"), tokens.stream().map(Token::term).toList());
            assertEquals(0, tokens.get(0).position());
            assertEquals("__surname__:цой", analyze(analyzer, "Цой подписал").get(0).term());
        }
    }

    public void testFactoryRegistrationAndValidation() throws IOException {
        Settings settings = Settings.builder()
            .put("index.analysis.filter.surnames.type", "russian_surname")
            .put("index.analysis.filter.surnames.mode", "search")
            .put("index.analysis.filter.surnames.max_expansions", 1)
            .put("index.analysis.analyzer.names.tokenizer", "standard")
            .putList("index.analysis.analyzer.names.filter", "surnames")
            .build();
        TestAnalysis analysis = createTestAnalysis(new Index("test", "_na_"), settings, new AnalysisMorphologyPlugin());
        try (var analyzers = analysis.indexAnalyzers) {
            assertEquals(List.of(new Token("__surname__:нилову", 0, 0, 6, 1)), analyze(analyzers.get("names"), "нилову"));
        }
        for (Settings invalid : List.of(Settings.builder().put("mode", "other").build(),
            Settings.builder().put("max_expansions", 0).build(), Settings.builder().put("max_expansions", 257).build(),
            Settings.builder().put("max_token_length", 1).build(), Settings.builder().put("max_token_length", 257).build())) {
            expectThrows(IllegalArgumentException.class,
                () -> new RussianSurnameTokenFilterFactory(null, null, "surnames", invalid));
        }
    }
}
