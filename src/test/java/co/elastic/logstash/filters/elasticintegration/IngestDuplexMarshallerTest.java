package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.elasticsearch.ingest.IngestDocument;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.BasicEventFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static co.elastic.logstash.filters.elasticintegration.EventMatchers.*;
import static co.elastic.logstash.filters.elasticintegration.IngestDuplexMarshaller.*;
import static co.elastic.logstash.filters.elasticintegration.TemporalMatchers.recentCurrentTimestamp;
import static com.github.seregamorph.hamcrest.MoreMatchers.where;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class IngestDuplexMarshallerTest {

    private static final IngestDuplexMarshaller IDM = IngestDuplexMarshaller.defaultInstance();

    @Test
    void basicRoundTrip() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world", "@metadata", Map.of("this", "that","flip", "flop")));

        final Event output = IDM.toLogstashEvent(IDM.toIngestDocument(input));

        assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
        assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

        assertThat(output, includesField("message").withValue(equalTo(input.getField("message"))));
        assertThat(output, includesField("[@metadata][this]").withValue(equalTo("that")));
        assertThat(output, includesField("[@metadata][flip]").withValue(equalTo("flop")));
    }

    @Test
    void ingestDocToEventModifiedTimestamp() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final ZonedDateTime updatedTimestamp = ZonedDateTime.parse("2023-03-12T01:17:38.135792468Z");
        intermediate.setFieldValue(IngestDocument.INGEST_KEY + "." + INGEST_METADATA_TIMESTAMP_FIELD, updatedTimestamp);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(where(org.logstash.Timestamp::toInstant, is(equalTo(updatedTimestamp.toInstant())))));
        assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));
    }

    @Test
    void ingestDocToEventRemovedTimestamp() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        intermediate.removeField(IngestDocument.INGEST_KEY + "." + INGEST_METADATA_TIMESTAMP_FIELD);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(where(org.logstash.Timestamp::toInstant, is(recentCurrentTimestamp()))));
        assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));
    }

    @Test
    void ingestDocToEventIncludingReservedAtTimestampField() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        // intentionally String to pass-through Valuifier#convert and make validation easier
        final String atTimestampInSource = "2023-03-12T01:17:38.135792468Z";
        intermediate.setFieldValue(org.logstash.Event.TIMESTAMP, atTimestampInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
        assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

        assertThat(output, includesField(LOGSTASH_TIMESTAMP_FALLBACK).withValue(equalTo(atTimestampInSource)));
    }

    @Test
    void ingestDocToEventIncludingReservedAtVersionField() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final String atVersionInSource = "bananas";
        intermediate.setFieldValue(org.logstash.Event.VERSION, atVersionInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
        assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

        assertThat(output, includesField(LOGSTASH_VERSION_FALLBACK).withValue(equalTo(atVersionInSource)));
    }

    @Test
    void ingestDocToEventIncludingReservedAtMetadataFieldWithAcceptableShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final Map<String,Object> atMetadataInSource = Map.of("this", "that","flip", "flop");
        intermediate.setFieldValue(org.logstash.Event.METADATA, atMetadataInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
        assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));

        assertThat(output, includesField("[@metadata][this]").withValue(equalTo("that")));
        assertThat(output, includesField("[@metadata][flip]").withValue(equalTo("flop")));
    }

    @Test
    void ingestDocToEventIncludingReservedAtMetadataFieldWithInvalidShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("@timestamp", "2023-01-17T23:19:04.765182352Z", "@version", "3", "message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final List<String> atMetadataInSource = List.of("wrong", "incorrect");
        intermediate.setFieldValue(org.logstash.Event.METADATA, atMetadataInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, includesField(org.logstash.Event.TIMESTAMP).withValue(equalTo(input.getField(org.logstash.Event.TIMESTAMP))));
        assertThat(output, includesField(org.logstash.Event.VERSION).withValue(equalTo(input.getField(org.logstash.Event.VERSION))));
        assertThat(output.getMetadata(), is(anEmptyMap()));

        assertThat(output, includesField(LOGSTASH_METADATA_FALLBACK).withValue(equalTo(atMetadataInSource)));
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithInvalidShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final Map<String,Object> atTagsInSource = Map.of("this", "that");
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, excludesField(org.logstash.Event.TAGS));
        assertThat(output, includesField("[" + LOGSTASH_TAGS_FALLBACK + "][this]").withValue(equalTo("that")));
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithInvalidCoercibleShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final Set<String> atTagsInSource = Set.of("this", "that");
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, isTagged("this"));
        assertThat(output, isTagged("that"));
        assertThat(output, excludesField(LOGSTASH_TAGS_FALLBACK));
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithStringShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final String atTagsInSource = "this";
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, isTagged("this"));
        assertThat(output, excludesField(LOGSTASH_TAGS_FALLBACK));
    }

    @Test
    void ingestDocToEventIncludingReservedTagsFieldWithListOfStringShape() {
        final Event input = BasicEventFactory.INSTANCE.newEvent(Map.of("message", "hello, world"));
        final IngestDocument intermediate = IDM.toIngestDocument(input);

        final List<String> atTagsInSource = List.of("this", "that");
        intermediate.setFieldValue(org.logstash.Event.TAGS, atTagsInSource);

        final Event output = IDM.toLogstashEvent(intermediate);

        assertThat(output, isTagged("this"));
        assertThat(output, isTagged("that"));
        assertThat(output, excludesField(LOGSTASH_TAGS_FALLBACK));

        // guarantee _should_ belong to LS core
        assertAll("providing immutable list doesn't prevent future tagging", ()->{
            output.tag("another");
            assertThat("new tag is applied", output, isTagged("another"));
            assertThat("previous tags are still present", output, both(isTagged("this")).and(isTagged("that")));
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
}