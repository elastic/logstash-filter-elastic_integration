/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.ingest.PipelineConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public List<PipelineConfiguration> parseNamedObjects(final String json) throws Exception {
        return Spec.MAPPER.readValue(json, Spec.class).get();
    }

    public PipelineConfiguration parseNamedObject(final String json) throws Exception {
        final List<PipelineConfiguration> configs = parseNamedObjects(json);
        if (configs.isEmpty()) {
            throw new IllegalStateException("Expected a single pipeline definition. Got none");
        } else if (configs.size() > 1) {
            throw new IllegalStateException("Expected a single pipeline definition. Got " + configs.size());
        }

        return configs.get(0);
    }

    public PipelineConfiguration parseConfigOnly(final String pipelineId, final String jsonEncodedConfig) {
        return new PipelineConfiguration(pipelineId, new BytesArray(jsonEncodedConfig), XContentType.JSON);
    }


    private static class Spec {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final Map<String, String> idToConfigMap = new LinkedHashMap<>();

        @JsonAnySetter
        public void setConfig(final String pipelineId, final JsonNode jsonNode) throws JsonProcessingException {
            idToConfigMap.put(pipelineId, MAPPER.writeValueAsString(jsonNode));
        }

        public List<PipelineConfiguration> get(){
            return idToConfigMap.entrySet()
                    .stream()
                    .map(e -> init(e.getKey(), e.getValue())).toList();
        }

        private static PipelineConfiguration init(final String id, final String json) {
            return new PipelineConfiguration(id, new BytesArray(json), XContentType.JSON);
        }
    }
}
