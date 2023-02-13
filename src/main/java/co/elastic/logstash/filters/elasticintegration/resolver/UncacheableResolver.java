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
