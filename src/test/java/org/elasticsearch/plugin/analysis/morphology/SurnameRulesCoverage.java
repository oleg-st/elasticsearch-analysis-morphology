/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Offline coverage report using the actual production recognizer and inflection rules. */
public class SurnameRulesCoverage {
    static Settings options(String mode) {
        return Settings.builder().put("rules_filter", true).put("rules_filter_mode", mode)
            .put("use_builtin_surnames", false).build();
    }

    static HashSet<String> forms(List<String> names) {
        var result = new HashSet<String>();
        for (String name : names) {
            result.addAll(forms(name));
        }
        return result;
    }

    private static HashSet<String> forms(String name) {
        var result = new HashSet<String>();
        var rules = RussianSurnameRules.getInstance();
        for (var gender : List.of(RussianSurnameRules.Gender.MALE, RussianSurnameRules.Gender.FEMALE)) {
            for (int c = 0; c <= 5; c++) {
                result.add(rules.inflect(name, gender, c));
            }
        }
        return result;
    }

    private static Map<String, Object> evaluate(String mode, RussianSurnameRecognizer recognizer, List<String> names,
                                                HashSet<String> allForms, Path output) throws Exception {
        var missedNames = new TreeSet<String>();
        var incompleteNames = new TreeSet<String>();
        var missedForms = new TreeSet<String>();
        for (String name : names) {
            if (!accepted(recognizer, name)) {
                missedNames.add(name);
            }
            if (forms(name).stream().anyMatch(form -> !accepted(recognizer, form))) {
                incompleteNames.add(name);
            }
        }
        for (String form : allForms) {
            if (!accepted(recognizer, form)) {
                missedForms.add(form);
            }
        }
        Files.write(output.resolve(mode + "-extra-surnames.txt"), incompleteNames, StandardCharsets.UTF_8);
        Files.write(output.resolve(mode + "-missed-nominatives.txt"), missedNames, StandardCharsets.UTF_8);
        Files.write(output.resolve(mode + "-missed-forms.txt"), missedForms, StandardCharsets.UTF_8);
        var report = new LinkedHashMap<String, Object>();
        report.put("nominatives", names.size());
        report.put("accepted_nominatives", names.size() - missedNames.size());
        report.put("nominative_coverage_percent", 100.0 * (names.size() - missedNames.size()) / names.size());
        report.put("all_forms_covered_nominatives", names.size() - incompleteNames.size());
        report.put("unique_forms", allForms.size());
        report.put("accepted_unique_forms", allForms.size() - missedForms.size());
        report.put("form_coverage_percent", 100.0 * (allForms.size() - missedForms.size()) / allForms.size());
        report.put("extra_surnames_for_full_coverage", incompleteNames.size());
        report.put("extra_surnames_utf8_bytes", Files.size(output.resolve(mode + "-extra-surnames.txt")));
        return report;
    }

    private static boolean accepted(RussianSurnameRecognizer recognizer, String form) {
        if (!recognizer.accepts(form)) {
            return false;
        }
        // Account for the standard tokenizer splitting compounds into separate tokens.
        for (String part : form.split("-")) {
            if (!recognizer.accepts(part)) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        var names = Files.readAllLines(input, StandardCharsets.UTF_8);
        if (names.isEmpty()) {
            throw new IllegalArgumentException("Empty surname validation corpus");
        }
        var allForms = forms(names);
        var report = new LinkedHashMap<String, Object>();
        for (String mode : List.of("conservative", "strict")) {
            var recognizer = RussianSurnameRecognizer.fromSettings(options(mode), null);
            report.put(mode, evaluate(mode, recognizer, names, allForms, output));
        }
        var strictFull = RussianSurnameRecognizer.fromSettings(Settings.builder().put("rules_filter", true).put("rules_filter_mode", "strict").build(), null);
        report.put("strict_with_exceptions", evaluate("strict_with_exceptions", strictFull, names, allForms, output));
        report.put("strict_extra_forms_in_memory", strictFull.builtinFormCount());
        try (var json = JsonXContent.contentBuilder()) {
            json.prettyPrint().map(report);
            String text = org.elasticsearch.common.Strings.toString(json) + "\n";
            Files.writeString(output.resolve("coverage.json"), text, StandardCharsets.UTF_8);
            System.out.println(text);
        }
    }
}
