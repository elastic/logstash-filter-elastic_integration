/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.ingest.geoip.GeoIpProcessor;

import java.util.Map;

public class GeoIpProcessorFactory implements Processor.Factory {
    private final IpDatabaseProvider ipDatabaseProvider;

    public GeoIpProcessorFactory(final IpDatabaseProvider ipDatabaseProvider) {
        this.ipDatabaseProvider = ipDatabaseProvider;
    }

    @Override
    public Processor create(Map<String, Processor.Factory> processorFactories,
                            String tag,
                            String description,
                            Map<String, Object> config,
                            ProjectId projectId) throws Exception {
        return new GeoIpProcessor.Factory("geoip", this.ipDatabaseProvider)
                .create(processorFactories, tag, description, config, projectId);
    }
}
