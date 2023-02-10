package co.elastic.logstash.filters.elasticintegration;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.ingest.PipelineConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code PipelineConfigurationFactory} is capable of creating an Elasticsearch
 * Ingest {@link PipelineConfiguration} from any of several json-encoded formats.
 */
public class PipelineConfigurationFactory {

    public static final PipelineConfigurationFactory INSTANCE = new PipelineConfigurationFactory();

    public static PipelineConfigurationFactory getInstance() {
        return INSTANCE;
    }

    private PipelineConfigurationFactory() { }

    public Collection<PipelineConfiguration> parseNamedObjects(final String json) throws Exception {
        return Spec.MAPPER.readValue(json, Spec.class).get();
    }

    public PipelineConfiguration parseNamedObject(final String json) throws Exception {
        final Collection<PipelineConfiguration> configs = parseNamedObjects(json);
        if (configs.size() > 1) { throw new IllegalStateException("empty"); }

        return configs.stream().findFirst().orElseThrow(() -> new IllegalStateException("empty"));
    }

    public PipelineConfiguration parseConfigOnly(final String pipelineId, final String jsonEncodedConfig) {
        return new PipelineConfiguration(pipelineId, new BytesArray(jsonEncodedConfig), XContentType.JSON);
    }


    private static class Spec {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final Map<String, String> idToConfigMap = new HashMap<>();

        @JsonAnySetter
        public void setConfig(final String pipelineId, final JsonNode jsonNode) throws JsonProcessingException {
            idToConfigMap.put(pipelineId, MAPPER.writeValueAsString(jsonNode));
        }

        public Collection<PipelineConfiguration> get(){
            return idToConfigMap.entrySet()
                    .stream()
                    .map(e -> init(e.getKey(), e.getValue()))
                    .collect(Collectors.toUnmodifiableSet());
        }

        private static PipelineConfiguration init(final String id, final String json) {
            return new PipelineConfiguration(id, new BytesArray(json), XContentType.JSON);
        }
    }
}
