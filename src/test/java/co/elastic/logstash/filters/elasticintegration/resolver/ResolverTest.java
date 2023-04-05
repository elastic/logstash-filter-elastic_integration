/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResolverTest {
    @Test
    void testCacheableResolverTestImplementationBaseline() {
        final StringToSequencedStringTestResolver resolver = new StringToSequencedStringTestResolver.Cacheable(
                Map.of(
                        "EMPTY", () -> null,
                        "EXCEPTION", () -> { throw new IllegalStateException("intentional"); }
                )
        );

        assertAll("basic sequencing", () -> {
            assertThat(resolver.resolve("one"), is(equalTo(Optional.of("one(1)"))));
            assertThat(resolver.resolve("two"), is(equalTo(Optional.of("two(2)"))));
            assertThat(resolver.resolve("three"), is(equalTo(Optional.of("three(3)"))));
        });

        assertAll("repeated inputs", () -> {
            assertThat(resolver.resolve("one"), is(equalTo(Optional.of("one(4)"))));
            assertThat(resolver.resolve("one"), is(equalTo(Optional.of("one(5)"))));
            assertThat(resolver.resolve("three"), is(equalTo(Optional.of("three(6)"))));
            assertThat(resolver.resolve("one"), is(equalTo(Optional.of("one(7)"))));
        });

        assertAll("magic EMPTY lookup increments internal sequence and returns empty", () -> {
            assertThat("magic EMPTY returns emtpy", resolver.resolve("EMPTY"), is(equalTo(Optional.empty())));
            assertThat("sequence number reflects prior empty", resolver.resolve("non-empty"), is(equalTo(Optional.of("non-empty(9)"))));
        });

        assertAll("more sequencing", () -> {
            assertThat(resolver.resolve("hello, world"), is(equalTo(Optional.of("hello, world(10)"))));
        });

        assertAll("throwing, squashing", () -> {
            assertThat("default behaviour emits empty resolve result", resolver.resolve("EXCEPTION"), is(equalTo(Optional.empty())));

            assertThrows(WrappingRuntimeException.class, () -> resolver.resolve("EXCEPTION", (e) -> {
                throw new WrappingRuntimeException(String.format("handled resolve exception(%s)", e.getMessage()), e);
            }), "throwing handler throws");

            assertAll("non-throwing handler side-effects", () -> {
                final List<Exception> handledExceptions = new ArrayList<>();
                assertThat(resolver.resolve("EXCEPTION", handledExceptions::add), is(equalTo(Optional.empty())));
                assertThat(handledExceptions, Matchers.<Collection<Exception>>both(hasSize(1))
                        .and(hasItem(instanceOf(IllegalStateException.class))));
            });
        });

        assertAll("sequencing after exceptions", () -> {
            assertThat(resolver.resolve("hello, world"), is(equalTo(Optional.of("hello, world(14)"))));
        });
    }

    final static class WrappingRuntimeException extends RuntimeException {
        public WrappingRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
