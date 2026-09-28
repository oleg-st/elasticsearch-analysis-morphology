/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Synthetic analysis benchmark, not a production indexing or retrieval-quality measurement. */
public class SurnameFilterBenchmark {
    private record Counts(long tokens, long surnames) {}
    private static volatile long sink;

    private static Counts analyze(Analyzer analyzer, List<String> documents) throws Exception {
        long tokens = 0;
        long surnames = 0;
        for (String document : documents) {
            try (TokenStream stream = analyzer.tokenStream("text", document)) {
                var term = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken()) {
                    tokens++;
                    if (term.length() >= RussianSurnameTokenFilter.PREFIX.length() && term.charAt(0) == '_') {
                        surnames++;
                    }
                }
                stream.end();
            }
        }
        sink = tokens + surnames;
        return new Counts(tokens, surnames);
    }

    private static String title(String name) {
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        var names = Files.readAllLines(output.resolve("nominatives.txt"), StandardCharsets.UTF_8);
        var documents = new ArrayList<String>();
        // Spread deterministic samples across the whole alphabet, including uncommon surnames.
        String[] templates = {
            "Министерство Финансов Российской Федерации направило Документ и Решение для %s. Подпись %s.",
            "Управление Образования получило Заявление, Уведомление и Разрешение. Исполнитель %s, согласовано %s.",
            "Ведомство направило Документы в Департамент. Регистрация и Финансирование завершены. Подпись %s. Ответ %s.",
            "Правительство Постановлением утвердило Положение. Ответственность за Исполнение возложена на %s. Проверил %s.",
            "ФИО %s. ФИО %s. Город Москва. Улица Садовая. Дом Первый. Письмо Получено. Ответ Подготовлен."
        };
        var rules = RussianSurnameRules.getInstance();
        for (int i = 0; i < 5000; i++) {
            String base = names.get((int) ((long) i * names.size() / 5000));
            documents.add(String.format(Locale.ROOT, templates[i % templates.length], title(base),
                title(rules.inflect(base, RussianSurnameRules.Gender.MALE, i % 6))));
        }
        var morphology = new RussianLuceneMorphology();
        var analyzers = new LinkedHashMap<String, Analyzer>();
        try {
            for (String mode : List.of("baseline", "conservative", "strict", "strict_with_exceptions")) {
                var settings = Settings.builder().put("mode", "index");
                if (!mode.equals("baseline")) {
                    settings.put(SurnameRulesCoverage.options(mode.startsWith("strict") ? "strict" : mode));
                }
                if (mode.equals("strict_with_exceptions")) {
                    settings.put("use_builtin_surnames", true);
                }
                var factory = new RussianMorphologyWithSurnamesFilterFactory(null, null, "benchmark", settings.build(), morphology);
                analyzers.put(mode, new Analyzer() {
                    @Override
                    protected TokenStreamComponents createComponents(String field) {
                        var tokenizer = new StandardTokenizer();
                        return new TokenStreamComponents(tokenizer, factory.create(tokenizer));
                    }
                });
            }
            var modes = new ArrayList<>(analyzers.keySet());
            // Interleave modes and rotate order to reduce JIT/order bias.
            for (int warmup = 0; warmup < 4; warmup++) {
                for (int offset = 0; offset < modes.size(); offset++) {
                    analyze(analyzers.get(modes.get((warmup + offset) % modes.size())), documents);
                }
            }
            var timings = new LinkedHashMap<String, long[]>();
            var counts = new LinkedHashMap<String, Counts>();
            modes.forEach(mode -> timings.put(mode, new long[7]));
            for (int iteration = 0; iteration < 7; iteration++) {
                for (int offset = 0; offset < modes.size(); offset++) {
                    String mode = modes.get((iteration + offset) % modes.size());
                    long start = System.nanoTime();
                    counts.put(mode, analyze(analyzers.get(mode), documents));
                    timings.get(mode)[iteration] = System.nanoTime() - start;
                }
            }
            var results = new LinkedHashMap<String, Object>();
            for (String mode : modes) {
                long[] samples = timings.get(mode);
                Arrays.sort(samples);
                var row = new LinkedHashMap<String, Object>();
                row.put("median_ms", samples[3] / 1_000_000.0);
                row.put("min_ms", samples[0] / 1_000_000.0);
                row.put("max_ms", samples[6] / 1_000_000.0);
                row.put("tokens", counts.get(mode).tokens());
                row.put("surname_tokens", counts.get(mode).surnames());
                row.put("surname_reduction_percent", 100.0 * (1.0 - (double) counts.get(mode).surnames() / counts.get("baseline").surnames()));
                results.put(mode, row);
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("description", "Synthetic analysis of formal-text templates plus deterministic dictionary surname samples; not production index size or precision.");
            report.put("documents", documents.size());
            report.put("warmup_rounds", 4);
            report.put("measured_rounds", 7);
            report.put("java_version", System.getProperty("java.version"));
            report.put("modes", results);
            try (var json = JsonXContent.contentBuilder()) {
                json.prettyPrint().map(report);
                String text = Strings.toString(json) + "\n";
                Files.writeString(output.resolve("benchmark.json"), text, StandardCharsets.UTF_8);
                System.out.println(text);
            }
        } finally {
            for (Analyzer analyzer : analyzers.values()) {
                analyzer.close();
            }
        }
    }
}
