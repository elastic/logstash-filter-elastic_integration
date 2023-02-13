package co.elastic.logstash.filters.elasticintegration.resolver;

/**
 * Implementations of {@link CacheableResolver} <em>MAY</em> be combined with
 * a {@link ResolverCache} to produce a {@link CachingResolver}.
 *
 * @see Resolver
 *
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public interface CacheableResolver<K, V> extends Resolver<K, V> {
    /**
     *
     * @param cache the {@code ResolverCache} implementation to use
     * @return a same-type resolver that resolves <em>through</em> the provided cache.
     */
    CachingResolver<K, V> withCache(ResolverCache<K, V> cache);

    /**
     * An {@link CacheableResolver.Ephemeral Ephemeral CacheableResolver} is an internal
     * safety mechanism for interacting with a type-safe <em>view</em> of a known-{@link CacheableResolver}
     * using only the bare {@link Resolver} API. It is <em>NOT</em> meant to be implemented.
     *
     * @param <K> the type of the resolvable key
     * @param <V> the type of the resolved value
     */
    @FunctionalInterface public // @api private
    interface Ephemeral<K, V> extends Resolver<K, V> {}
}
