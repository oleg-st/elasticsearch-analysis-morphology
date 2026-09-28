/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.pattern.PatternCaptureGroupTokenFilter;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.morphology.LuceneMorphology;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.Analysis;

import java.util.regex.Pattern;

public class RussianMorphologyWithSurnamesFilterFactory extends AbstractTokenFilterFactory {
    private static final Pattern INITIALS = Pattern.compile("^(?:\\p{L}\\.){1,2}(\\p{L}\\p{IsAlphabetic}+)$");
    private final LuceneMorphology morphology;
    private final RussianSurnameOptions options;
    private final RussianSurnameRules rules;
    private final CharArraySet stopwords;
    private final boolean captureInitials;

    public RussianMorphologyWithSurnamesFilterFactory(IndexSettings indexSettings, Environment environment, String name,
                                                     Settings settings, LuceneMorphology morphology) {
        super(name, settings);
        this.morphology = morphology;
        options = RussianSurnameOptions.fromSettings(settings, environment);
        rules = options.search() ? RussianSurnameRules.getInstance() : null;
        stopwords = CharArraySet.unmodifiableSet(CharArraySet.copy(Analysis.parseStopWords(environment, settings,
            RussianAnalyzer.getDefaultStopSet())));
        captureInitials = settings.getAsBoolean("capture_initials", true);
    }

    @Override
    public TokenStream create(TokenStream input) {
        TokenStream captured = captureInitials ? new PatternCaptureGroupTokenFilter(input, true, INITIALS) : input;
        return new RemoveDuplicatesTokenFilter(new RussianMorphologyWithSurnamesFilter(captured, morphology, options, rules, stopwords));
    }
}
