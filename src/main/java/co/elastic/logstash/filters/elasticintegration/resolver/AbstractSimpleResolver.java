/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * This {@link AbstractSimpleResolver} is an abstract base class for {@link Resolver} implementations
 * that handles exception intercepting.
 *
 * @param <K> the type of the resolvable key
 * @param <V> the type of the resolved value
 */
public abstract class AbstractSimpleResolver<K,V> implements Resolver<K,V> {

    /**
     * Resolves the {@code key} into an {@link Optional<V>}, turning exceptions into empty
     * results unless the provided {@code exceptionHandler} throws.
     * @param resolveKey the key to resolve
     * @param exceptionHandler a handler, which has the opportunity to re-throw a caught
     *                         exception to prevent an empty value from being emitted.
     * @return an {@code Optional} describing the resolved value
     */
    @Override
    public final Optional<V> resolve(K resolveKey, Consumer<Exception> exceptionHandler) {
        try {
            return resolveSafely(resolveKey);
        } catch (Exception e) {
            exceptionHandler.accept(e);
            return Optional.empty();
        }
    }

    public abstract Optional<V> resolveSafely(K resolveKey) throws Exception;
}
