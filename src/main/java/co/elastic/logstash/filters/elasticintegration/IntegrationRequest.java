/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public interface IntegrationRequest {
    Event event();

    default void complete() {
        this.complete(UnaryOperator.identity());
    }

    default void complete(final Event replacement) {
        this.complete(original -> replacement);
    }

    default void complete(final Consumer<Event> mutator) {
        complete(event -> {
            mutator.accept(event);
            return event;
        });
    }

    void complete(UnaryOperator<Event> eventSwapper);
}
