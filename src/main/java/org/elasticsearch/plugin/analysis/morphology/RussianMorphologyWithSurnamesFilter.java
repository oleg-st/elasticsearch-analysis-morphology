/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.morphology.LuceneMorphology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Processes both representations from the original token, without a multiplexer or a second morphology pass. */
final class RussianMorphologyWithSurnamesFilter extends TokenFilter {
    private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keyword = addAttribute(KeywordAttribute.class);
    private final PositionIncrementAttribute position = addAttribute(PositionIncrementAttribute.class);
    private final LuceneMorphology morphology;
    private final RussianSurnameOptions options;
    private final RussianSurnameRules rules;
    private final CharArraySet stopwords;
    private Iterator<String> pending = Collections.emptyIterator();
    private State savedState;
    private int skippedPositions;

    RussianMorphologyWithSurnamesFilter(TokenStream input, LuceneMorphology morphology, RussianSurnameOptions options,
                                       RussianSurnameRules rules, CharArraySet stopwords) {
        super(input);
        this.morphology = morphology;
        this.options = options;
        this.rules = rules;
        this.stopwords = stopwords;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (pending.hasNext()) {
            restoreState(savedState);
            term.setEmpty().append(pending.next());
            position.setPositionIncrement(0);
            return true;
        }
        savedState = null;
        while (input.incrementToken()) {
            boolean surnameCandidate = options.accepts(term);
            // Exactly the same lowercasing as Lucene LowerCaseFilter; ordinary terms retain their original ё/е behavior.
            CharacterUtils.toLowerCase(term.buffer(), 0, term.length());
            String word = term.toString();
            List<String> output = new ArrayList<>();
            if (!stopwords.contains(term.buffer(), 0, term.length())) {
                if (!keyword.isKeyword() && !word.isEmpty() && morphology.checkString(word)) {
                    output.addAll(morphology.getNormalForms(word));
                } else {
                    output.add(word);
                }
            }
            // Stopwords apply only to ordinary morphology: Ли and Ко can also be surnames.
            if (surnameCandidate) {
                List<String> forms = options.forms(word, rules);
                for (String form : forms) {
                    output.add(RussianSurnameTokenFilter.PREFIX + form);
                }
            }
            if (output.isEmpty()) {
                skippedPositions += position.getPositionIncrement();
                continue;
            }
            position.setPositionIncrement(position.getPositionIncrement() + skippedPositions);
            skippedPositions = 0;
            pending = output.iterator();
            String first = pending.next();
            if (pending.hasNext()) {
                savedState = captureState();
            }
            term.setEmpty().append(first);
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
