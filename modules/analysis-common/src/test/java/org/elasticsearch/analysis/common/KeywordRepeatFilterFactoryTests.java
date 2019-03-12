package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.test.VersionUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

public class KeywordRepeatFilterFactoryTests extends ESTestCase {
    /**
     * Check that the deprecated name "keyword_repeat" issues a deprecation warning for indices created since 8.0.0
     */
    public void testDeprecationWarning() throws IOException {
        Settings settings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir())
            .put(IndexMetaData.SETTING_VERSION_CREATED, VersionUtils.randomVersionBetween(random(), Version.V_8_0_0, Version.CURRENT))
            .build();

        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        try (CommonAnalysisPlugin commonAnalysisPlugin = new CommonAnalysisPlugin()) {
            Map<String, TokenFilterFactory> tokenFilters = createTestAnalysis(idxSettings, settings, commonAnalysisPlugin).tokenFilter;
            TokenFilterFactory tokenFilterFactory = tokenFilters.get("keyword_repeat");
            Tokenizer tokenizer = new WhitespaceTokenizer();
            tokenizer.setReader(new StringReader("input"));
            assertNotNull(tokenFilterFactory.create(tokenizer));
            assertWarnings("The [keyword_repeat] token filter is deprecated and will be removed in a future version. "
                + "Please change the filter to [multiplexer] instead.");
        }
    }

    /**
     * Check that the deprecated name "keyword_repeat" does NOT isses a deprecation warning for indices created before 8.0.0
     */
    public void testNoDeprecationWarningPre8_0() throws IOException {
        Settings settings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir())
            .put(IndexMetaData.SETTING_VERSION_CREATED,
                VersionUtils.randomVersionBetween(random(), Version.V_6_0_0, Version.V_7_1_0))
            .build();

        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        try (CommonAnalysisPlugin commonAnalysisPlugin = new CommonAnalysisPlugin()) {
            Map<String, TokenFilterFactory> tokenFilters = createTestAnalysis(idxSettings, settings, commonAnalysisPlugin).tokenFilter;
            TokenFilterFactory tokenFilterFactory = tokenFilters.get("keyword_repeat");
            Tokenizer tokenizer = new WhitespaceTokenizer();
            tokenizer.setReader(new StringReader(""));
            assertNotNull(tokenFilterFactory.create(tokenizer));
        }
    }
}
