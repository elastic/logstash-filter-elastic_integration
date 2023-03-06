package co.elastic.logstash.filters.elasticintegration.resolver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;


/**
 * A {@link SimpleResolverCache} is a configurable concrete implementation
 * of {@link ResolverCache} that can be provided to a {@link CacheableResolver cacheable resolver}
 * to produce a {@link CachingResolver cached resolver}.
 *
 * <p>
 *  The default {@link Configuration} has the following properties:
 *  <ul>
 *     <li>Successful lookups are cached <em>until {@link SimpleResolverCache#clear cleared}</em></li>
 *     <li>Failed lookups are <em>NOT</em> cached</li>
 *  </ul>
 * </p>
 *
 * @param <K> the type of the resolve key
 * @param <V> the type of the resolve value
 */
public class SimpleResolverCache<K, V> implements ResolverCache<K, V> {


    public record Configuration(long maxHitAgeNanos,
                                long maxMissAgeNanos) {
        public Configuration(final Duration maxHitAge, final Duration maxMissAge) {
            this(maxHitAge.toNanos(), maxMissAge.toNanos());
        }

        public static Configuration PERMANENT = new Configuration(Long.MAX_VALUE, 0L);
    }

    private final LongSupplier nanoTimeSupplier;

    private final Configuration configuration;
    
    private final String type;

    private static final Logger LOGGER = LogManager.getLogger(SimpleResolverCache.class);

    private final ConcurrentMap<K,CacheResult> persistentCache = new ConcurrentHashMap<>();
    private final SimpleMultiLock<K> loadLock = new SimpleMultiLock<>();

    public SimpleResolverCache(final String type) {
        this(type, Configuration.PERMANENT);
    }
    
    public SimpleResolverCache(final String type, final Configuration configuration) {
        this(System::nanoTime, type, configuration);
    }

    SimpleResolverCache(final LongSupplier nanoTimeSupplier,
                        final String type,
                        final Configuration configuration) {
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.type = type;
        this.configuration = configuration;
    }

    @Override
    public Optional<V> resolve(final K resolveKey,
                               final CacheableResolver.Ephemeral<K, V> cacheMissResolver,
                               final Consumer <Exception> exceptionHandler) {
        final CacheResult cacheResult = pruningFastResolveFromCache(resolveKey);
        if (Objects.nonNull(cacheResult)) {
            LOGGER.trace(() -> String.format("cached-hit(%s:fast){ %s -> %s }", type, resolveKey, cacheResult.getCachedValue()));
            return Optional.ofNullable(cacheResult.getCachedValue());
        }
        return Optional.ofNullable(persistentCache.compute(resolveKey, (rKey, existing) -> {
            if (Objects.nonNull(existing) && !existing.isExpired()) {
                LOGGER.trace(() -> String.format("cached-hit(%s:slow){ %s -> %s }", type, resolveKey, existing.getCachedValue()));
                return existing;
            }
            return loadLock.withLock(resolveKey, () -> {
                try {
                    final CacheResult retrieved = doGet(rKey, cacheMissResolver, exceptionHandler);
                    LOGGER.trace(() -> String.format("uncached-load(%s){ %s -> %s }", type, resolveKey, retrieved.getCachedValue()));
                    return configuration.maxMissAgeNanos >= 0 ? retrieved : null;
                } catch (Exception e) {
                    LOGGER.debug(() -> String.format("uncached-load-exception(%s){ %s !> %s }", type, resolveKey, e.getMessage()));
                    throw e;
                }
            });
        }).getCachedValue());
    }

    @Override
    public void flush() {
        keys().forEach(this::pruningFastResolveFromCache);
    }

    @Override
    public void clear() {
        persistentCache.clear();
    }

    @Override
    public Set<K> keys() {
        return persistentCache.keySet();
    }

    private CacheResult pruningFastResolveFromCache(final K resolveKey) {
        CacheResult cacheResult = persistentCache.get(resolveKey);
        if (Objects.nonNull(cacheResult) && cacheResult.isExpired()) {
            persistentCache.remove(resolveKey, cacheResult);
            cacheResult = null;
        }
        return cacheResult;
    }

    /**
     * @param resolveKey the key to resolve with the underlying resolver
     * @return a non-{@code null} CacheResult
     */
    private CacheResult doGet(final K resolveKey,
                              final CacheableResolver.Ephemeral<K, V> innerResolver,
                              final Consumer<Exception> exceptionHandler) {
        LOGGER.debug(() -> String.format("loading %s: `%s`", type, resolveKey));
        return innerResolver.resolve(resolveKey, exceptionHandler)
                .map(this.cacheHit(resolveKey))
                .orElseGet(this.cacheMiss(resolveKey));
    }

    private Function<V,CacheResult> cacheHit(final K resolveKey) {
        return (resolveResult) -> {
            LOGGER.debug(() -> String.format("loaded %s: `%s`", type, resolveKey));
            return new CacheHit(resolveResult);
        };
    }

    private Supplier<CacheResult> cacheMiss(final K resolveKey) {
        return () -> {
            LOGGER.debug(() -> String.format("failed to load %s: `%s`", type, resolveKey));
            return new CacheMiss();
        };
    }

    /**
     * The {@code SimpleMultiLock} is a naive multi-lock that ensures a resolve key
     * is being accessed through the lock at most once concurrently. It is implemented
     * on top of the concurrency guarantees of {@link ConcurrentHashMap#compute}.
     *
     * @param <T> the type of the resolvable key that us used for the lock
     */
    public static class SimpleMultiLock<T> {
        private final ConcurrentMap<T,Object> lockMap = new ConcurrentHashMap<>();

        public <X> X withLock(final T resolveKey, Supplier<X> lockable) {
            @SuppressWarnings("unchecked") final X result = (X) lockMap.compute(resolveKey, (k, cr) -> lockable.get());
            if (Objects.nonNull(result)) { lockMap.remove(resolveKey, result); }
            return result;
        }

        public void withLock(final T resolveKey, Runnable lockable) {
            withLock(resolveKey, () -> { lockable.run(); return null; });
        }
    }

    abstract class CacheResult {
        private final long nanoTimestamp;

        public CacheResult() {
            this.nanoTimestamp = nanoTimeSupplier.getAsLong();
        }

        abstract public boolean isHit();

        public long getAgeNanos() {
            return Math.subtractExact(nanoTimeSupplier.getAsLong(), this.nanoTimestamp);
        }

        abstract V getCachedValue();

        abstract boolean isExpired();
    }

    private class CacheHit extends CacheResult {
        private final V value;

        public CacheHit(V value) {
            this.value = value;
        }

        @Override
        V getCachedValue() {
            return this.value;
        }

        @Override
        public boolean isHit() {
            return true;
        }

        @Override
        boolean isExpired() {
            return getAgeNanos() >= configuration.maxHitAgeNanos;
        }
    }

    private class CacheMiss extends CacheResult {
        @Override
        V getCachedValue() {
            return null;
        }

        @Override
        public boolean isHit() {
            return false;
        }

        @Override
        boolean isExpired() {
            return getAgeNanos() >= configuration.maxMissAgeNanos;
        }
    }
}
