/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.util;

import co.elastic.logstash.api.Event;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.logstash.FieldReference;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class EventUtil {

    private static final Logger LOGGER = LogManager.getLogger(EventUtil.class);

    private EventUtil() {
    }

    public static String safeExtractString(final Event event, final String fieldReference) {
        return safeExtractValue(event, fieldReference, String.class);
    }

    static <T> T safeExtractValue(final Event event, final String fieldReference, final Class<T> tClass) {
        if (!event.includes(fieldReference)) {
            LOGGER.trace(() -> String.format("field `%s` not present on event", fieldReference));
            return null;
        }

        final Object fieldValue = event.getField(fieldReference);
        if (Objects.isNull(fieldValue)) {
            LOGGER.trace(() -> String.format("field `%s` contained null-value", fieldReference));
            return null;
        }
        if (!tClass.isAssignableFrom(fieldValue.getClass())) {
            LOGGER.trace(() -> String.format("field `%s` value was of type `%s` and cannot be assigned to `%s`", fieldReference, fieldValue.getClass(), tClass));
            return null;
        }

        return tClass.cast(fieldValue);
    }

    public static String ensureValidFieldReference(final String fieldReference, final String descriptor) {
        if (!FieldReference.isValid(fieldReference)) {
            throw new IllegalArgumentException(String.format("Invalid field reference for `%s`: `%s`", descriptor, fieldReference));
        }
        return fieldReference;
    }

    public static String serializeEventForLog(final Logger logger, final Event event) {
        if (logger.isTraceEnabled()) {
            return String.format("Event{%s}", eventAsMap(event));
        } else {
            return event.toString();
        }
    }

    public static Map<String,Object> eventAsMap(final Event event) {
        final Event eventClone = event.clone();
        final Map<String,Object> intermediate = new HashMap<>(eventClone.toMap());
        intermediate.put("@metadata", Map.copyOf(eventClone.getMetadata()));
        return Collections.unmodifiableMap(intermediate);
    }
}
