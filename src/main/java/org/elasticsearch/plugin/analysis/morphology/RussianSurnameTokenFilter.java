/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** A branch filter: emits surname terms only; combine with ordinary morphology via multiplexer. */
final class RussianSurnameTokenFilter extends TokenFilter {
    static final String PREFIX = "__surname__:";
    private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute position = addAttribute(PositionIncrementAttribute.class);
    private final RussianSurnameOptions options;
    private final RussianSurnameRules rules;
    private Iterator<String> pending = Collections.emptyIterator();
    private State savedState;
    private int skippedPositions;

    RussianSurnameTokenFilter(TokenStream input, RussianSurnameOptions options, RussianSurnameRules rules) {
        super(input);
        this.options = options;
        this.rules = rules;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (pending.hasNext()) {
            restoreState(savedState);
            term.setEmpty().append(PREFIX).append(pending.next());
            position.setPositionIncrement(0);
            return true;
        }
        savedState = null;
        while (input.incrementToken()) {
            if (!options.accepts(term)) {
                skippedPositions += position.getPositionIncrement();
                continue;
            }
            String word = RussianSurnameRules.normalize(term.toString());
            position.setPositionIncrement(position.getPositionIncrement() + skippedPositions);
            skippedPositions = 0;
            List<String> forms = options.forms(word, rules);
            pending = forms.iterator();
            String first = pending.next();
            if (pending.hasNext()) {
                savedState = captureState();
            }
            term.setEmpty().append(PREFIX).append(first);
            return true;
        }
        return false;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        pending = Collections.emptyIterator();
        savedState = null;
        skippedPositions = 0;
    }

    @Override
    public void end() throws IOException {
        super.end();
        position.setPositionIncrement(position.getPositionIncrement() + skippedPositions);
        skippedPositions = 0;
    }
}
