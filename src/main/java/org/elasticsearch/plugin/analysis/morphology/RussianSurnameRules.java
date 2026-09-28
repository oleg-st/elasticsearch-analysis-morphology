/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable Petrovich surname rules, shared by all filter instances. */
final class RussianSurnameRules {
    enum Gender { MALE, FEMALE, ANDROGYNOUS }

    private static final int MAX_CANDIDATES = 256;
    private final List<Rule> exceptions;
    private final List<Rule> suffixes;
    private final Map<String, List<ReverseRule>> reverse;
    private final int maxReverseSuffixLength;

    private record Rule(String suffix, Gender gender, List<String> mods, boolean firstWord) {
        boolean accepts(Gender requested, boolean first) {
            return (gender == requested || gender == Gender.ANDROGYNOUS) && (!firstWord || first);
        }

        String apply(String word, int grammaticalCase) {
            if (grammaticalCase == 0) {
                return word;
            }
            String mod = mods.get(grammaticalCase - 1);
            if (mod.equals(".")) {
                return word;
            }
            int removed = 0;
            while (removed < mod.length() && mod.charAt(removed) == '-') {
                removed++;
            }
            return word.substring(0, word.length() - removed) + mod.substring(removed);
        }
    }

    private record ReverseRule(Rule rule, int grammaticalCase) {}
    private record Candidate(String nominative, Gender gender) {}

    private static class Holder {
        private static final RussianSurnameRules INSTANCE = load();
    }

    static RussianSurnameRules getInstance() {
        return Holder.INSTANCE;
    }

    private RussianSurnameRules(List<Rule> exceptions, List<Rule> suffixes) {
        this.exceptions = List.copyOf(exceptions);
        this.suffixes = List.copyOf(suffixes);
        Map<String, List<ReverseRule>> index = new HashMap<>();
        int longest = 0;
        List<Rule> all = new ArrayList<>(exceptions);
        all.addAll(suffixes);
        for (Rule rule : all) {
            for (int grammaticalCase = 1; grammaticalCase <= 5; grammaticalCase++) {
                String inflected = rule.apply(rule.suffix(), grammaticalCase);
                if (!inflected.equals(rule.suffix())) {
                    index.computeIfAbsent(inflected, key -> new ArrayList<>()).add(new ReverseRule(rule, grammaticalCase));
                    longest = Math.max(longest, inflected.length());
                }
            }
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        reverse = Map.copyOf(index);
        maxReverseSuffixLength = longest;
    }

    @SuppressWarnings("unchecked")
    private static RussianSurnameRules load() {
        try (InputStream stream = RussianSurnameRules.class.getResourceAsStream("/surname/petrovich-rules.json")) {
            if (stream == null) {
                throw new IOException("Missing bundled Petrovich rules");
            }
            try (XContentParser parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, stream)) {
                Map<String, Object> lastname = (Map<String, Object>) parser.map().get("lastname");
                return new RussianSurnameRules(readRules(lastname, "exceptions"), readRules(lastname, "suffixes"));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to load Russian surname rules", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Rule> readRules(Map<String, Object> lastname, String group) {
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) lastname.get(group)) {
            Gender gender = Gender.valueOf(((String) entry.get("gender")).toUpperCase(Locale.ROOT));
            List<String> mods = ((List<String>) entry.get("mods")).stream().map(RussianSurnameRules::normalize).toList();
            if (mods.size() != 5) {
                throw new IllegalArgumentException("Petrovich rules must contain five case modifiers");
            }
            List<String> tags = (List<String>) entry.getOrDefault("tags", List.of());
            if (!tags.isEmpty() && !tags.equals(List.of("first_word"))) {
                throw new IllegalArgumentException("Unsupported Petrovich rule tags: " + tags);
            }
            for (String test : (List<String>) entry.get("test")) {
                rules.add(new Rule(normalize(test), gender, List.copyOf(mods), tags.contains("first_word")));
            }
        }
        // Stable sorting retains upstream order for ties. Exceptions are consulted separately, first.
        rules.sort(Comparator.comparingInt((Rule rule) -> rule.suffix().length()).reversed());
        return rules;
    }

    static String normalize(String word) {
        return word.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /** Only complete Russian-letter tokens (or hyphenated surnames), never initials or arbitrary identifiers. */
    static boolean isSurnameToken(CharSequence word) {
        int partLength = 0;
        int parts = 1;
        for (int i = 0; i < word.length(); i++) {
            char letter = Character.toLowerCase(word.charAt(i));
            if (letter == '-') {
                if (partLength < 2 || ++parts > 4) {
                    return false;
                }
                partLength = 0;
            } else if ((letter >= 'а' && letter <= 'я') || letter == 'ё') {
                partLength++;
            } else {
                return false;
            }
        }
        return partLength >= 2;
    }

    private Rule select(String word, Gender gender, boolean firstWord) {
        for (Rule rule : exceptions) {
            if (rule.accepts(gender, firstWord) && word.equals(rule.suffix())) {
                return rule;
            }
        }
        for (Rule rule : suffixes) {
            if (rule.accepts(gender, firstWord) && word.endsWith(rule.suffix())) {
                return rule;
            }
        }
        return null;
    }

    String inflect(String nominative, Gender gender, int grammaticalCase) {
        if (grammaticalCase < 0 || grammaticalCase > 5) {
            throw new IllegalArgumentException("Case must be between 0 and 5");
        }
        String[] parts = normalize(nominative).split("-");
        for (int i = 0; i < parts.length; i++) {
            Rule rule = select(parts[i], gender, i == 0 && parts.length > 1);
            if (rule != null) {
                parts[i] = rule.apply(parts[i], grammaticalCase);
            }
        }
        return String.join("-", parts);
    }

    private List<String> nominatives(String word, Gender gender, int sourceCase, boolean firstWord) {
        Set<String> candidates = new LinkedHashSet<>();
        if (sourceCase == 0) {
            return List.of(word);
        }
        for (int length = Math.min(word.length(), maxReverseSuffixLength); length > 0; length--) {
            String ending = word.substring(word.length() - length);
            for (ReverseRule reverseRule : reverse.getOrDefault(ending, List.of())) {
                Rule rule = reverseRule.rule();
                if (reverseRule.grammaticalCase() != sourceCase || !rule.accepts(gender, firstWord)) {
                    continue;
                }
                String candidate = word.substring(0, word.length() - length) + rule.suffix();
                // Forward selection is essential: a generic inverse must not bypass an exception or a longer suffix.
                if (isSurnameToken(candidate) && select(candidate, gender, firstWord) == rule
                    && rule.apply(candidate, sourceCase).equals(word)) {
                    candidates.add(candidate);
                }
            }
        }
        Rule unchanged = select(word, gender, firstWord);
        if (unchanged == null || unchanged.apply(word, sourceCase).equals(word)) {
            candidates.add(word);
        }
        return List.copyOf(candidates);
    }

    /** Specific multi-letter rules are evidence for the optional strict recognizer. */
    Set<String> recognitionSuffixes() {
        Set<String> result = new LinkedHashSet<>();
        for (Rule rule : suffixes) {
            if (rule.suffix().length() >= 2) {
                result.add(rule.suffix());
            }
        }
        return Set.copyOf(result);
    }

    Set<String> recognitionExceptions() {
        Set<String> result = new LinkedHashSet<>();
        for (Rule rule : exceptions) {
            if (!rule.firstWord()) {
                result.add(rule.suffix());
            }
        }
        return Set.copyOf(result);
    }

    /** One inverse step and one forward step, never a transitive closure of guessed surnames. */
    List<String> expand(String token, int maxExpansions) {
        if (maxExpansions < 1) {
            throw new IllegalArgumentException("max_expansions must be positive");
        }
        String word = normalize(token);
        Set<String> forms = new LinkedHashSet<>();
        forms.add(word); // Exact spelling always survives truncation.
        if (!isSurnameToken(word) || forms.size() == maxExpansions) {
            return List.copyOf(forms);
        }
        String[] parts = word.split("-");
        Set<Candidate> visited = new LinkedHashSet<>();
        // Recover oblique forms before treating the input as an additional nominative hypothesis.
        for (int step = 1; step <= 6; step++) {
            int sourceCase = step % 6;
            for (Gender gender : List.of(Gender.MALE, Gender.FEMALE)) {
                List<String> candidates = List.of("");
                for (int i = 0; i < parts.length; i++) {
                    List<String> roots = nominatives(parts[i], gender, sourceCase, i == 0 && parts.length > 1);
                    List<String> combined = new ArrayList<>();
                    for (String prefix : candidates) {
                        for (String root : roots) {
                            if (combined.size() < MAX_CANDIDATES) {
                                combined.add(prefix.isEmpty() ? root : prefix + "-" + root);
                            }
                        }
                    }
                    candidates = combined;
                }
                for (String candidate : candidates) {
                    if (!visited.add(new Candidate(candidate, gender))) {
                        continue;
                    }
                    if (visited.size() > MAX_CANDIDATES) {
                        return List.copyOf(forms);
                    }
                    for (int targetCase = 0; targetCase <= 5; targetCase++) {
                        forms.add(inflect(candidate, gender, targetCase));
                        if (forms.size() >= maxExpansions) {
                            return List.copyOf(forms);
                        }
                    }
                }
            }
        }
        return List.copyOf(forms);
    }
}
