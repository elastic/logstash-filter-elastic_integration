/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;

import java.util.Optional;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.ensureValidFieldReference;
import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.safeExtractString;

public class FieldValueEventToPipelineNameResolver implements EventToPipelineNameResolver {
    private final String fieldReference;

    public FieldValueEventToPipelineNameResolver(String fieldReference) {
        this.fieldReference = ensureValidFieldReference(fieldReference, "pipeline name");
    }

    @Override
    public Optional<String> resolve(Event event, Consumer<Exception> exceptionHandler) {
        return Optional.ofNullable(safeExtractString(event, fieldReference));
    }
}
