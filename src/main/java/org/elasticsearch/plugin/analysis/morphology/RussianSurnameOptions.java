/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.util.List;

record RussianSurnameOptions(boolean search, boolean uppercaseOnly, int maxExpansions, int maxTokenLength,
                             RussianSurnameRecognizer recognizer) {
    RussianSurnameOptions(boolean search, boolean uppercaseOnly, int maxExpansions, int maxTokenLength) {
        this(search, uppercaseOnly, maxExpansions, maxTokenLength, null);
    }

    RussianSurnameOptions {
        if (maxExpansions < 1 || maxExpansions > 256) {
            throw new IllegalArgumentException("russian_surname max_expansions must be between 1 and 256");
        }
        if (maxTokenLength < 2 || maxTokenLength > 256) {
            throw new IllegalArgumentException("russian_surname max_token_length must be between 2 and 256");
        }
    }

    static RussianSurnameOptions fromSettings(Settings settings, Environment environment) {
        String mode = settings.get("mode", "index");
        if (!mode.equals("index") && !mode.equals("search")) {
            throw new IllegalArgumentException("russian_surname mode must be [index] or [search]");
        }
        boolean search = mode.equals("search");
        return new RussianSurnameOptions(search, settings.getAsBoolean("uppercase_only", !search),
            settings.getAsInt("max_expansions", 64), settings.getAsInt("max_token_length", 128),
            RussianSurnameRecognizer.fromSettings(settings, environment));
    }

    boolean accepts(CharSequence term) {
        return term.length() > 0 && term.length() <= maxTokenLength
            && (!uppercaseOnly || Character.isUpperCase(term.charAt(0)))
            && RussianSurnameRules.isSurnameToken(term)
            && (recognizer == null || recognizer.accepts(term));
    }

    List<String> forms(String word, RussianSurnameRules rules) {
        if (!search) {
            return List.of(RussianSurnameRules.normalize(word));
        }
        if (recognizer == null) {
            return rules.expand(word, maxExpansions);
        }
        // Keep the existing expansion work bound; only remove clearly unsuitable outputs.
        return rules.expand(word, maxExpansions).stream().filter(recognizer::accepts).toList();
    }
}
