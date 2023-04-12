/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

/**
 * Implementations of {@link CachingResolver} are caching {@link Resolver}s.
 *
 * @see Resolver
 *
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public interface CachingResolver<K, V> extends Resolver<K, V> {
}
