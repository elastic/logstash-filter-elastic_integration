/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.EventFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.script.Metadata;
import org.logstash.Javafier;
import org.logstash.Timestamp;
import org.logstash.plugins.BasicEventFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code IngestDuplexMarshaller} is capable of marshalling events between the internal logstash {@link Event}
 * and the external Elasticsearch {@link IngestDocument}.
 */
public class IngestDuplexMarshaller {
    private final EventFactory eventFactory;

    private final Logger logger;

    private static final Logger DEFAULT_LOGGER = LogManager.getLogger(IngestDuplexMarshaller.class);

    static final String LOGSTASH_VERSION_FALLBACK = "_@version";
    static final String LOGSTASH_TIMESTAMP_FALLBACK = "_@timestamp";
    static final String LOGSTASH_METADATA_FALLBACK = "_@metadata";
    static final String LOGSTASH_TAGS_FALLBACK = "_tags";

    static final String INGEST_METADATA_TIMESTAMP_FIELD = "timestamp";
    static final String ECS_EVENT_CREATED_FIELD = "event.created";
    static final String VERSION_ONE = "1";

    static final String INGEST_DOCUMENT = "_ingest_document";
    static final String LOGSTASH_METADATA_INGEST_DOCUMENT_METADATA = "[@metadata][" + INGEST_DOCUMENT + "]";

    private static final IngestDuplexMarshaller DEFAULT_INSTANCE = new IngestDuplexMarshaller(DEFAULT_LOGGER);

    private static final ZoneId UTC = ZoneId.of("UTC");

    private IngestDuplexMarshaller(final EventFactory eventFactory,
                                   final Logger logger) {
        this.eventFactory = eventFactory;
        this.logger = logger;
    }

    IngestDuplexMarshaller(final Logger logger) {
        this(BasicEventFactory.INSTANCE, logger);
    }

    public static IngestDuplexMarshaller defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Converts the provided Logstash {@link Event} into an Elasticsearch {@link IngestDocument},
     * ensuring that required values are present, reserved values are of the appropriate shape,
     * and field values are of types that are useful to Ingest Processors.
     *
     * @param event the event to convert
     * @return an equivalent Elasticsearch {@link IngestDocument}
     */
    public IngestDocument toIngestDocument(final Event event) {

        // The public Elasticsearch IngestDocument constructor accepts a single source-and-metadata map,
        // which it splits into two maps based keys being valid IngestDocument.Metadata properties.
        // we copy the entirety of the event's top-level fields into this.
        Map<String, Object> sourceAndMetadata = new HashMap<>(externalize(event.getData()));

        // Map Event metadata onto `@metadata` within the ingest document's source
        final Map<String, Object> eventMetadata = event.getMetadata();
        if (!eventMetadata.isEmpty()) {
            sourceAndMetadata.put(org.logstash.Event.METADATA, externalize(eventMetadata));
        }

        // IngestDocument's metadata REQUIRES a _version, so we extract it from the
        // event's @version if available or provide a sensible default
        sanitizeIngestDocumentRequiredMetadataVersion(sourceAndMetadata, event);

        // When an IngestDocument is initialized, its "ingestMetadata" is only expected to contain the
        // event's timestamp, which is copied into the event and can be either a String or a ZonedDateTime.
        final Timestamp eventTimestamp = safeTimestampFrom(event.getField(org.logstash.Event.TIMESTAMP));
        Map<String, Object> ingestMetadata = Map.of(INGEST_METADATA_TIMESTAMP_FIELD, Objects.requireNonNullElseGet(eventTimestamp, Timestamp::now).toString());

        return new IngestDocument(sourceAndMetadata, ingestMetadata);
    }

    /**
     * Externalizes an object for use in Elasticsearch Ingest Processors.
     * Intercepts maps, lists, and sets for recursive externalization.
     *
     * @implNote naively falls through to Logstash's {@link Javafier#deep},
     *           which provides identity converters for known-safe types
     *           and a selection of Jruby->Java converters whose outputs
     *           should be largely safe in Elasticsearch.
     *
     * @param internalObject an object that may need to be externalized
     * @return an object that is safe for use as a field value in an IngestDocument
     */
    private Object externalize(final @Nullable Object internalObject) {
        if (Objects.isNull(internalObject)) { return null; }

        // intercept collection types to own recursion
        if (internalObject instanceof Map<?, ?> internalMap) {
            return externalize(internalMap);
        } else if (internalObject instanceof List<?> internalList) {
            return externalize(internalList);
        } else if (internalObject instanceof Set<?> internalSet) {
            return externalize(internalSet);
        }

        // then java-ify and perform further conversions
        final Object javafiedInternalObject = Javafier.deep(internalObject);
        if (javafiedInternalObject instanceof Timestamp internalTimestamp) {
            return internalTimestamp.toString();
        } else {
            return javafiedInternalObject;
        }
    }

    /**
     * Externalizes a {@link Map} for use in Elasticsearch Ingest Processors,
     * stringifying keys and recursively externalizing values.
     *
     * @param internalMap a map that may contain Logstash-internal types
     * @return a {@code Map<String,Object>} containing values that are safe for external use
     */
    private Map<String,Object> externalize(final @Nonnull Map<?, ?> internalMap) {
        final HashMap<String,Object> externalizedMap = new HashMap<>();
        internalMap.forEach((k,v) -> {
            final String externalizedKey = Objects.toString(k);
            final Object externalizedValue = externalize(v);
            externalizedMap.put(externalizedKey, externalizedValue);
        });
        return externalizedMap;
    }

    /**
     * Externalizes a {@link List} for use in Elasticsearch Ingest Processors,
     * recursively.
     *
     * @param internalList a list that may contain Logstash-internal types
     * @return a {@code List<Object>} containing values that are safe for external use
     */
    private List<Object> externalize(final @Nonnull List<?> internalList) {
        final List<Object> externalizedList = new ArrayList<>();
        internalList.forEach((v) -> {
            final Object externalizedValue = externalize(v);
            externalizedList.add(externalizedValue);
        });
        return externalizedList;
    }

    /**
     * Externalizes a {@link Set} for use in Elasticsearch Ingest Processors,
     * recursively.
     *
     * @param internalSet a list that may contain Logstash-internal types
     * @return a {@code List<Object>} containing values that are safe for external use
     */
    private Set<Object> externalize(final @Nonnull Set<?> internalSet) {
        final Set<Object> externalizedSet = new HashSet<>();
        internalSet.forEach((v) -> {
            final Object externalizedValue = externalize(v);
            externalizedSet.add(externalizedValue);
        });
        return externalizedSet;
    }

    /**
     * Ensures the valid required long-value {@code _version} is set,
     * preferring to use or coerce an existing value, falling back to
     * the Logstash-reserved {@code @version}, or using a sensible
     * default {@code 1L}.
     *
     * @param sourceAndMetadata the map to mutate
     * @param event the event to fetch fallback values from
     */
    private void sanitizeIngestDocumentRequiredMetadataVersion(final Map<String,Object> sourceAndMetadata, final Event event) {
        Object sourceVersion = safeLongFrom(sourceAndMetadata.remove(IngestDocument.Metadata.VERSION.getFieldName()));
        if (Objects.isNull(sourceVersion)) {
            sourceVersion = safeLongFrom(event.getField(org.logstash.Event.VERSION));
        }

        sourceAndMetadata.put(IngestDocument.Metadata.VERSION.getFieldName(), Objects.requireNonNullElse(sourceVersion, 1L));
    }

    /**
     * Safely extracts a {@link Long} from a possibly-{@code null} object.
     * @param object a possibly-{@code null} value that may hold or encode a {@link Long}.
     * @return a {@link Long} value equivalent to the provided {@code object},
     *         or {@code null} if no equivalent value is available.
     */
    private Long safeLongFrom(final Object object) {
        if (Objects.isNull(object)) { return null; }

        final Object jVersionField = Javafier.deep(object);
        if (jVersionField instanceof String) { // most common
            try {
                return Long.parseLong((String) jVersionField);
            } catch (NumberFormatException nfe) {
                return null;
            }
        } else if (jVersionField instanceof Long) {
            return (Long) jVersionField;
        } else if (jVersionField instanceof Integer) {
            return ((Integer) jVersionField).longValue();
        } else {
            return null;
        }
    }

    /**
     * Converts the provided Elasticsearch {@link IngestDocument} into a Logstash {@link Event},
     * ensuring that required values are present, reserved values are of the appropriate shape,
     * and relevant metadata from the {@code IngestDocument} are available to further processing
     * in Logstash.
     *
     * @param ingestDocument the document to convert
     * @return an equivalent Logstash {@link Event}
     */
    public Event toLogstashEvent(final IngestDocument ingestDocument) {
        // the IngestDocument we get back will have modified source directly.
        Map<String, Object> eventMap = internalize(ingestDocument.getSource());

        // ensure that Logstash-reserved fields are of the expected shape
        sanitizeEventRequiredTimestamp(eventMap, ingestDocument);
        sanitizeEventRequiredVersion(eventMap, ingestDocument);
        sanitizeEventRequiredMetadata(eventMap);
        sanitizeEventOptionalTags(eventMap);

        final Event event = eventFactory.newEvent(eventMap);

        // inject the relevant normalized metadata from the IngestDocument
        event.setField(LOGSTASH_METADATA_INGEST_DOCUMENT_METADATA, normalizeIngestDocumentMetadata(ingestDocument));
        return event;
    }

    /**
     * Internalizes an object for use in a Logstash {@link Event}.
     * Intercepts maps, lists, and sets for recursive internalization.
     *
     * @implNote naively falls through to Logstash's {@link Javafier#deep},
     *           which provides identity converters for known-safe types
     *           and also includes unnecessary Jruby->Java converters
     *
     * @param externalObject an object that may need to be internalized
     * @return an object that is safe for use as a field value in an {@link Event}
     */
    private Object internalize(final @Nullable Object externalObject) {
        if (Objects.isNull(externalObject)) { return null; }

        if (externalObject instanceof Map<?, ?> externalMap) {
            return internalize(externalMap);
        } else if (externalObject instanceof List<?> externalList) {
            return internalize(externalList);
        } else if (externalObject instanceof Set<?> externalSet) {
            return internalize(externalSet);
        } else if (externalObject instanceof ZonedDateTime zonedDateTime) {
            return new Timestamp(zonedDateTime.toInstant());
        } else if (externalObject.getClass().isArray()) {
            return internalize(Arrays.asList((Object[]) externalObject));
        } else {
            // Naively fall through to Logstash's Javafier,
            // which has identity converters for known-safe types
            // and also includes unnecessary rubyish->java converters
            return Javafier.deep(externalObject);
        }
    }

    /**
     * Internalizes a {@link Map} for use in a Logstash {@link Event},
     * stringifying keys and recursively internalizing values.
     *
     * @param externalMap a map that may contain external types
     * @return a {@code Map<String,Object>} containing values that are safe for internal use
     */
    private Map<String,Object> internalize(final @Nonnull Map<?,?> externalMap) {
        final HashMap<String,Object> internalMap = new HashMap<>();
        externalMap.forEach((k,v) -> {
            final String internalizedKey = Objects.toString(k);
            final Object internalizedValue = internalize(v);
            internalMap.put(internalizedKey, internalizedValue);
        });
        return internalMap;
    }

    /**
     * Internalizes a {@link Collection} into a {@link List } for use in
     * a Logstash {@link Event}, recursively.
     *
     * @param externalCollection a list that may contain external types
     * @return a {@code List<Object>} containing values that are safe for internal use
     */
    private List<Object> internalize(final @Nonnull Collection<?> externalCollection) {
        final List<Object> internalList = new ArrayList<>();
        externalCollection.forEach((v) -> {
            final Object internalizedValue = internalize(v);
            internalList.add(internalizedValue);
        });
        return internalList;
    }

    /**
     * Normalizes the IngestDocument's various metadata into a map that can be added to an Event
     *
     * @param ingestDocument the source
     * @return a simple map containing non-{@code null} metadata
     */
    private Map<String,Object> normalizeIngestDocumentMetadata(final IngestDocument ingestDocument) {
        final Map<String,Object> collectedMetadata = new HashMap<>();
        final Metadata metadata = ingestDocument.getMetadata();

        collectedMetadata.put("index", metadata.getIndex());
        collectedMetadata.put("id", metadata.getId());
        collectedMetadata.put("version", metadata.getVersion());
        collectedMetadata.put("version_type", metadata.getVersionType());
        collectedMetadata.put("routing", metadata.getRouting());

        collectedMetadata.put("timestamp", ingestDocument.getIngestMetadata().get(INGEST_METADATA_TIMESTAMP_FIELD));

        collectedMetadata.values().removeIf(Objects::isNull);

        return collectedMetadata;
    }

    /**
     * Ensures a valid required string-encoded integer {@code @version} is set,
     * preferring to use or coerce an existing value and falling back to the version
     * from the {@code ingestDocument}'s metadata.
     * When the source contains a value that cannot be coerced, it is re-routed to {@code _@version}.
     *
     * @param eventMap the map to mutate
     * @param ingestDocument the document to fetch version information from
     */
    private void sanitizeEventRequiredVersion(final Map<String,Object> eventMap, final IngestDocument ingestDocument) {
        final Object sourceVersion = eventMap.remove(org.logstash.Event.VERSION);
        String safeVersion = null;
        if (Objects.nonNull(sourceVersion)) {
            final String stringVersion = sourceVersion.toString();
            if (stringVersion.matches("\\d+")) {
                // ok; put the coerced version back
                safeVersion = stringVersion;
            } else {
                // invalid; place ORIGINAL in fallback
                eventMap.put(LOGSTASH_VERSION_FALLBACK, sourceVersion);
            }
        }

        if (Objects.isNull(safeVersion)) {
            final long version = ingestDocument.getMetadata().getVersion();
            safeVersion = (version == 1L ? VERSION_ONE : Long.toString(version));
        }

        eventMap.put(org.logstash.Event.VERSION, safeVersion);
    }


    /**
     * Ensures a valid required {@link Timestamp} {@code @timestamp} is set,
     * preferring to use or coerce an existing value and falling through to:
     * <ol>
     *     <li>the value of ECS field with same semantic meaning `event.created`</li>
     *     <li>the value of `_ingest.timestamp`</li>
     *     <li>the IngestDocument's initialization timestamp</li>
     * </ol>
     * When the source contains a {@code @timestamp} value that cannot be coerced,
     * it is re-routed to {@code _@timestamp}.
     *
     * @param eventMap the map to mutate
     * @param ingestDocument the document to fetch timestamp information from
     */
    // extract and set the timestamp, moving a pre-existing `@timestamp` field out of the way
    private void sanitizeEventRequiredTimestamp(final Map<String,Object> eventMap, final IngestDocument ingestDocument) {
        final Object sourceTimestamp = eventMap.remove(org.logstash.Event.TIMESTAMP);

        Timestamp safeTimestamp = safeTimestampFrom(sourceTimestamp);
        if (Objects.isNull(safeTimestamp)) {
            if (Objects.nonNull(sourceTimestamp)) {
                eventMap.put(LOGSTASH_TIMESTAMP_FALLBACK, sourceTimestamp);
            }
            safeTimestamp = safeTimestampFrom(ingestDocument.getFieldValue(ECS_EVENT_CREATED_FIELD, Object.class, true));
            if (Objects.isNull(safeTimestamp)) {
                safeTimestamp = safeTimestampFrom(ingestDocument.getIngestMetadata().get(INGEST_METADATA_TIMESTAMP_FIELD));
                if (Objects.isNull(safeTimestamp)) {
                    safeTimestamp = safeTimestampFrom(ingestDocument.getMetadata().getNow());
                }
            }
        }

        eventMap.put(org.logstash.Event.TIMESTAMP, Objects.requireNonNullElseGet(safeTimestamp, Timestamp::new));
    }

    /**
     * Extracts a {@link Timestamp} from the object, or {@code null} if none can be determined.
     *
     * @param object something that represents a point-in-time
     * @return a {@link Timestamp} equivalent to the provided {@code object}, or {@code null}.
     */
    private Timestamp safeTimestampFrom(final Object object) {
        if (Objects.isNull(object)) { return null; }

        try {
            if (object instanceof String string) {
                return new Timestamp(string);
            } else if (object instanceof Timestamp timestamp) {
                return timestamp;
            } else if (object instanceof Instant instant) {
                return new Timestamp(instant);
            } else if (object instanceof ZonedDateTime zonedDateTime) {
                return new Timestamp(zonedDateTime.toInstant());
            } else {
                final Timestamp bruteForceTimestamp = new Timestamp(object.toString());
                logger.debug(() -> String.format("Successful brute-force parsing of timestamp-like object `%s` (%s) into `%s`", object, object.getClass(), bruteForceTimestamp));
                return bruteForceTimestamp;
            }
        } catch (Exception e) {
            logger.trace(() -> String.format("failed to extract a Timestamp from `%s`", object), e);
            return null;
        }
    }

    /**
     * Ensures a valid required {@link Map}{@code <String, Object>} {@code @metadata} is set,
     * preferring to use or coerce an existing value and falling back to an empty map.
     * When the source contains a value that cannot be coerced, it is re-routed to {@code _@metadata}.
     *
     * @param eventMap the map to mutate
     */
    // if the source included `@metadata` that was not a map, move it to fallback
    private void sanitizeEventRequiredMetadata(final Map<String,Object> eventMap) {
        final Object sourceMetadata = eventMap.remove(org.logstash.Event.METADATA);
        Map<String,Object> safeMetadata = null;

        if (Objects.nonNull(sourceMetadata)) {
            if ((sourceMetadata instanceof Map<?,?> sourceMetadataMap)) {
                try {
                    safeMetadata = preCheckedMap(sourceMetadataMap, String.class, Object.class);
                } catch (ClassCastException cce) {
                    logger.trace(() -> String.format("metadata could not be cast into safe shape: %s", sourceMetadata), cce);
                }
            } else {
                logger.trace(() -> String.format("metadata could not be coerced into safe shape: %s (%s)", sourceMetadata, sourceMetadata.getClass()));
            }
            if (Objects.isNull(safeMetadata)) {
                eventMap.put(LOGSTASH_METADATA_FALLBACK, sourceMetadata);
            }
        }

        eventMap.put(org.logstash.Event.METADATA, Objects.requireNonNullElseGet(safeMetadata, HashMap::new));
    }

    /**
     * Ensures that the optional {@code tags} is a coercible shape.
     * When the source contains a value that cannot be coerced, it is re-routed to {@code _tags}.
     *
     * @param eventMap the map to mutate
     */
    // if the source included top-level `tags`, replace it with a coercible
    // representation or pre-emptively move it to fallback
    private void sanitizeEventOptionalTags(final Map<String,Object> eventMap) {
        final Object sourceTags = eventMap.remove(org.logstash.Event.TAGS);

        if (Objects.nonNull(sourceTags)) {
            Object coercibleTags = null;
            if (sourceTags instanceof String) {
                coercibleTags = sourceTags;
            } else if (sourceTags instanceof List<?> sourceTagsList) {
                try {
                    coercibleTags = preCheckedList(sourceTagsList, String.class);
                } catch (ClassCastException cce) {
                    logger.trace(() -> String.format("tags field could not be coerced into safe shape: %s", sourceTags), cce);
                }
            } else {
                logger.trace(() -> String.format("tags field could not be coerced into safe shape: %s (%s)", sourceTags, sourceTags.getClass()));
            }

            if (Objects.nonNull(coercibleTags)) {
                eventMap.put(org.logstash.Event.TAGS, coercibleTags);
            } else {
                eventMap.put(LOGSTASH_TAGS_FALLBACK, sourceTags);
            }
        }
    }

    /**
     * Returns a {@link Collections#checkedMap} view of the provided map that has been pre-checked,
     * immediately throwing a {@link ClassCastException} if the provided map's contents are not valid.
     *
     * @param map the map to wrap
     * @param kClass the class of the keys
     * @param vClass the class of the values
     * @return a pre-checked type-safe view of the provided {@code map}
     * @param <K> the type of the map's keys
     * @param <V> the type of the map's values
     * @throws ClassCastException when the map's contents are invalid
     */
    @SuppressWarnings("unchecked")
    static <K,V> Map<K,V> preCheckedMap(final Map<?,?> map, final Class<K> kClass, final Class<V> vClass) throws ClassCastException {
        final Map<K,V> checkedMap = Collections.checkedMap((Map<K, V>) map, kClass, vClass);
        checkedMap.forEach((k,v) -> {
            if (Objects.nonNull(k)) { kClass.cast(k); }
            if (Objects.nonNull(v)) { vClass.cast(v); }
        });
        return checkedMap;
    }

    /**
     * Returns a {@link Collections#checkedList} view of the provided map that has been pre-checked,
     * immediately throwing a {@link ClassCastException} if the provided list's contents are not valid.
     *
     * @param list the list to wrap
     * @param vClass the class of the list's values
     * @return a pre-checked type-safe view of the provided {@code list}
     * @param <V> the type of the list's values
     * @throws ClassCastException when the list's contents are invalid
     */
    @SuppressWarnings("unchecked")
    static <V> List<V> preCheckedList(final List<?> list, final Class<V> vClass) throws ClassCastException {
        final List<V> checkedList = Collections.checkedList((List<V>) list, vClass);
        checkedList.forEach((v) -> {
            if (Objects.nonNull(v)) { vClass.cast(v); }
        });
        return checkedList;
    }
}

