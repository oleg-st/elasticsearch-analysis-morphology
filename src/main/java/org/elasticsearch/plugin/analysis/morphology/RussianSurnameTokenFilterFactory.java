/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;


public class RussianSurnameTokenFilterFactory extends CompatibleTokenFilterFactory {
    private final RussianSurnameOptions options;
    private final RussianSurnameRules rules;

    public RussianSurnameTokenFilterFactory(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(name, settings);
        options = RussianSurnameOptions.fromSettings(settings, environment);
        rules = options.search() ? RussianSurnameRules.getInstance() : null;
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return new RussianSurnameTokenFilter(tokenStream, options, rules);
    }
}
