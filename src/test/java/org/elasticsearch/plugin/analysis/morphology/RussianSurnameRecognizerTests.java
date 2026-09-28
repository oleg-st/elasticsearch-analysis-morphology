/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.index.Index;
import org.elasticsearch.test.ESTestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class RussianSurnameRecognizerTests extends ESTestCase {
    private record Token(String text, int position, int start, int end) {}

    private List<Token> analyze(Analyzer analyzer, String text) throws Exception {
        List<Token> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text", text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            var increment = stream.addAttribute(PositionIncrementAttribute.class);
            var offset = stream.addAttribute(OffsetAttribute.class);
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

    private TestAnalysis analysis(String type, boolean enabled, String mode) throws Exception {
        var settings = Settings.builder()
            .put("index.analysis.filter.names.type", type)
            .put("index.analysis.filter.names.rules_filter", enabled)
            .put("index.analysis.filter.names.mode", mode)
            .put("index.analysis.analyzer.names.tokenizer", "standard")
            .putList("index.analysis.analyzer.names.filter", "names").build();
        return createTestAnalysis(new Index("test", "_na_"), settings, new AnalysisMorphologyPlugin());
    }

    public void testBothFactoriesPreserveOrdinaryTokensPositionsAndOffsets() throws Exception {
        for (String type : List.of("russian_surname", "russian_morphology_surnames")) {
            try (var baseline = analysis(type, false, "index").indexAnalyzers;
                 var filtered = analysis(type, true, "index").indexAnalyzers) {
                String text = "Министерство Документами Нилову Фёдоровым Достоевской КИМ ЦОЙ Ли";
                var before = analyze(baseline.get("names"), text);
                var after = analyze(filtered.get("names"), text);
                assertTrue(before.stream().anyMatch(t -> t.text().equals("__surname__:министерство")));
                assertEquals(before.stream().filter(t -> !t.text().equals("__surname__:министерство")
                    && !t.text().equals("__surname__:документами")).toList(), after);
                int start = text.indexOf("Нилову");
                assertTrue(after.contains(new Token("__surname__:нилову", 2, start, start + 6)));
            }
        }
    }

    public void testAllCasesAndHeuristicLimitations() {
        var recognizer = RussianSurnameRecognizer.fromSettings(Settings.builder().put("rules_filter", true).build(), null);
        var rules = RussianSurnameRules.getInstance();
        for (String name : List.of("Нилов", "Мантуров", "Фёдоров", "Иванова", "Достоевская", "Цой", "Ким", "Ли", "Ко",
            "Буало", "Шмидт", "Белый", "Толстой", "Шевченко", "Гоголь", "Бонч-Бруевич", "Иванов-Петров")) {
            for (var gender : List.of(RussianSurnameRules.Gender.MALE, RussianSurnameRules.Gender.FEMALE)) {
                for (int c = 0; c <= 5; c++) {
                    String form = rules.inflect(name, gender, c);
                    assertTrue(form, recognizer.accepts(form));
                }
            }
        }
        for (String word : List.of("министерство", "министерству", "документ", "документами", "федерация", "решение", "управление")) {
            assertFalse(word, recognizer.accepts(word));
        }
        // Heuristics cannot distinguish these ordinary words from surnames with the same endings.
        assertTrue(recognizer.accepts("столов"));
        for (String name : List.of("жвания", "жвании", "шостью", "сриваству", "дирекция")) {
            assertTrue(name, recognizer.accepts(name));
        }
        assertTrue(recognizer.accepts("новый"));
        assertFalse(recognizer.accepts("министерство-нилов"));
        assertTrue(recognizer.accepts("квазитест"));
        var strict = RussianSurnameRecognizer.fromSettings(Settings.builder().put("rules_filter", true)
            .put("rules_filter_mode", "strict").build(), null);
        assertFalse(strict.accepts("квазитест"));
        assertTrue(strict.accepts("нилову"));
        assertTrue(strict.accepts("шмидтом"));
    }

    public void testSearchInitialsAndExpansionCap() throws Exception {
        for (String type : List.of("russian_surname", "russian_morphology_surnames")) {
            try (var filtered = analysis(type, true, "search").indexAnalyzers) {
                var tokens = analyze(filtered.get("names"), "министерство нилову");
                assertTrue(tokens.stream().anyMatch(t -> t.text().equals("__surname__:ниловым")));
                for (var token : tokens) {
                    if (token.text().startsWith("__surname__:")) {
                        assertEquals(1, token.position());
                    }
                }
                if (type.equals("russian_morphology_surnames")) {
                    assertTrue(analyze(filtered.get("names"), "и.и.нилову").stream()
                        .anyMatch(t -> t.text().equals("__surname__:ниловым")));
                }
            }
        }
        var options = RussianSurnameOptions.fromSettings(Settings.builder().put("rules_filter", true)
            .put("mode", "search").put("max_expansions", 1).build(), null);
        assertEquals(List.of("нилову"), options.forms("Нилову", RussianSurnameRules.getInstance()));
        assertTrue(options.accepts("нилову"));
    }

    public void testAdditionalNamesAndFileAreInflectedAndValidated() throws Exception {
        var defaults = Settings.builder().put("rules_filter", true).put("rules_filter_mode", "strict").build();
        var custom = RussianSurnameRecognizer.fromSettings(Settings.builder().put(defaults)
            .putList("surnames", "Квазитест", "Фёдоров").build(), null);
        for (String word : List.of("квазитест", "квазитесту", "квазитестом", "федоровым")) {
            assertTrue(word, custom.accepts(word));
        }
        var home = createTempDir();
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/rare.txt"), "# Extra nominatives\nКвазитест\n", StandardCharsets.UTF_8);
        var environment = TestEnvironment.newEnvironment(Settings.builder().put("path.home", home).build());
        var fromFile = RussianSurnameRecognizer.fromSettings(Settings.builder().put(defaults)
            .put("surnames_path", "rare.txt").build(), environment);
        assertTrue(fromFile.accepts("квазитестом"));
        expectThrows(IllegalArgumentException.class, () -> RussianSurnameRecognizer.fromSettings(
            Settings.builder().put(defaults).putList("surnames", "Иванов123").build(), null));
        expectThrows(IllegalArgumentException.class, () -> RussianSurnameRecognizer.fromSettings(
            Settings.builder().putList("surnames", "Квазитест").build(), null));
        expectThrows(IllegalArgumentException.class, () -> RussianSurnameRecognizer.fromSettings(
            Settings.builder().put("rules_filter", true).put("rules_filter_mode", "typo").build(), null));
        expectThrows(IllegalArgumentException.class, () -> RussianSurnameRecognizer.fromSettings(
            Settings.builder().put(defaults).put("surnames_path", "missing.txt").build(), environment));
    }

    public void testCompoundExceptionsSurviveStandardTokenizerAndStrictMode() throws Exception {
        for (String mode : List.of("conservative", "strict")) {
            Settings settings = Settings.builder()
                .put("index.analysis.filter.names.type", "russian_surname")
                .put("index.analysis.filter.names.rules_filter", true)
                .put("index.analysis.filter.names.rules_filter_mode", mode)
                .put("index.analysis.analyzer.names.tokenizer", "standard")
                .putList("index.analysis.analyzer.names.filter", "names").build();
            try (var analyzers = createTestAnalysis(new Index("test", "_na_"), settings, new AnalysisMorphologyPlugin()).indexAnalyzers) {
                assertEquals(List.of("__surname__:эфендеева", "__surname__:раекция"),
                    analyze(analyzers.get("names"), "Эфендеева-Раекция").stream().map(Token::text).toList());
            }
        }
    }

    public void testStrictBuiltinsAreDefaultOptionalAndAdditive() {
        var settings = Settings.builder().put("rules_filter", true).put("rules_filter_mode", "strict").build();
        var builtin = RussianSurnameRecognizer.fromSettings(settings, null);
        var without = RussianSurnameRecognizer.fromSettings(Settings.builder().put(settings)
            .put("use_builtin_surnames", false).build(), null);
        assertTrue(builtin.builtinFormCount() > 150000);
        assertEquals(0, without.builtinFormCount());
        assertEquals(0, RussianSurnameRecognizer.fromSettings(Settings.builder().put("rules_filter", true).build(), null).builtinFormCount());
        for (String name : List.of("мельник", "мельником", "коваль", "ковалем", "мюллер", "мюллером")) {
            assertTrue(name, builtin.accepts(name));
        }
        for (String name : List.of("мельник", "коваль", "мюллер")) {
            assertFalse(name, without.accepts(name));
        }
        var custom = RussianSurnameRecognizer.fromSettings(Settings.builder().put(settings)
            .putList("surnames", "Квазитест").build(), null);
        assertTrue(custom.accepts("квазитестом"));
        assertTrue(custom.accepts("мельником"));
        assertFalse(builtin.accepts("министерство"));
    }

    public void testBothFactoriesUsePackagedNamesWithoutExternalFiles() throws Exception {
        for (String type : List.of("russian_surname", "russian_morphology_surnames")) {
            for (String mode : List.of("index", "search")) {
                var settings = Settings.builder()
                    .put("index.analysis.filter.names.type", type)
                    .put("index.analysis.filter.names.mode", mode)
                    .put("index.analysis.filter.names.rules_filter", true)
                    .put("index.analysis.filter.names.rules_filter_mode", "strict")
                    .put("index.analysis.analyzer.names.tokenizer", "standard")
                    .putList("index.analysis.analyzer.names.filter", "names").build();
                try (var analyzers = createTestAnalysis(new Index("test", "_na_"), settings, new AnalysisMorphologyPlugin()).indexAnalyzers) {
                    var tokens = analyze(analyzers.get("names"), mode.equals("index") ? "Мельником" : "мельник");
                    assertTrue(tokens.stream().anyMatch(t -> t.text().equals("__surname__:мельником")));
                }
            }
        }
    }

    public void testCaseControlsAndTrailingPositions() throws Exception {
        var index = RussianSurnameOptions.fromSettings(Settings.builder().put("rules_filter", true).build(), null);
        assertFalse(index.accepts("нилову"));
        try (Analyzer analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String field) {
                var tokenizer = new WhitespaceTokenizer();
                return new TokenStreamComponents(tokenizer, new RussianSurnameTokenFilter(tokenizer, index, null));
            }
        }) {
            assertEquals(List.of(new Token("__surname__:нилов", 1, 13, 18)), analyze(analyzer, "Министерство Нилов Документами"));
            try (TokenStream stream = analyzer.tokenStream("text", "Нилов Министерство Документами")) {
                var position = stream.addAttribute(PositionIncrementAttribute.class);
                stream.reset();
                assertTrue(stream.incrementToken());
                assertFalse(stream.incrementToken());
                stream.end();
                assertEquals(2, position.getPositionIncrement());
            }
        }
    }
}
