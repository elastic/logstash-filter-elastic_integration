/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

/**
 * This {@link AbstractSimpleCacheableResolver} is an abstract base implementation of {@link CacheableResolver}
 * that extends {@link AbstractSimpleResolver}.
 *
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public abstract class AbstractSimpleCacheableResolver<K,V>
        extends AbstractSimpleResolver<K,V>
        implements CacheableResolver<K,V> {

    @Override
    public CachingResolver<K, V> withCache(final ResolverCache<K, V> cache) {
        return new SimpleCachingResolver<K, V>(cache, this);
    }
}
