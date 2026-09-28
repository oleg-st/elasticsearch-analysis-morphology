/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.analysis.AbstractIndexAnalyzerProvider;

// Select the Elasticsearch 8 constructor at compile time.
abstract class CompatibleAnalyzerProvider<T extends Analyzer> extends AbstractIndexAnalyzerProvider<T> {
    protected CompatibleAnalyzerProvider(String name, Settings settings) {
        super(name, settings);
    }
}
