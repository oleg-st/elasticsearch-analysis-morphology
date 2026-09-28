/* Licensed under the Apache License, Version 2.0. */
package org.elasticsearch.plugin.analysis.morphology;

import org.elasticsearch.test.ESTestCase;

import java.util.HashSet;
import java.util.List;

import static org.elasticsearch.plugin.analysis.morphology.RussianSurnameRules.Gender.FEMALE;
import static org.elasticsearch.plugin.analysis.morphology.RussianSurnameRules.Gender.MALE;

public class RussianSurnameRulesTests extends ESTestCase {
    private final RussianSurnameRules rules = RussianSurnameRules.getInstance();

    private void assertParadigm(RussianSurnameRules.Gender gender, String... forms) {
        assertEquals(6, forms.length);
        for (int grammaticalCase = 0; grammaticalCase < forms.length; grammaticalCase++) {
            assertEquals(forms[grammaticalCase], rules.inflect(forms[0], gender, grammaticalCase));
        }
        for (String query : forms) {
            List<String> expanded = rules.expand(query, 64);
            for (String document : forms) {
                assertTrue(query + " must find " + document + ": " + expanded, expanded.contains(document));
            }
            assertEquals(query, expanded.get(0));
            assertEquals(expanded.size(), new HashSet<>(expanded).size());
        }
    }

    public void testCommonMaleAndFemaleParadigmsInEveryDirection() {
        assertParadigm(MALE, "нилов", "нилова", "нилову", "нилова", "ниловым", "нилове");
        assertParadigm(MALE, "мантуров", "мантурова", "мантурову", "мантурова", "мантуровым", "мантурове");
        assertParadigm(FEMALE, "нилова", "ниловой", "ниловой", "нилову", "ниловой", "ниловой");
        assertParadigm(MALE, "пушкин", "пушкина", "пушкину", "пушкина", "пушкиным", "пушкине");
        assertParadigm(FEMALE, "пушкина", "пушкиной", "пушкиной", "пушкину", "пушкиной", "пушкиной");
        assertParadigm(MALE, "солженицын", "солженицына", "солженицыну", "солженицына", "солженицыным", "солженицыне");
        assertParadigm(MALE, "андреев", "андреева", "андрееву", "андреева", "андреевым", "андрееве");
    }

    public void testConsonantSurnamesInEveryCaseAndSearchDirection() {
        assertParadigm(MALE, "мельник", "мельника", "мельнику", "мельника", "мельником", "мельнике");
        assertParadigm(MALE, "коваль", "коваля", "ковалю", "коваля", "ковалем", "ковале");
        assertParadigm(MALE, "бондарь", "бондаря", "бондарю", "бондаря", "бондарем", "бондаре");
        assertParadigm(MALE, "мюллер", "мюллера", "мюллеру", "мюллера", "мюллером", "мюллере");
        assertParadigm(MALE, "бергер", "бергера", "бергеру", "бергера", "бергером", "бергере");
        assertParadigm(MALE, "шульц", "шульца", "шульцу", "шульца", "шульцем", "шульце");
    }

    public void testAdjectiveParadigms() {
        assertParadigm(MALE, "достоевский", "достоевского", "достоевскому", "достоевского", "достоевским", "достоевском");
        assertParadigm(FEMALE, "достоевская", "достоевской", "достоевской", "достоевскую", "достоевской", "достоевской");
        assertParadigm(MALE, "толстой", "толстого", "толстому", "толстого", "толстым", "толстом");
        assertParadigm(MALE, "белый", "белого", "белому", "белого", "белым", "белом");
        assertParadigm(MALE, "синий", "синего", "синему", "синего", "синим", "синем");
        assertParadigm(FEMALE, "синяя", "синей", "синей", "синюю", "синей", "синей");
    }

    public void testFullRulesBeyondTypicalRussianEndings() {
        assertParadigm(MALE, "цой", "цоя", "цою", "цоя", "цоем", "цое");
        assertParadigm(MALE, "дарвин", "дарвина", "дарвину", "дарвина", "дарвином", "дарвине");
        assertParadigm(MALE, "блок", "блока", "блоку", "блока", "блоком", "блоке");
        assertParadigm(MALE, "гоголь", "гоголя", "гоголю", "гоголя", "гоголем", "гоголе");
        assertParadigm(MALE, "воробей", "воробья", "воробью", "воробья", "воробьем", "воробье");
        assertParadigm(MALE, "кравец", "кравца", "кравцу", "кравца", "кравцом", "кравце");
        assertParadigm(MALE, "ким", "кима", "киму", "кима", "кимом", "киме");
        assertParadigm(MALE, "петросян", "петросяна", "петросяну", "петросяна", "петросяном", "петросяне");
        assertParadigm(FEMALE, "муха", "мухи", "мухе", "муху", "мухой", "мухе");
        assertParadigm(MALE, "берия", "берии", "берии", "берию", "берией", "берии");
    }

    public void testExceptionsAndIndeclinableNames() {
        for (String name : List.of("дюма", "шевченко", "седых", "ли", "гусь", "ремень")) {
            for (int grammaticalCase = 0; grammaticalCase <= 5; grammaticalCase++) {
                assertEquals(name, rules.inflect(name, MALE, grammaticalCase));
            }
        }
        assertFalse(rules.expand("дюма", 64).contains("дюмы"));
        assertFalse(rules.expand("дарвин", 64).contains("дарвиным"));
        assertFalse(rules.expand("дарвиным", 64).contains("дарвин"));
        assertFalse(rules.expand("цой", 64).contains("цого"));
        // Female consonant surnames are not declined.
        assertEquals("блок", rules.inflect("блок", FEMALE, 1));
    }

    public void testAmbiguityDoesNotChooseOneGender() {
        List<String> forms = rules.expand("нилову", 64);
        assertTrue(forms.containsAll(List.of("нилов", "нилова", "ниловым", "ниловой")));
        assertFalse(forms.contains("нил"));
        assertFalse(forms.contains("мантуров"));
    }

    public void testCompoundSurnamesShareCaseAndGender() {
        assertParadigm(MALE, "бонч-бруевич", "бонч-бруевича", "бонч-бруевичу", "бонч-бруевича", "бонч-бруевичем", "бонч-бруевиче");
        assertParadigm(MALE, "салтыков-щедрин", "салтыкова-щедрина", "салтыкову-щедрину", "салтыкова-щедрина",
            "салтыковым-щедриным", "салтыкове-щедрине");
        assertEquals("бонча", rules.inflect("бонч", MALE, 1));
        assertFalse(rules.expand("салтыков-щедрин", 64).contains("салтыкову-щедриным"));
    }

    public void testNormalizationLimitsAndDeterminism() {
        assertTrue(rules.expand("ФЁДОРОВУ", 64).contains("федоров"));
        assertEquals("щелок", rules.inflect("щёлок", MALE, 1));
        assertEquals(List.of("нилову"), rules.expand("Нилову", 1));
        for (int cap : List.of(2, 8, 16, 64)) {
            List<String> forms = rules.expand("достоевской", cap);
            assertTrue(forms.size() <= cap);
            assertEquals("достоевской", forms.get(0));
            assertEquals(forms, rules.expand("ДОСТОЕВСКОЙ", cap));
        }
        expectThrows(IllegalArgumentException.class, () -> rules.expand("нилов", 0));
    }

    public void testTokenEligibility() {
        for (String word : List.of("Нилов", "Ли", "Ёлкин", "Бонч-Бруевич")) {
            assertTrue(word, RussianSurnameRules.isSurnameToken(word));
        }
        for (String word : List.of("", "И", "И.И.Нилов", "Ivanov", "Иванов123", "Нилов-", "-Нилов", "Нилов--Петров")) {
            assertFalse(word, RussianSurnameRules.isSurnameToken(word));
        }
    }
}
