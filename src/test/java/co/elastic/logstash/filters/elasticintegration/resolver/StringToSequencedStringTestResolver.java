package co.elastic.logstash.filters.elasticintegration.resolver;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

interface StringToSequencedStringTestResolver extends Resolver<String, String> {

    class Cacheable extends AbstractSimpleCacheableResolver<String,String> implements StringToSequencedStringTestResolver {
        private final AtomicLong sequenceNumber = new AtomicLong();

        public Cacheable(Map<String, Supplier<String>> interceptors) {
            this.interceptors = Map.copyOf(interceptors);
        }

        public Cacheable() {
            this(Map.of());
        }

        long lastSequenceNumber() {
            return sequenceNumber.get();
        }

        private final Map<String, Supplier<String>> interceptors;

        @Override
        public CachingResolver<String, String> withCache(final ResolverCache<String, String> cache) {
            return new Caching(cache, this);
        }

        @Override
        public Optional<String> resolveSafely(final String resolveKey) {
            final long sequence = sequenceNumber.incrementAndGet();

            if (this.interceptors.containsKey(resolveKey)) {
                return Optional.ofNullable(this.interceptors.get(resolveKey).get());
            }

            return Optional.of(String.format("%s(%s)", resolveKey, sequence));
        }
    }

    class Caching extends SimpleCachingResolver<String,String>
            implements StringToSequencedStringTestResolver {

        public Caching(final ResolverCache<String, String> cache,
                       final Cacheable cacheMissResolver) {
            super(cache, cacheMissResolver);
        }
    }
}
