package co.elastic.logstash.filters.elasticintegration.resolver;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A {@code Resolver} is a generic transformer of values, with tools for both caching
 * and <em>preventing</em> caching.
 *
 * <p>
 *     The type-generic {@code Resolver} interface is not meant to be used directly
 *     throughout user-code. Instead, you should define your own non-generic type as
 *     a sub-interface of either {@link Resolver} or {@link UncacheableResolver}, and
 *     then provide implementations of yor own type which may also extend from the
 *     generic helper interfaces and classes in this package:
 *     <dl>
 *        <dt>{@link CacheableResolver}</dt>
 *          <dd>interface whose implementations can be combined with a {@link ResolverCache}
 *          to produce a {@link CachingResolver}. The {@link AbstractSimpleCacheableResolver}
 *          implements simplified caching details of {@link CacheableResolver} and produces
 *          a {@link SimpleCachingResolver}.</dd>
 *        <dt>{@link CachingResolver}</dt>
 *          <dd>interface whose implementations perform caching through a {@link ResolverCache}.
 *          The {@link SimpleCachingResolver} implements simplified caching details of
 *          {@link CachingResolver}.</dd>
 *     </dl>
 * </p>
 *
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public interface Resolver<K, V> {
    /**
     * Implementations are expected to emit an empty value if none can be resolved,
     * giving the provided {@code exceptionHandler} an opportunity to throw and prevent
     * an empty value from being emitted by an error condition.
     *
     * @param resolveKey the key to resolve
     * @param exceptionHandler a handler, which has the opportunity to throw
     *                         the exception to prevent an empty value from being eimitted.
     * @return an {@code Optional} describing the resolved value
     */
    Optional<V> resolve(K resolveKey, Consumer<Exception> exceptionHandler);

    /**
     * Implementations are expected to provide an empty value if none can be resolved,
     * including when encountering exceptions, unless an exception handler is provided
     * to escalate the exception.
     *
     * @apiNote this method is not meant to be overridden.
     *
     * @param resolveKey resolveKey the key to resolve
     * @return an {@code Optional} describing the resolved value
     */
    default Optional<V> resolve(K resolveKey) {
        return this.resolve(resolveKey, (e) -> {});
    }
}
