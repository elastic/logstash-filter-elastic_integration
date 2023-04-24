package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.elasticsearch.ingest.IngestDocument;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.BasicEventFactory;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.EventMatchers.*;
import static co.elastic.logstash.filters.elasticintegration.IngestDuplexMarshaller.*;
import static co.elastic.logstash.filters.elasticintegration.TemporalMatchers.recentCurrentTimestamp;
import static co.elastic.logstash.filters.elasticintegration.TypeMatchers.instanceOfMatching;
import static com.github.seregamorph.hamcrest.MoreMatchers.where;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class IngestDuplexMarshallerTest {

    private static final IngestDuplexMarshaller IDM = IngestDuplexMarshaller.defaultInstance();

    @Test
    void basicRoundTrip() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world", "@metadata", Map.of("this", "that","flip", "flop")));

        validateEvent(IDM.toLogstashEvent(IDM.toIngestDocument(input)), (output) -> {
            assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

            assertThat(output, includesField("message").withValue(equalTo(input.getField("message"))));
            assertThat(output, includesField("[@metadata][this]").withValue(equalTo("that")));
            assertThat(output, includesField("[@metadata][flip]").withValue(equalTo("flop")));

            assertAll("no superfluous ingestDocument metadata is injected", () -> {
                assertThat(output, allOf(
                        excludesField("[@metadata][_ingest_document][version_type]"),
                        excludesField("[@metadata][_ingest_document][routing]"),
                        excludesField("[@metadata][_ingest_document][index]"),
                        excludesField("[@metadata][_ingest_document][id]")
                ));
            });
        });
    }

    @Test
    void ingestDocToEventModifiedTimestamp() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final ZonedDateTime updatedTimestamp = ZonedDateTime.parse("2023-03-12T01:17:38.135792468Z");
        intermediate.setFieldValue(IngestDocument.INGEST_KEY + "." + INGEST_METADATA_TIMESTAMP_FIELD, updatedTimestamp);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(where(org.logstash.Timestamp::toInstant, is(equalTo(updatedTimestamp.toInstant())))));
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));
        });
    }

    @Test
    void ingestDocToEventRemovedTimestamp() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        intermediate.removeField(IngestDocument.INGEST_KEY + "." + INGEST_METADATA_TIMESTAMP_FIELD);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(where(org.logstash.Timestamp::toInstant, is(recentCurrentTimestamp()))));
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));
        });
    }

    @Test
    void ingestDocToEventModifiedMetadataVersion() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final long updatedMetadataVersion = 17L;
        intermediate.getMetadata().setVersion(updatedMetadataVersion);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(Long.toString(updatedMetadataVersion))));
            assertThat(output, includesField("[@metadata][_ingest_document][version]").withValue(equalTo(updatedMetadataVersion)));
        });
    }

    @Test
    void ingestDocToEventAdditionalMetadata() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        intermediate.getMetadata().setVersion(8191L);
        intermediate.getMetadata().setVersionType("external_gte"); // constrained
        intermediate.getMetadata().setRouting("route-66");
        intermediate.getMetadata().setId("confused");
        intermediate.getMetadata().setIndex("card");

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo("8191")));
            assertThat(output, includesField("[@metadata][_ingest_document][version]").withValue(equalTo(8191L))); // boxing??
            assertThat(output, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("card")));
            assertThat(output, includesField("[@metadata][_ingest_document][id]").withValue(equalTo("confused")));
            assertThat(output, includesField("[@metadata][_ingest_document][routing]").withValue(equalTo("route-66")));
            assertThat(output, includesField("[@metadata][_ingest_document][version_type]").withValue(equalTo("external_gte")));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedAtTimestampField() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        // intentionally String to pass-through Valuifier#convert and make validation easier
        final String atTimestampInSource = "2023-03-12T01:17:38.135792468Z";
        intermediate.setFieldValue(org.logstash.Event.TIMESTAMP, atTimestampInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

            assertThat(output, includesField(LOGSTASH_TIMESTAMP_FALLBACK).withValue(equalTo(atTimestampInSource)));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedAtVersionField() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final String atVersionInSource = "bananas";
        intermediate.setFieldValue(org.logstash.Event.VERSION, atVersionInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

            assertThat(output, includesField(LOGSTASH_VERSION_FALLBACK).withValue(equalTo(atVersionInSource)));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedAtMetadataFieldWithAcceptableShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final Map<String,Object> atMetadataInSource = Map.of("this", "that","flip", "flop");
        intermediate.setFieldValue(org.logstash.Event.METADATA, atMetadataInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

            assertThat(output, includesField("[@metadata][this]").withValue(equalTo("that")));
            assertThat(output, includesField("[@metadata][flip]").withValue(equalTo("flop")));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedAtMetadataFieldWithInvalidShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final List<String> atMetadataInSource = List.of("wrong", "incorrect");
        intermediate.setFieldValue(org.logstash.Event.METADATA, atMetadataInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
            assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));
            assertThat(output.getMetadata(), is(notNullValue())); // static typed, so non-null is enough.

            assertThat(output, includesField(LOGSTASH_METADATA_FALLBACK).withValue(equalTo(atMetadataInSource)));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithInvalidShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final Map<String,Object> atTagsInSource = Map.of("this", "that");
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, excludesField(org.logstash.Event.TAGS));
            assertThat(output, includesField("[" + LOGSTASH_TAGS_FALLBACK + "][this]").withValue(equalTo("that")));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithInvalidCoercibleShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final Set<String> atTagsInSource = Set.of("this", "that");
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, isTagged("this"));
            assertThat(output, isTagged("that"));
            assertThat(output, excludesField(LOGSTASH_TAGS_FALLBACK));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithStringShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final String atTagsInSource = "this";
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, isTagged("this"));
            assertThat(output, excludesField(LOGSTASH_TAGS_FALLBACK));
        });
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithListOfStringShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final List<String> atTagsInSource = List.of("this", "that");
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        validateEvent(IDM.toLogstashEvent(intermediate), (output) -> {
            assertThat(output, isTagged("this"));
            assertThat(output, isTagged("that"));
            assertThat(output, excludesField(LOGSTASH_TAGS_FALLBACK));

            // guarantee _should_ belong to LS core
            assertAll("providing immutable list doesn't prevent future tagging", ()->{
                output.tag("another");
                assertThat("new tag is applied", output, isTagged("another"));
                assertThat("previous tags are still present", output, both(isTagged("this")).and(isTagged("that")));
            });
        });
    }

    @Test
    void eventToIngestDoc() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world", "@metadata", Map.of("this", "that","flip", "flop")));

        final IngestDocument ingestDocument = IDM.toIngestDocument(input);

        final ZonedDateTime ingestTimestamp = getIngestDocumentTimestamp(ingestDocument);
        assertThat(ingestTimestamp, is(notNullValue()));
        assertThat(ingestTimestamp.toInstant(), is(equalTo(getEventTimestamp(input))));

        assertThat(ingestDocument.getMetadata().getVersion(), is(equalTo(3L)));
    }

    @Test
    void eventToIngestDocMissingRequiredVersion() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world", "@metadata", Map.of("this", "that","flip", "flop")));
        input.remove(org.logstash.Event.VERSION);

        final IngestDocument ingestDocument = IDM.toIngestDocument(input);

        // sensible default
        assertThat(ingestDocument.getMetadata().getVersion(), is(equalTo(1L)));
    }

    @Test
    void eventToIngestDocMissingRequiredTimestamp() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world", "@metadata", Map.of("this", "that","flip", "flop")));
        input.remove(org.logstash.Event.TIMESTAMP);

        final IngestDocument ingestDocument = IDM.toIngestDocument(input);

        final ZonedDateTime ingestTimestamp = getIngestDocumentTimestamp(ingestDocument);
        assertThat(ingestTimestamp, is(recentCurrentTimestamp()));
    }

    Instant getEventTimestamp(final Event event) {
        return ((org.logstash.Timestamp) event.getField(org.logstash.Event.TIMESTAMP)).toInstant();
    }

    ZonedDateTime getIngestDocumentTimestamp(final IngestDocument ingestDocument) {
        return ingestDocument.getFieldValue(IngestDocument.INGEST_KEY + "." + INGEST_METADATA_TIMESTAMP_FIELD, ZonedDateTime.class);
    }

    // shared helper for validating that Logstash-reserved fields are of the correct shape
    // and that Elasticsearch-reserved fields are not present.
    void validateEvent(final Event event, final Consumer<Event> eventConsumer) {
        eventConsumer.accept(event);

        assertAll("Logstash-reserved fields MUST be shaped correctly", () -> {
            // Including LS-reserved "tags" in the wrong shape is a "poison pill" that will
            // cause subsequent tagging of an event to fail with a runtime error.
            assertThat(event, anyOf(
                    excludesField("tags"),
                    includesField("tags").withValue(instanceOf(String.class)),
                    includesField("tags").withValue(instanceOfMatching(Collection.class, everyItem(any(String.class))))
            ));
        });

        // Including ES-reserved ingest Metadata fields at top-level is a "poison pill" that will cause
        // indexing the events to be rejected by ES as it creates _source directly
        // from the event source and these metadata fields are not allowed in source.
        assertAll("Elasticsearch-reserved fields MUST NOT be present", () -> {
            assertThat(event, allOf(
                    excludesField("_index"),
                    excludesField("_id"),
                    excludesField("_version"),
                    excludesField("_routing"),
                    excludesField("_version_type")
            ));
        });

        // ES-reserved metadata should be available; of these, only `version` is required (note: no underscore prefixes)
        assertAll("IngestDocument metadata fields should be available", () -> {
            assertThat(event, includesField("[@metadata][_ingest_document][version]"));
        });
    }
}