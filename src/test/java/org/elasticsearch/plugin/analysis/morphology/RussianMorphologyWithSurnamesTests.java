/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.pattern.PatternCaptureGroupTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.morphology.analyzer.MorphologyFilter;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class RussianMorphologyWithSurnamesTests extends ESTestCase {
    private record Token(String text, int position, int start, int end) {}

    private List<Token> analyze(Analyzer analyzer, String text) throws IOException {
        List<Token> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            PositionIncrementAttribute increment = stream.addAttribute(PositionIncrementAttribute.class);
            OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
            stream.reset();
            int position = -1;
            while (stream.incrementToken()) {
                position += increment.getPositionIncrement();
                tokens.add(new Token(term.toString(), position, offset.startOffset(), offset.endOffset()));
            }
            stream.end();
        }
        return tokens;
    }

    private TestAnalysis analysis(Settings options) throws IOException {
        Settings.Builder settings = Settings.builder()
            .put("index.analysis.filter.combined.type", "russian_morphology_surnames")
            .put("index.analysis.analyzer.names.tokenizer", "standard")
            .putList("index.analysis.analyzer.names.filter", "combined");
        for (String key : options.keySet()) {
            if (key.equals("stopwords")) {
                settings.putList("index.analysis.filter.combined." + key, options.getAsList(key));
            } else {
                settings.put("index.analysis.filter.combined." + key, options.get(key));
            }
        }
        return createTestAnalysis(new Index("test", "_na_"), settings.build(), new AnalysisMorphologyPlugin());
    }

    public void testEverythingIsAppliedWithOnlyOneConfiguredFilter() throws IOException {
        try (var analyzers = analysis(Settings.EMPTY).indexAnalyzers) {
            assertEquals(List.of(new Token("документ", 0, 0, 11), new Token("нилов", 2, 14, 20),
                new Token("__surname__:нилову", 2, 14, 20)), analyze(analyzers.get("names"), "документами и Нилову"));
            List<Token> captured = analyze(analyzers.get("names"), "И.И.Нилову");
            assertTrue(captured.contains(new Token("нилов", 0, 0, 10)));
            assertTrue(captured.contains(new Token("__surname__:нилову", 0, 0, 10)));
            assertEquals(0, captured.stream().filter(t -> t.position() != 0).count());
        }
    }

    public void testNormalMorphologyMatchesExistingPipeline() throws IOException {
        var morphology = new RussianLuceneMorphology();
        CharArraySet stops = new CharArraySet(List.of("и", "в", "на"), false);
        try (Analyzer reference = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                StandardTokenizer tokenizer = new StandardTokenizer();
                TokenStream fio = new PatternCaptureGroupTokenFilter(tokenizer, true,
                    Pattern.compile("^(?:\\p{L}\\.){1,2}(\\p{L}\\p{IsAlphabetic}+)$"));
                TokenStream filtered = new StopFilter(new LowerCaseFilter(fio), stops);
                return new TokenStreamComponents(tokenizer, new RemoveDuplicatesTokenFilter(new MorphologyFilter(filtered, morphology)));
            }
        }; var analyzers = analysis(Settings.builder().putList("stopwords", "и", "в", "на").build()).indexAnalyzers) {
            for (String input : List.of("Японские автомобили и документы", "тест пм тест", "И.И.Нилову и И.Петрову",
                "МИНИСТЕРСТВО ФИНАНСОВ РОССИЙСКОЙ ФЕДЕРАЦИИ", "123 ABC Москва-на-Дону", "Фёдоров ёлками",
                "министерству мантурову КИМ ЦОЙ ЛИ БУАЛО", "и в на")) {
                List<Token> ordinary = analyze(analyzers.get("names"), input).stream()
                    .filter(t -> !t.text().startsWith(RussianSurnameTokenFilter.PREFIX)).toList();
                assertEquals(input, analyze(reference, input), ordinary);
            }
        }
    }

    public void testSuccessfulMorphologyMustNotSuppressSurnames() throws IOException {
        try (var analyzers = analysis(Settings.EMPTY).indexAnalyzers) {
            List<String> tokens = analyze(analyzers.get("names"), "МИНИСТЕРСТВУ МАНТУРОВУ КИМ ЦОЙ ЛИ БУАЛО")
                .stream().map(Token::text).toList();
            assertTrue(tokens.containsAll(List.of("министерство", "__surname__:министерству", "мантурово",
                "__surname__:мантурову", "__surname__:ким", "__surname__:цой", "__surname__:ли", "__surname__:буало")));
        }
    }

    public void testSearchIsCaseInsensitiveAndExpandsLowercaseInitials() throws IOException {
        try (var analyzers = analysis(Settings.builder().put("mode", "search").build()).indexAnalyzers) {
            List<Token> tokens = analyze(analyzers.get("names"), "и.и.нилову");
            assertTrue(tokens.stream().anyMatch(t -> t.text().equals("__surname__:ниловым")));
            assertTrue(tokens.stream().allMatch(t -> t.position() == 0 && t.start() == 0 && t.end() == 10));
            // Reuse must not emit expansions left over from a previously partially consumed stream.
            try (TokenStream stream = analyzers.get("names").tokenStream("text", "Мантуровым")) {
                stream.reset();
                assertTrue(stream.incrementToken());
            }
            assertEquals(List.of(), analyze(analyzers.get("names"), "и"));
        }
    }

    public void testStopwordAndInitialsSettings() throws IOException {
        try (var analyzers = analysis(Settings.builder().putList("stopwords", "_none_")
            .put("capture_initials", false).build()).indexAnalyzers) {
            assertEquals(List.of(new Token("и", 0, 0, 1)), analyze(analyzers.get("names"), "И"));
            assertEquals(List.of(new Token("и.и.нилову", 0, 0, 10)), analyze(analyzers.get("names"), "И.И.Нилову"));
        }
        try (var analyzers = analysis(Settings.builder().putList("stopwords", "документами").build()).indexAnalyzers) {
            assertEquals(List.of(), analyze(analyzers.get("names"), "документами"));
            // Ordinary stopwords do not remove a surname with the same spelling.
            assertEquals(List.of(new Token("__surname__:документами", 0, 0, 11)), analyze(analyzers.get("names"), "Документами"));
        }
    }

    public void testProductionStopwordsInIndexAndSearch() throws IOException {
        List<String> words;
        try (var stream = getClass().getResourceAsStream("/production-stopwords.txt")) {
            assertNotNull(stream);
            words = new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        assertEquals(99, words.size());
        for (String mode : List.of("index", "search")) {
            try (var analyzers = analysis(Settings.builder().put("mode", mode).putList("stopwords", words).build()).indexAnalyzers) {
                var analyzer = analyzers.get("names");
                List<Token> stopped = analyze(analyzer, String.join(" ", words));
                assertTrue(stopped.stream().allMatch(t -> t.text().startsWith(RussianSurnameTokenFilter.PREFIX)));
                if (mode.equals("index")) {
                    assertTrue(stopped.isEmpty());
                }
                List<Token> mixed = analyze(analyzer, "документами ли ко Нилову");
                assertEquals(List.of(new Token("документ", 0, 0, 11), new Token("нилов", 3, 18, 24)),
                    mixed.stream().filter(t -> !t.text().startsWith(RussianSurnameTokenFilter.PREFIX)).toList());
                assertTrue(mixed.contains(new Token("__surname__:нилову", 3, 18, 24)));
                List<String> surnames = analyze(analyzer, "Ли Ко КИМ ЦОЙ").stream().map(Token::text).toList();
                assertTrue(surnames.containsAll(List.of("__surname__:ли", "__surname__:ко", "__surname__:ким", "__surname__:цой")));
                assertFalse(surnames.contains("ли"));
                assertFalse(surnames.contains("ко"));
            }
        }
    }
}
