/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.elasticsearch.logstashbridge.core.ReleasableBridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class IntegrationBatch {
    final ArrayList<Event> events;

    public IntegrationBatch(Collection<Event> events) {
        this.events = new ArrayList<>(events);
    }

    void eachRequest(Supplier<ReleasableBridge> releasableSupplier, Consumer<IntegrationRequest> consumer) {
        for (int i = 0; i < this.events.size(); i++) {
            consumer.accept(new Request(i, releasableSupplier.get()));
        }
    }

    private class Request implements IntegrationRequest {
        private final int idx;
        private final ReleasableBridge handle;

        public Request(final int idx, final ReleasableBridge releasable) {
            this.idx = idx;
            this.handle = releasable;
        }

        @Override
        public Event event() {
            return IntegrationBatch.this.events.get(idx);
        }

        @Override
        public void complete(UnaryOperator<Event> eventSwapper) {
            final Event sourceEvent = event();
            final Event resultEvent = eventSwapper.apply(sourceEvent);

            if (resultEvent != sourceEvent) {
                events.set(idx, resultEvent);
            }

            handle.close();
        }
    }
}
