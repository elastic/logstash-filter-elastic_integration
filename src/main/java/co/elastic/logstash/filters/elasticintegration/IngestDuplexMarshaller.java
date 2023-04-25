/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.EventFactory;
import org.elasticsearch.ingest.IngestDocument;
import org.logstash.Javafier;
import org.logstash.plugins.BasicEventFactory;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code IngestDuplexMarshaller} is capable of marshalling events between the internal logstash {@link Event}
 * and the external Elasticsearch {@link IngestDocument}.
 */
public class IngestDuplexMarshaller {
    static final String INGEST_METADATA_TIMESTAMP_FIELD = "timestamp";
    private final EventFactory eventFactory;

    static final String LOGSTASH_VERSION_FALLBACK = "_@version";
    static final String LOGSTASH_TIMESTAMP_FALLBACK = "_@timestamp";
    static final String LOGSTASH_METADATA_FALLBACK = "_@metadata";
    static final String LOGSTASH_TAGS_FALLBACK = "_tags";

    private static final IngestDuplexMarshaller DEFAULT_INSTANCE = new IngestDuplexMarshaller(BasicEventFactory.INSTANCE);

    private static final ZoneId UTC = ZoneId.of("UTC");

    private IngestDuplexMarshaller(final EventFactory eventFactory) {
        this.eventFactory = eventFactory;
    }

    public static IngestDuplexMarshaller defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    public IngestDocument toIngestDocument(final Event event) {
        Map<String, Object> sourceAndMetadata = new HashMap<>();

        // The public Elasticsearch IngestDocument constructor accepts a single source-and-metadata map,
        // which it splits into two maps based keys being valid IngestDocument.Metadata properties.
        // we copy the entirety of the event's top-level fields into this _except_ the @timestamp and @version
        // which have special handling below
        event.getData().forEach((eventKey, eventValue) -> {
            if (eventKey.equals(org.logstash.Event.TIMESTAMP) || eventKey.equals(org.logstash.Event.VERSION)) {
                // no-op; handled outside of iteration
            } else {
                sourceAndMetadata.put(eventKey, Javafier.deep(eventValue));
            }
        });
        // Map Event metadata onto `@metadata` within source
        final Map<String, Object> eventMetadata = event.getMetadata();
        if (!eventMetadata.isEmpty()) {
            sourceAndMetadata.put(org.logstash.Event.METADATA, Javafier.deep(eventMetadata));
        }

        // IngestDocument's metadata REQUIRES a _version, so we extract it from the event's @version if available
        // or provide a sensible default
        sourceAndMetadata.putIfAbsent(IngestDocument.Metadata.VERSION.getFieldName(), ingestDocumentVersion(event));

        // When an IngestDocument is initialized, its "ingestMetadata" is only expected to contain the
        // event's timestamp, which is copied into the event
        Map<String, Object> ingestMetadata = Map.of(INGEST_METADATA_TIMESTAMP_FIELD, ingestDocumentTimestamp(event));

        return new IngestDocument(sourceAndMetadata, ingestMetadata);
    }

    private long ingestDocumentVersion(final Event event) {
        final Object eventVersionValue = event.getField(org.logstash.Event.VERSION);
        if (Objects.nonNull(eventVersionValue)) {
            final Object jVersionField = Javafier.deep(eventVersionValue);
            if (jVersionField instanceof String) { // most common
                try {
                    return Long.parseLong((String) jVersionField);
                } catch (NumberFormatException nfe) {
                    // noop
                }
            }
            if (jVersionField instanceof Long) {
                return (Long) jVersionField;
            }
            if (jVersionField instanceof Integer) {
                return (Integer) jVersionField;
            }
        }
        return 1L; // usable default
    }

    /**
     * Extract an {@link IngestDocument}-compatible timestamp from an {@link Event}.
     * Avoids {@link Event#getEventTimestamp()} because it loses millis.
     *
     * @param event the event from which to extract a {@code @timestamp}
     * @return a {@link ZonedDateTime} that is the same moment-in-time as the event's timestamp
     */
    private ZonedDateTime ingestDocumentTimestamp(final Event event) {
        final Object eventTimestampValue = event.getField(org.logstash.Event.TIMESTAMP);
        if (Objects.nonNull(eventTimestampValue)) {
            if (eventTimestampValue instanceof org.logstash.Timestamp) {
                return ZonedDateTime.ofInstant(((org.logstash.Timestamp) eventTimestampValue).toInstant(), UTC);
            }
        }
        return ZonedDateTime.now(UTC); // usable default
    }

    public Event toLogstashEvent(final IngestDocument ingestDocument) {
        // the IngestDocument we get back will have modified source directly,
        // and may have modified metadata.
        Map<String, Object> eventMap = new HashMap<>(ingestDocument.getSourceAndMetadata());

        // extract and set the version, moving a pre-existing `@version` field out of the way
        final Object sourceVersion = eventMap.put(org.logstash.Event.VERSION, eventVersion(ingestDocument));
        if (Objects.nonNull(sourceVersion)) {
            eventMap.put(LOGSTASH_VERSION_FALLBACK, sourceVersion);
        }

        // extract and set the timestamp, moving a pre-existing `@timestamp` field out of the way
        final Object sourceTimestamp = eventMap.put(org.logstash.Event.TIMESTAMP, eventTimestamp(ingestDocument));
        if (Objects.nonNull(sourceTimestamp)) {
            eventMap.put(LOGSTASH_TIMESTAMP_FALLBACK, sourceTimestamp);
        }

        // if the source included `@metadata` that was not a map, move it to fallback
        final Object sourceMetadata = eventMap.get(org.logstash.Event.METADATA);
        if (Objects.isNull(sourceMetadata)) {
            eventMap.remove(org.logstash.Event.METADATA, null);
        } else if (!(sourceMetadata instanceof Map)) {
            eventMap.remove(org.logstash.Event.METADATA, sourceMetadata);
            eventMap.put(LOGSTASH_METADATA_FALLBACK, sourceMetadata);
        }

        // if the source included top-level `tags`, replace it with a coercible
        // representation or pre-emptively move it to fallback
        final Object sourceTags = eventMap.remove(org.logstash.Event.TAGS);
        if (Objects.nonNull(sourceTags)) {
            final Object coercibleTags = likelyCoercibleTags(sourceTags);
            if (Objects.nonNull(coercibleTags)) {
                eventMap.put(org.logstash.Event.TAGS, coercibleTags);
            } else {
                eventMap.put(LOGSTASH_TAGS_FALLBACK, sourceTags);
            }
        }

        return eventFactory.newEvent(eventMap);
    }

    private static org.logstash.Timestamp eventTimestamp(final IngestDocument ingestDocument) {
        final Object o = ingestDocument.getIngestMetadata().get(INGEST_METADATA_TIMESTAMP_FIELD);
        try {
            if (Objects.nonNull(o) && o instanceof TemporalAccessor) {
                final Instant instant = Instant.from((TemporalAccessor) o);
                return new org.logstash.Timestamp(instant);
            }
        } catch (DateTimeException e) {
            // noop
        }
        return new org.logstash.Timestamp();
    }

    private static String eventVersion(final IngestDocument ingestDocument) {
        return Long.toString(ingestDocument.getMetadata().getVersion());
    }

    private static Object likelyCoercibleTags(final Object rawTagsValue) {
        // Logstash core handles Java types String and List<?>
        if (rawTagsValue instanceof List) { return rawTagsValue; }
        if (rawTagsValue instanceof String) { return rawTagsValue; }

        // Extend to support Set
        if (rawTagsValue instanceof Set) { return new ArrayList<>((Set<?>) rawTagsValue); }

        return null;
    }
}

