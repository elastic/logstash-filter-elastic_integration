/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


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
                    return (retrieved.isHit() || !retrieved.isExpired()) ? retrieved : null;
                } catch (Exception e) {
                    LOGGER.debug(() -> String.format("uncached-load-exception(%s){ %s !> %s }", type, resolveKey), e);
                    throw e;
                }
            });
        })).map(CacheResult::getCachedValue);
    }

    public CacheReloader getReloader(final CacheableResolver.Ephemeral<K,V> innerResolver) {
        return new Reloader(innerResolver);
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

    @Override
    public void reload(final K resolveKey,
                       final CacheableResolver.Ephemeral<K, V> resolver) {
        final CacheResult initialCacheResult = pruningFastResolveFromCache(resolveKey);
        if (Objects.isNull(initialCacheResult)) {
            LOGGER.warn(() -> String.format("reload-gone(%s) { %s } the entry disappeared from the cache", type, resolveKey));
        }

        final Exception[] exceptionHolder = new Exception[1];
        final Optional<V> resolveResult = loadLock.withLock(resolveKey, () -> {
            return resolver.resolve(resolveKey, e -> exceptionHolder[0] = e);
        });

        final Exception resolveException = exceptionHolder[0];
        if (Objects.nonNull(resolveException)) {
            LOGGER.warn(() -> {
                if (Objects.nonNull(initialCacheResult)) {
                    final String ttlRemainingDesc = Duration.ofNanos(initialCacheResult.getRemainingNanos()).truncatedTo(ChronoUnit.SECONDS).toString();
                    final String cachedResultDesc = initialCacheResult.isHit() ? "non-empty value" : "empty value";

                    return String.format("reload-failure(%s) { %s } the existing cached %s will continue to be available until it expires in ~%s",
                            type, resolveKey, cachedResultDesc, ttlRemainingDesc);
                } else {
                    return String.format("reload-failure(%s) { %s } there is no existing cached value", type, resolveKey);
                }
            }, resolveException);
            return;
        }

        persistentCache.compute(resolveKey, (k, currentCacheResult) -> {
            if (Objects.nonNull(currentCacheResult)
                    && currentCacheResult.isHit()
                    && resolveResult.isPresent()
                    && Objects.equals(resolveResult.get(), currentCacheResult.getCachedValue())) {
                LOGGER.debug(() -> String.format("reload-unchanged(%s) { %s }", type, resolveKey));
                // when unchanged, we return new cache entry containing old value
                return new CacheHit(currentCacheResult.getCachedValue());
            } else if (resolveResult.isPresent()) {
                LOGGER.info(() -> String.format("reload-modified(%s) { %s }", type, resolveKey));
                return new CacheHit(resolveResult.get());
            } else if (Objects.nonNull(currentCacheResult) && currentCacheResult.isHit()) {
                LOGGER.info(() -> String.format("reload-removed(%s) { %s }", type, resolveKey));
                return new CacheMiss();
            } else {
                // unchanged miss; return unmodified
                return currentCacheResult;
            }
        });
    }

    /**
     * Quickly retrieves a non-expired result from the cache with minimal locking
     *
     * @param resolveKey the key to resolve directly from the hit/miss cache
     * @return a possibly-{@code null} but never-expired cache result
     */
    private CacheResult pruningFastResolveFromCache(final K resolveKey) {
        CacheResult cacheResult = persistentCache.get(resolveKey);
        if (Objects.nonNull(cacheResult) && cacheResult.isExpired()) {
            if (persistentCache.remove(resolveKey, cacheResult)) {
                LOGGER.debug(() -> String.format("expired(%s) { %s }", type, resolveKey));
            }
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
            return nanoTimeSupplier.getAsLong() - this.nanoTimestamp;
        }

        abstract V getCachedValue();

        abstract long getRemainingNanos();

        boolean isExpired() {
            return getRemainingNanos() <= 0;
        }
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
        long getRemainingNanos() {
            return Math.subtractExact(configuration.maxHitAgeNanos, getAgeNanos());
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
        long getRemainingNanos() {
            return Math.subtractExact(configuration.maxMissAgeNanos, getAgeNanos());
        }
    }

    class Reloader implements CacheReloader {
        private final CacheableResolver.Ephemeral<K,V> innerResolver;

        public Reloader(CacheableResolver.Ephemeral<K, V> innerResolver) {
            this.innerResolver = innerResolver;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public void reloadOnce() {
            persistentCache.keySet().forEach(this::reloadSingleEntry);
        }

        private void reloadSingleEntry(final K resolveKey) {
            SimpleResolverCache.this.reload(resolveKey, this.innerResolver);
        }
    }
}
