/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.logstashbridge.common.ProjectIdBridge;
import org.elasticsearch.logstashbridge.ingest.IngestDocumentBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;

import java.util.Map;
import java.util.function.BiConsumer;

public class SetSecurityUserProcessor extends ProcessorBridge.AbstractExternal {

    public static final String TYPE = "set_security_user";
    private final String tag;
    private final String description;

    private SetSecurityUserProcessor(final String tag, final String description) {
        this.tag = tag;
        this.description = description;
    }


    @Override
    public void execute(IngestDocumentBridge ingestDocumentBridge, BiConsumer<IngestDocumentBridge, Exception> biConsumer) {
        // within Logstash, the set_security_user processor is a no-op
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getTag() {
        return this.tag;
    }

    @Override
    public String getDescription() {
        return this.description;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    public static class Factory extends ProcessorBridge.Factory.AbstractExternal {

        @Override
        public ProcessorBridge create(Map<String, ProcessorBridge.Factory> registry,
                                      String processorTag,
                                      String description,
                                      Map<String, Object> config,
                                      ProjectIdBridge projectId) {
            String[] supportedConfigs = {"field", "properties"};
            for (String cfg : supportedConfigs) {
                config.remove(cfg);
            }
            return new SetSecurityUserProcessor(processorTag, description);
        }
    }
}
