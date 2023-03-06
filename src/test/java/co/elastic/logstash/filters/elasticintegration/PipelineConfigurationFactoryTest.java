package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.util.LocalPipelinesUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.ingest.PipelineConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineConfigurationFactoryTest {
    private static final Map<String,Object> EXPECTED_PIPELINE_ID_ONE_CONFIG_MAP =
            Map.of("description","my fancy description",
                    "version", 123,
                    "processors", List.of(Map.of(
                            "set", Map.of("field", "foo","value", "bar")
            )));


    private static final Map<String,Object> EXPECTED_PIPELINE_ID_TWO_CONFIG_MAP =
            Map.of("description","my mundane description",
                    "version", 456,
                    "processors", List.of(Map.of(
                            "append", Map.of("field", "foo","value", "bar")
                    )));

    @Test
    public void testParseNamedObjectWithOnePipeline() throws Exception {
        final String json = elasticsearchApiFormattedJson("one-pipeline");
        final PipelineConfiguration loaded = PipelineConfigurationFactory.getInstance().parseNamedObject(json);
        assertThat(loaded, is(notNullValue()));
        assertThat(loaded.getId(), is(equalTo("pipeline-id-one")));
        assertThat(loaded.getConfigAsMap(), is(equalTo(EXPECTED_PIPELINE_ID_ONE_CONFIG_MAP)));
    }

    @Test
    public void testParseNamedObjectsWithTwoPipelines() throws Exception {
        final String json = elasticsearchApiFormattedJson("two-pipelines");
        final IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> {
            PipelineConfigurationFactory.getInstance().parseNamedObject(json);
        });
        assertThat(illegalStateException.getMessage(), containsString("Expected a single pipeline definition. Got 2"));
    }

    @Test
    public void testParseNamedObjectsWithZeroPipelines() throws Exception {
        final String json = "{}";
        final IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> {
            PipelineConfigurationFactory.getInstance().parseNamedObject(json);
        });
        assertThat(illegalStateException.getMessage(), containsString("Expected a single pipeline definition. Got none"));
    }

    @Test
    public void testParseNamedObjectsWithOnePipeline() throws Exception {
        final String json = elasticsearchApiFormattedJson("one-pipeline");
        final List<PipelineConfiguration> loaded = PipelineConfigurationFactory.getInstance().parseNamedObjects(json);
        assertThat(loaded, is(notNullValue()));
        assertThat(loaded, hasSize(1));

        assertThat(loaded.get(0), is(notNullValue()));
        assertThat(loaded.get(0).getId(), is(equalTo("pipeline-id-one")));
        assertThat(loaded.get(0).getConfigAsMap(), is(equalTo(EXPECTED_PIPELINE_ID_ONE_CONFIG_MAP)));
    }

    @Test
    public void testParseNamedObjectWithTwoPipelines() throws Exception {
        final String json = elasticsearchApiFormattedJson("two-pipelines");
        final List<PipelineConfiguration> loaded = PipelineConfigurationFactory.getInstance().parseNamedObjects(json);
        assertThat(loaded, is(notNullValue()));
        assertThat(loaded, hasSize(2));

        assertThat(loaded.get(0), is(notNullValue()));
        assertThat(loaded.get(0).getId(), is(equalTo("pipeline-id-one")));
        assertThat(loaded.get(0).getConfigAsMap(), is(equalTo(EXPECTED_PIPELINE_ID_ONE_CONFIG_MAP)));


        assertThat(loaded.get(1), is(notNullValue()));
        assertThat(loaded.get(1).getId(), is(equalTo("pipeline-id-two")));
        assertThat(loaded.get(1).getConfigAsMap(), is(equalTo(EXPECTED_PIPELINE_ID_TWO_CONFIG_MAP)));
    }

    @Test
    public void testParseNamedObjectWithZeroPipelines() throws Exception {
        final String json = "{}";
        final List<PipelineConfiguration> loaded = PipelineConfigurationFactory.getInstance().parseNamedObjects(json);
        assertThat(loaded, is(notNullValue()));
        assertThat(loaded, hasSize(0));
    }

    @Test
    public void testParseConfigOnly() throws Exception {
        final ObjectMapper objectMapper = new ObjectMapper();
        final String json = objectMapper.writeValueAsString(EXPECTED_PIPELINE_ID_ONE_CONFIG_MAP);
        final PipelineConfiguration loaded = PipelineConfigurationFactory.getInstance().parseConfigOnly("bananas" , json);
        assertThat(loaded, is(notNullValue()));
        assertThat(loaded.getId(), is(equalTo("bananas")));
        assertThat(loaded.getConfigAsMap(), is(equalTo(EXPECTED_PIPELINE_ID_ONE_CONFIG_MAP)));
    }

    String elasticsearchApiFormattedJson(final String name) throws IOException {
        final String packageRelativeFileName = String.format("elasticsearch-api-format-pipelines/%s.json", name);
        final Optional<Path> resourcePath = LocalPipelinesUtil.getResourcePath(PipelineConfigurationFactoryTest.class, packageRelativeFileName);
        if (!resourcePath.isPresent()) {
            throw new RuntimeException(String.format("Failed to load resource `%s`", packageRelativeFileName));
        }
        return Files.readString(resourcePath.get());
    }
}