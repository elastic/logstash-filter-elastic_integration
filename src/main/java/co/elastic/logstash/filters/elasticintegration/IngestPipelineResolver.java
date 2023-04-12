/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.Resolver;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * An {@code IngestPipelineResolver} is capable of resolving a pipeline name
 * into an {@link IngestPipeline}.
 */
@FunctionalInterface
public interface IngestPipelineResolver extends Resolver<String, IngestPipeline> {
    @Override
    Optional<IngestPipeline> resolve(String pipelineName, Consumer<Exception> exceptionHandler);
}