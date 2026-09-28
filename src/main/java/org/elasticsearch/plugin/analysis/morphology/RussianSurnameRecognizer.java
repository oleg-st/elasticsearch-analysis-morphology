/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.analysis.Analysis;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fast conservative/strict suffix checks; exceptions use the existing Petrovich inflection rules. */
final class RussianSurnameRecognizer {
    private record Patterns(SuffixMatcher rejected, SuffixMatcher accepted, Set<String> exceptions, Set<String> strictExceptions) {}

    private static class Holder {
        private static final Patterns PATTERNS = load();
    }

    private static class StrictSurnamesHolder {
        private static final Set<String> FORMS = loadSurnames("/surname/strict-surnames.txt");
    }

    private final Patterns patterns;
    private final Set<String> additional;
    private final boolean strict;
    private final Set<String> builtinSurnames;

    private RussianSurnameRecognizer(Set<String> additional, boolean strict, boolean useBuiltinSurnames) {
        patterns = Holder.PATTERNS;
        this.additional = Set.copyOf(additional);
        this.strict = strict;
        builtinSurnames = strict && useBuiltinSurnames ? StrictSurnamesHolder.FORMS : Set.of();
    }

    static RussianSurnameRecognizer fromSettings(Settings settings, Environment environment) {
        String mode = settings.get("rules_filter_mode", "conservative");
        if (!mode.equals("conservative") && !mode.equals("strict")) {
            throw new IllegalArgumentException("rules_filter_mode must be [conservative] or [strict]");
        }
        if (!settings.getAsBoolean("rules_filter", false)) {
            if (settings.hasValue("surnames") || settings.hasValue("surnames_path") || settings.hasValue("rules_filter_mode")
                || settings.hasValue("use_builtin_surnames")) {
                throw new IllegalArgumentException("surnames/surnames_path/rules_filter_mode/use_builtin_surnames require rules_filter: true");
            }
            return null;
        }
        List<String> entries = Analysis.getWordList(environment, settings, "surnames");
        Set<String> additional = new HashSet<>();
        if (entries != null) {
            for (String entry : entries) {
                String word = RussianSurnameRules.normalize(entry.trim());
                if (word.length() > 256 || !RussianSurnameRules.isSurnameToken(word)) {
                    throw new IllegalArgumentException("Invalid surname in surnames/surnames_path: " + entry);
                }
                addForms(additional, word);
            }
        }
        return new RussianSurnameRecognizer(additional, mode.equals("strict"), settings.getAsBoolean("use_builtin_surnames", true));
    }

    int builtinFormCount() {
        return builtinSurnames.size();
    }

    /** Handles case and yo without allocating strings on the suffix-only path. */
    boolean accepts(CharSequence word) {
        boolean compound = false;
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) == '-') {
                compound = true;
                break;
            }
        }
        if (compound) {
            if (isException(word)) {
                return true;
            }
            for (String part : word.toString().split("-", -1)) {
                if (!accepts(part)) {
                    return false;
                }
            }
            return true;
        }
        // The common path traverses a short suffix trie, with no substring allocation or inflection.
        if (strict) {
            return patterns.accepted().matches(word, true) || isException(word);
        }
        return !patterns.rejected().matches(word, false) || isException(word);
    }

    private boolean isException(CharSequence word) {
        String normalized = RussianSurnameRules.normalize(word.toString());
        return additional.contains(normalized) || builtinSurnames.contains(normalized) || patterns.exceptions().contains(normalized)
            || (strict && patterns.strictExceptions().contains(normalized));
    }

    private static void addForms(Set<String> forms, String nominative) {
        RussianSurnameRules rules = RussianSurnameRules.getInstance();
        for (var gender : List.of(RussianSurnameRules.Gender.MALE, RussianSurnameRules.Gender.FEMALE)) {
            for (int grammaticalCase = 0; grammaticalCase <= 5; grammaticalCase++) {
                String form = rules.inflect(nominative, gender, grammaticalCase);
                forms.add(form);
                // StandardTokenizer splits hyphenated names; protect their emitted components as well.
                if (form.indexOf('-') >= 0) {
                    forms.addAll(List.of(form.split("-")));
                }
            }
        }
    }

    private static Set<String> loadSurnames(String path) {
        try (InputStream names = RussianSurnameRecognizer.class.getResourceAsStream(path)) {
            if (names == null) {
                throw new IOException("Missing bundled surname exceptions: " + path);
            }
            Set<String> forms = new HashSet<>();
            try (var reader = new BufferedReader(new InputStreamReader(names, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    String word = RussianSurnameRules.normalize(line.trim());
                    if (!word.isEmpty() && !word.startsWith("#")) {
                        if (word.length() > 256 || !RussianSurnameRules.isSurnameToken(word)) {
                            throw new IOException("Invalid bundled surname exception: " + word);
                        }
                        addForms(forms, word);
                    }
                }
            }
            return Set.copyOf(forms);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load bundled surname exceptions: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Patterns load() {
        try (InputStream resource = RussianSurnameRecognizer.class.getResourceAsStream("/surname/recognition-rules.json")) {
            if (resource == null) {
                throw new IOException("Missing surname recognition rules");
            }
            Map<String, Object> config;
            try (XContentParser parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, resource)) {
                config = parser.map();
            }
            Map<String, List<String>> groups = (Map<String, List<String>>) config.get("reject_suffix_groups");
            Set<String> endings = new HashSet<>();
            groups.values().forEach(endings::addAll);
            Set<String> exceptions = loadSurnames("/surname/rare-surnames.txt");
            Map<String, Object> strictConfig;
            try (InputStream strictResource = RussianSurnameRecognizer.class.getResourceAsStream("/surname/strict-recognition-rules.json")) {
                if (strictResource == null) {
                    throw new IOException("Missing strict surname recognition rules");
                }
                try (XContentParser parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, strictResource)) {
                    strictConfig = parser.map();
                }
            }
            var rules = RussianSurnameRules.getInstance();
            Set<String> bases = new HashSet<>(rules.recognitionSuffixes());
            bases.removeAll((List<String>) strictConfig.get("excluded_rule_suffixes"));
            bases.addAll((List<String>) strictConfig.get("additional_suffixes"));
            Set<String> accepted = new HashSet<>();
            for (String base : bases) {
                addForms(accepted, base);
            }
            Set<String> strictExceptions = new HashSet<>();
            for (String name : rules.recognitionExceptions()) {
                addForms(strictExceptions, name);
            }
            for (String name : (List<String>) strictConfig.get("exceptions")) {
                addForms(strictExceptions, name);
            }
            return new Patterns(new SuffixMatcher(endings), new SuffixMatcher(accepted), Set.copyOf(exceptions),
                Set.copyOf(strictExceptions));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to load Russian surname recognition rules", e);
        }
    }

    /** Reversed trie: array lookups only, O(length of the matched ending). */
    private static final class SuffixMatcher {
        private final int[][] next;
        private final boolean[] terminal;

        SuffixMatcher(Set<String> suffixes) {
            List<int[]> nodes = new ArrayList<>();
            Set<Integer> ends = new HashSet<>();
            nodes.add(new int[32]);
            for (String suffix : suffixes) {
                int node = 0;
                for (int i = suffix.length() - 1; i >= 0; i--) {
                    int letter = suffix.charAt(i) - 'а';
                    if (letter < 0 || letter >= 32) {
                        throw new IllegalArgumentException("Non-normalized recognition suffix: " + suffix);
                    }
                    if (nodes.get(node)[letter] == 0) {
                        nodes.get(node)[letter] = nodes.size();
                        nodes.add(new int[32]);
                    }
                    node = nodes.get(node)[letter];
                }
                ends.add(node);
            }
            next = nodes.toArray(new int[0][]);
            terminal = new boolean[next.length];
            for (int end : ends) {
                terminal[end] = true;
            }
        }

        boolean matches(CharSequence word, boolean requireStem) {
            int node = 0;
            for (int i = word.length() - 1; i >= 0; i--) {
                char character = Character.toLowerCase(word.charAt(i));
                int letter = (character == 'ё' ? 'е' : character) - 'а';
                if (letter < 0 || letter >= 32 || (node = next[node][letter]) == 0) {
                    return false;
                }
                if (terminal[node] && (!requireStem || i > 0)) {
                    return true;
                }
            }
            return false;
        }
    }
}
