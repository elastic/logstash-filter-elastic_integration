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
