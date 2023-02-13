package co.elastic.logstash.filters.elasticintegration.resolver;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimpleResolverCacheTest {

    @Test
    void resolve() {
        final AtomicLong fakeNanosClock = new AtomicLong();
        final LongSupplier nanoTimeSupplier = fakeNanosClock::get;
        final SimpleResolverCache.Configuration cacheConfig = new SimpleResolverCache.Configuration(Duration.ofSeconds(60), Duration.ofSeconds(5));
        final SimpleResolverCache<String,String> src = new SimpleResolverCache<>(nanoTimeSupplier, "test-value", cacheConfig);

        final StringToSequencedStringTestResolver.Cacheable cacheable = new StringToSequencedStringTestResolver.Cacheable(Map.of(
                "EMPTY", () -> null,
                "EXCEPTION", () -> { throw new IllegalStateException("intentional"); }
        ));
        final CacheableResolver.Ephemeral<String,String> ephemeralCacheable = asEphermeral(cacheable);

        final AtomicReference<Exception> lastObservedException = new AtomicReference<>();
        final Consumer<Exception> exceptionObserver = lastObservedException::set;

        assertAll("cache hit retention", () -> {
            assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(1)"))));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(1L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(10).toNanos());
            assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(1)"))));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(1L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(49).toNanos());
            assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(1)"))));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(1L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(2).toNanos());
            assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(2)"))));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(2L)));
        });

        assertAll("cache miss retention", () -> {
            assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(3L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(3).toNanos());
            assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(3L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(1).toNanos());
            assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(3L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(2).toNanos());
            assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(4L)));
        });

        assertAll("intermix hits and misses", () -> {
            assertAll("baseline retained from previous", () -> {
                assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(2)"))));
                assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
                assertThat(cacheable.lastSequenceNumber(), is(equalTo(4L)));
            });

            fakeNanosClock.addAndGet(Duration.ofSeconds(2).toNanos());
            assertThat(src.resolve("NEW", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("NEW(5)"))));
            assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(2)"))));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(5L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(20).toNanos());
            assertThat(src.resolve("NEW", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("NEW(5)"))));
            assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(2)"))));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(6L))); // empty had expired

            fakeNanosClock.addAndGet(Duration.ofSeconds(100).toNanos());
            assertThat(src.resolve("NEW", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("NEW(7)"))));
            assertThat(src.resolve("EMPTY", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(src.resolve("OK", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.of("OK(9)"))));
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(9L))); // all had expired
        });

        assertAll("exception-squashing cache miss lookups", () -> {
            assertThat(src.resolve("EXCEPTION", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(lastObservedException.get(), is(instanceOf(IllegalStateException.class)));
            lastObservedException.set(null);
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(10L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(2).toNanos());
            assertThat(src.resolve("EXCEPTION", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
            assertThat(lastObservedException.get(), is(nullValue())); // cached
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(10L)));
        });

        assertAll("exception-throwing cache miss lookups", () -> {
            assertAll("baseline retained from previous", () -> {
                assertThat(src.resolve("EXCEPTION", ephemeralCacheable, exceptionObserver), is(equalTo(Optional.empty())));
                assertThat(lastObservedException.get(), is(nullValue())); // cached
                assertThat(cacheable.lastSequenceNumber(), is(equalTo(10L)));
            });

            fakeNanosClock.addAndGet(Duration.ofSeconds(10).toNanos()); // force expire

            final Consumer<Exception> throwingExceptionHandler = (e) -> { throw new WrappingRuntimeException("wrapping", e); };
            assertThrows(WrappingRuntimeException.class, () -> {
                src.resolve("EXCEPTION", ephemeralCacheable, throwingExceptionHandler);
            });
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(11L)));

            fakeNanosClock.addAndGet(Duration.ofSeconds(1).toNanos());
            assertThrows(WrappingRuntimeException.class, () -> {
                src.resolve("EXCEPTION", ephemeralCacheable, throwingExceptionHandler);
            });
            assertThat(cacheable.lastSequenceNumber(), is(equalTo(12L)));
        });

        assertAll("flush", () -> {
            assertAll("baseline retained from previous", () -> {
                src.flush();
                assertThat(src.keys(), hasSize(2)); // only hits remain
            });

            fakeNanosClock.addAndGet(Duration.ofSeconds(100).toNanos());
            src.flush();
            assertThat(src.keys(), hasSize(0));
        });
    }

    static CacheableResolver.Ephemeral<String,String> asEphermeral(final CacheableResolver<String,String> cr) {
        return cr::resolve;
    }

    final static class WrappingRuntimeException extends RuntimeException {
        public WrappingRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}