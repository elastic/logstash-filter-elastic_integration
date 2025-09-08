/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.UncacheableResolver;
import org.elasticsearch.logstashbridge.ingest.PipelineConfigurationBridge;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A {@link PipelineConfigurationResolver} is capable of resolving a pipeline name into
 * an Elasticsearch Ingest {@link PipelineConfigurationBridge}.
 */
@FunctionalInterface
public interface PipelineConfigurationResolver extends UncacheableResolver<String, PipelineConfigurationBridge> {
    @Override
    Optional<PipelineConfigurationBridge> resolve(String pipelineName, Consumer<Exception> exceptionHandler);
}
