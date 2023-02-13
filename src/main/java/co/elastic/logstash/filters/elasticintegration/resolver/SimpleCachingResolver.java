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
        this.cache = cache;
        this.cacheMissResolver = cacheMissResolver::resolve;
    }

    @Override
    public Optional<V> resolve(final K resolveKey,
                               final Consumer<Exception> exceptionHandler) {
        return cache.resolve(resolveKey, cacheMissResolver, exceptionHandler);
    }

}
