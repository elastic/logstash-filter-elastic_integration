package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.BasicEventFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static co.elastic.logstash.filters.elasticintegration.util.EventTestUtil.eventFromMap;
import static co.elastic.logstash.filters.elasticintegration.util.EventTestUtil.mapWithNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class FieldValueEventToPipelineNameResolverTest {

    static final EventToPipelineNameResolver fieldExtractor = new FieldValueEventToPipelineNameResolver("[deeply][nested][field]");
    @Test
    void testEventIncludesFieldWithStringValue() {
        final Event event = eventFromMap(Map.of("deeply", Map.of("nested", Map.of("field", "my-pipeline-name"))));
        assertThat(fieldExtractor.resolve(event), is(equalTo(Optional.of("my-pipeline-name"))));
    }

    @Test
    void testEventIncludesFieldWithNullValue() {
        final Event event = eventFromMap(Map.of("deeply", Map.of("nested", mapWithNullValue("field"))));
        assertThat(fieldExtractor.resolve(event), is(equalTo(Optional.empty())));
    }

    @Test
    void testEventIncludesFieldWithNonStringValue() {
        final Event event = eventFromMap(Map.of("deeply", Map.of("nested", Map.of("field", 1337L))));
        assertThat(fieldExtractor.resolve(event), is(equalTo(Optional.empty())));

        final Event event2 = eventFromMap(Map.of("deeply", Map.of("nested", Map.of("field", Map.of("deeper","value")))));
        assertThat(fieldExtractor.resolve(event2), is(equalTo(Optional.empty())));
    }

    @Test
    void testEventExcludesField() {
        final Event event = eventFromMap(Map.of());
        assertThat(fieldExtractor.resolve(event), is(equalTo(Optional.empty())));
    }
}