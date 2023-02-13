package co.elastic.logstash.filters.elasticintegration.resolver;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A {@link ResolverCache} is a cache suitable for use with a {@link CachingResolver}.
 *
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public interface ResolverCache<K, V> {
    Optional<V> resolve(K resolveKey,
                        CacheableResolver.Ephemeral<K, V> cacheMissResolver,
                        Consumer<Exception> exceptionHandler);

    void clear();

    void flush();

    Set<K> keys();
}
