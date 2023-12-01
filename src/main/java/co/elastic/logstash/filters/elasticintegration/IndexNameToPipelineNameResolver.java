/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.CacheableResolver;
import co.elastic.logstash.filters.elasticintegration.resolver.Resolver;

import java.util.Optional;
import java.util.function.Consumer;

public interface IndexNameToPipelineNameResolver extends Resolver<String,String> {
    @Override
    Optional<String> resolve(final String indexName, final Consumer<Exception> exceptionHandler);

    @Override
    default Optional<String> resolve(final String indexName) {
        return Resolver.super.resolve(indexName);
    }

    interface Cacheable extends IndexNameToPipelineNameResolver, CacheableResolver<String,String> {}
}
