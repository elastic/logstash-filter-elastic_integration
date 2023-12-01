/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.BasicEventFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class DatastreamEventToIndexNameResolverTest {

    private static final DatastreamEventToIndexNameResolver RESOLVER = new DatastreamEventToIndexNameResolver();

    @Test public void eventWithoutDatastream() throws Exception {
        final Event event = BasicEventFactory.INSTANCE.newEvent();

        final AtomicReference<Exception> lastException = new AtomicReference<>();

        final Optional<String> indexName = RESOLVER.resolve(event, lastException::set);
        assertThat(lastException.get(), is(nullValue()));
        assertThat(indexName, is(equalTo(Optional.empty())));
    }

    @Test public void eventWithDatastream() throws Exception {
        final Event event = datastreamConfigEvent("logs", "elastic_agent.metricbeat", "default");

        final AtomicReference<Exception> lastException = new AtomicReference<>();

        final Optional<String> indexName = RESOLVER.resolve(event, lastException::set);
        assertThat(lastException.get(), is(nullValue()));
        assertThat(indexName, is(equalTo(Optional.of("logs-elastic_agent.metricbeat-default"))));
    }

    @Test public void eventWithDatastreamMissingType() throws Exception {
        final Event event = datastreamConfigEvent(null, "elastic_agent.metricbeat", "custom");

        final AtomicReference<Exception> lastException = new AtomicReference<>();

        final Optional<String> indexName = RESOLVER.resolve(event, lastException::set);
        assertThat(lastException.get(), is(nullValue()));
        assertThat(indexName, is(equalTo(Optional.empty())));
    }

    @Test public void eventWithDatastreamMissingDataset() throws Exception {
        final Event event = datastreamConfigEvent("logs", null, "custom");

        final AtomicReference<Exception> lastException = new AtomicReference<>();

        final Optional<String> indexName = RESOLVER.resolve(event, lastException::set);
        assertThat(lastException.get(), is(nullValue()));
        assertThat(indexName, is(equalTo(Optional.empty())));
    }

    @Test public void eventWithMalformedDatastream() throws Exception {
        final Event event = datastreamConfigEvent("logs", "OVERRIDE/ME", "custom");
        event.setField("[data_stream][dataset]", List.of("one", "two")); // not a string value

        final AtomicReference<Exception> lastException = new AtomicReference<>();

        final Optional<String> indexName = RESOLVER.resolve(event, lastException::set);
        assertThat(lastException.get(), is(nullValue()));
        assertThat(indexName, is(equalTo(Optional.empty())));
    }

    @Test public void eventWithDatastreamMissingNamespace() throws Exception {
        final Event event = datastreamConfigEvent("logs", "elastic_agent.metricbeat", null);

        final AtomicReference<Exception> lastException = new AtomicReference<>();

        final Optional<String> indexName = RESOLVER.resolve(event, lastException::set);
        assertThat(lastException.get(), is(nullValue()));
        assertThat(indexName, is(equalTo(Optional.empty())));
    }


    private Event datastreamConfigEvent(final String type, final String dataset, final String namespace) {
        final Event intermediateEvent = BasicEventFactory.INSTANCE.newEvent();

        if (Objects.nonNull(type)) { intermediateEvent.setField("[data_stream][type]", type); }
        if (Objects.nonNull(dataset)) { intermediateEvent.setField("[data_stream][dataset]", dataset); }
        if (Objects.nonNull(namespace)) { intermediateEvent.setField("[data_stream][namespace]", namespace); }

        return intermediateEvent;
    }
}