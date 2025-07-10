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
import org.elasticsearch.logstashbridge.ingest.PipelineConfigurationBridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code PipelineConfigurationFactory} is capable of creating an Elasticsearch
 * Ingest {@link PipelineConfigurationBridge} from any of several json-encoded formats.
 */
public class PipelineConfigurationFactory {

    public static final PipelineConfigurationFactory INSTANCE = new PipelineConfigurationFactory();

    public static PipelineConfigurationFactory getInstance() {
        return INSTANCE;
    }

    private PipelineConfigurationFactory() { }

    public List<PipelineConfigurationBridge> parseNamedObjects(final String json) throws Exception {
        return Spec.MAPPER.readValue(json, Spec.class).get();
    }

    public PipelineConfigurationBridge parseNamedObject(final String json) throws Exception {
        final List<PipelineConfigurationBridge> configs = parseNamedObjects(json);
        if (configs.isEmpty()) {
            throw new IllegalStateException("Expected a single pipeline definition. Got none");
        } else if (configs.size() > 1) {
            throw new IllegalStateException("Expected a single pipeline definition. Got " + configs.size());
        }

        return configs.get(0);
    }

    public PipelineConfigurationBridge parseConfigOnly(final String pipelineId, final String jsonEncodedConfig) {
        return new PipelineConfigurationBridge(pipelineId, jsonEncodedConfig);
    }


    private static class Spec {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final Map<String, String> idToConfigMap = new LinkedHashMap<>();

        @JsonAnySetter
        public void setConfig(final String pipelineId, final JsonNode jsonNode) throws JsonProcessingException {
            idToConfigMap.put(pipelineId, MAPPER.writeValueAsString(jsonNode));
        }

        public List<PipelineConfigurationBridge> get(){
            return idToConfigMap.entrySet()
                    .stream()
                    .map(e -> init(e.getKey(), e.getValue())).toList();
        }

        private static PipelineConfigurationBridge init(final String id, final String json) {
            return new PipelineConfigurationBridge(id, json);
        }
    }
}
