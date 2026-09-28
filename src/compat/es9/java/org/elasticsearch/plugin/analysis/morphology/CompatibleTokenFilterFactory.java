/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;

// Select the Elasticsearch 9 constructor at compile time.
abstract class CompatibleTokenFilterFactory extends AbstractTokenFilterFactory {
    protected CompatibleTokenFilterFactory(String name, Settings settings) {
        super(name);
    }
}
