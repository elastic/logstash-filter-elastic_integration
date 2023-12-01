/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.safeExtractString;

public class DatastreamEventToIndexNameResolver implements EventToIndexNameResolver {
    private static final Logger LOGGER = LogManager.getLogger();


    private static final String DATASTREAM_NAMESPACE_FIELD_REFERENCE = "[data_stream][namespace]";
    private static final String DATASTREAM_TYPE_FIELD_REFERENCE = "[data_stream][type]";
    private static final String DATASTREAM_DATASET_FIELD_REFERENCE = "[data_stream][dataset]";

    @Override
    public Optional<String> resolve(Event event, Consumer<Exception> exceptionHandler) {
        final String namespace = safeExtractString(event, DATASTREAM_NAMESPACE_FIELD_REFERENCE);
        if (Objects.isNull(namespace)) {
            LOGGER.trace(() -> String.format("datastream not resolved from event: `%s` had no value", DATASTREAM_NAMESPACE_FIELD_REFERENCE));
            return Optional.empty();
        }

        final String type = safeExtractString(event, DATASTREAM_TYPE_FIELD_REFERENCE);
        if (Objects.isNull(type)) {
            LOGGER.trace(() -> String.format("datastream not resolved from event: `%s` had no value", DATASTREAM_TYPE_FIELD_REFERENCE));
            return Optional.empty();
        }

        final String dataset = safeExtractString(event, DATASTREAM_DATASET_FIELD_REFERENCE);
        if (Objects.isNull(dataset)) {
            LOGGER.trace(() -> String.format("datastream not resolved from event: `%s` had no value", DATASTREAM_DATASET_FIELD_REFERENCE));
            return Optional.empty();
        }

        // When we add an integration, by default index template name will be `<type>-<dataset>` and index pattern is `<type>-<dataset>-*`
        // and there is no guarantee that namespace is added to index template name
        // we use `_simulate_index` API (not `_simulate`) to fetch the default pipeline
        final String composedDatastream = String.format("%s-%s-%s", type, dataset, namespace);
        LOGGER.trace(() -> String.format("datastream resolved from event: `%s`", composedDatastream));

        return Optional.of(composedDatastream);
    }
}
