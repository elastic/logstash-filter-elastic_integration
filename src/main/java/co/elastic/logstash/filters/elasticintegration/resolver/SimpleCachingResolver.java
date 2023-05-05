/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * This {@link SimpleCachingResolver} is a simple concrete implementation of {@link CachingResolver}
 * that can be used with <em>any</em> {@link CacheableResolver}
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public class SimpleCachingResolver<K,V> implements CachingResolver<K,V> {

    private final ResolverCache<K,V> cache;
    private final CacheableResolver.Ephemeral<K,V> cacheMissResolver;

    public SimpleCachingResolver(final ResolverCache<K,V> cache,
                                 final CacheableResolver<K,V> cacheMissResolver) {
        this(cache, Bindable.constant(cacheMissResolver::resolve));
    }

    public SimpleCachingResolver(final ResolverCache<K, V> cache,
                                 final Bindable<K,V> bindable) {
        this.cache = cache;
        this.cacheMissResolver = bindable.withCachingResolverBinding(this);
    }

    @Override
    public Optional<V> resolve(final K resolveKey,
                               final Consumer<Exception> exceptionHandler) {
        return cache.resolve(resolveKey, cacheMissResolver, exceptionHandler);
    }

    @Override
    public CacheReloader getReloader() {
        return cache.getReloader(cacheMissResolver);
    }

    @FunctionalInterface
    public interface Bindable<K,V> {
        CacheableResolver.Ephemeral<K,V> withCachingResolverBinding(final SimpleCachingResolver<K,V> cachingResolver);

        static <KK,VV> Bindable<KK,VV> constant(final CacheableResolver.Ephemeral<KK,VV> unboundEphemeral) {
            return (ignoredBinding) -> unboundEphemeral;
        }
    }
}
