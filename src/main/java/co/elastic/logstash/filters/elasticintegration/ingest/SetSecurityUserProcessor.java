/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.ingest.AbstractProcessor;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Processor;

import java.util.Map;

public class SetSecurityUserProcessor  extends AbstractProcessor {

    public static final String TYPE = "set_security_user";

    private SetSecurityUserProcessor(String tag, String description) {
        super(tag, description);
    }


    @Override
    public IngestDocument execute(IngestDocument ingestDocument) throws Exception {
        // within Logstash, the set_security_user processor is a no-op
        return ingestDocument;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public static final class Factory implements Processor.Factory {

        @Override
        public SetSecurityUserProcessor create(Map<String, Processor.Factory> registry, String processorTag,
                                               String description, Map<String, Object> config, ProjectId projectId) {
            String[] supportedConfigs = {"field", "properties"};
            for (String cfg : supportedConfigs) {
                config.remove(cfg);
            }
            return new SetSecurityUserProcessor(processorTag, description);
        }
    }
}
