/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.geoip.GeoIpProcessorBridge;

import java.util.Map;
import java.util.stream.Collectors;

public class GeoIpProcessorFactory implements ProcessorBridge.Factory {
    private final IpDatabaseProvider ipDatabaseProvider;

    public GeoIpProcessorFactory(final IpDatabaseProvider ipDatabaseProvider) {
        this.ipDatabaseProvider = ipDatabaseProvider;
    }

    @Override
    public ProcessorBridge create(Map<String, ProcessorBridge.Factory> processorFactories,
                            String tag,
                            String description,
                            Map<String, Object> config) throws Exception {
        return ProcessorBridge.fromInternal(
                new GeoIpProcessorBridge.Factory("geoip", this.ipDatabaseProvider.toInternal()).toInternal()
                    .create(processorFactories.entrySet()
                                .stream()
                                .collect(Collectors.toMap(Map.Entry::getKey,e -> e.getValue().toInternal())),
                        tag,
                        description,
                        config,
                        null));
    }
}
