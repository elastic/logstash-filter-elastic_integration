/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

/**
 * This sub-interface of {@link Resolver} prevents implementations from
 * also being {@link CacheableResolver}s.
 *
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public interface UncacheableResolver<K,V> extends Resolver<K,V> {
    // Intentional return-type conflict with CacheableResolver#withCache
    // prevents both interfaces from being used together
    default void withCache(ResolverCache<K, V> cache) {};
}
