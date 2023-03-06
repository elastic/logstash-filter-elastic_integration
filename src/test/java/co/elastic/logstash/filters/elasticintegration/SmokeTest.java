package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.BasicEventFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static co.elastic.logstash.filters.elasticintegration.EventMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;

public class SmokeTest {

    @Test
    public void testSinglePipelineMutatingEvents() {

        final PluginConfiguration pluginConfiguration = PluginConfiguration.builder()
                .setPipelineName("simple-mutate")
                .setLocalPipelines(getPreparedPipelinesResourcePath("simple-mutate-pipelines"))
                .build();

        final List<Event> inputEvents = List.of(
                newEvent(Map.of("toplevel", "ok", "id", "first","required-field-to-remove","present","nested", Map.of("field-to-lowercase", "sIlLyCaSe3", "field-to-remove", "nope", "field-to-keep", "ok")), Map.of("meta", "ok")),
                newEvent(Map.of("toplevel", "ok", "id", "second", "nested", Map.of("field-to-lowercase", "sIlLyCaSe3", "field-to-remove", "nope", "field-to-keep", "ok")), Map.of("meta", "ok")),
                newEvent(Map.of("toplevel", "ok", "id", "third","required-field-to-remove","present","nested", Map.of( "field-to-remove", "nope", "field-to-keep", "ok")), Map.of("meta", "ok"))
        );

        final List<Event> matchedEvents = new ArrayList<>();

        withEventProcessor(pluginConfiguration, matchedEvents::add, (eventProcessor) -> {
            final Collection<Event> outputEvents = eventProcessor.processEvents(inputEvents);
            assertThat("event count is unchanged", outputEvents, hasSize(inputEvents.size()));

            validateEvent(outputEvents, eventWithId("first"), (firstEvent) -> {
                assertAll("untouched elements are unchanged", () -> {
                    assertThat(firstEvent, includesField("[id]").withValue(equalTo("first")));
                    assertThat(firstEvent, includesField("[toplevel]").withValue(equalTo("ok")));
                    assertThat(firstEvent, includesField("[nested][field-to-keep]").withValue(equalTo("ok")));
                });

                assertAll("pipeline effects applied", () ->{
                    assertThat(firstEvent, includesField("[my-long-field]").withValue(equalTo(10L)));
                    assertThat(firstEvent, excludesField("[required-field-to-remove]"));
                    assertThat(firstEvent, includesField("[my-long-field]").withValue(equalTo(10L)));
                    assertThat(firstEvent, includesField("[nested][my-boolean-field]").withValue(equalTo(true)));
                    assertThat(firstEvent, includesField("[nested][field-to-lowercase]").withValue(equalTo("sillycase3")));
                    assertThat(firstEvent, excludesField("[nested][field-to-remove]"));
                });

                assertThat(firstEvent, is(in(matchedEvents)));
                assertThat(firstEvent, includesField("[@metadata][target_ingest_pipeline]").withValue(equalTo("_none")));
            });

            validateEvent(outputEvents, eventWithId("second"), (secondEvent) -> {

                assertAll("failure tag and metadata injection", () -> {
                    assertThat(secondEvent, isTagged("_ingest_pipeline_failure"));
                    assertThat(secondEvent, includesField("[@metadata][_ingest_pipeline_failure][message]").withValue(containsString("field [required-field-to-remove] not present")));
                    assertThat(secondEvent, includesField("[@metadata][_ingest_pipeline_failure][pipeline]").withValue(containsString("simple-mutate")));
                    assertThat(secondEvent, includesField("[@metadata][_ingest_pipeline_failure][exception]").withValue(containsString("org.elasticsearch.ingest.IngestProcessorException")));
                });

                // ensure that the transformations from the pipeline did _not_ apply.
                assertAll("event emitted otherwise-unmodified", () -> {
                    assertThat(secondEvent, includesField("[id]").withValue(equalTo("second")));
                    assertThat(secondEvent, includesField("[toplevel]").withValue(equalTo("ok")));
                    assertThat(secondEvent, includesField("[nested][field-to-keep]").withValue(equalTo("ok")));

                    assertThat(secondEvent, excludesField("[my-long-field]"));
                    assertThat(secondEvent, excludesField("[nested][my-boolean-field]"));
                    assertThat(secondEvent, includesField("[nested][field-to-lowercase]").withValue(equalTo("sIlLyCaSe3")));
                    assertThat(secondEvent, includesField("[nested][field-to-remove]").withValue(equalTo("nope")));

                    assertThat(secondEvent, excludesField("[@metadata][target_ingest_pipeline]"));
                });

                assertThat(secondEvent, is(not(in(matchedEvents))));
            });

            validateEvent(outputEvents, eventWithId("third"), (thirdEvent) -> {
                assertThat(thirdEvent, includesField("[id]").withValue(equalTo("third")));
                assertThat(thirdEvent, includesField("[toplevel]").withValue(equalTo("ok")));

                assertAll("pipeline effects applied", () -> {
                    assertThat(thirdEvent, excludesField("[required-field-to-remove]"));

                    assertThat(thirdEvent, includesField("[my-long-field]").withValue(equalTo(10L)));
                    assertThat(thirdEvent, includesField("[nested][my-boolean-field]").withValue(equalTo(true)));
                    assertThat(thirdEvent, excludesField("[nested][field-to-lowercase]"));
                    assertThat(thirdEvent, excludesField("[nested][field-to-remove]"));
                });

                assertThat(thirdEvent, is(in(matchedEvents)));
                assertThat(thirdEvent, includesField("[@metadata][target_ingest_pipeline]").withValue(equalTo("_none")));
            });
        });
    }


    @Test void testMultiplePipelinesMutatingEvents() {
        final PluginConfiguration pluginConfiguration = PluginConfiguration.builder()
                .setPipelineField("[@metadata][ingest_pipeline]")
                .setLocalPipelines(getPreparedPipelinesResourcePath("nesting-pipelines"))
                .build();

        final List<Event> inputEvents = List.of(
                newEvent(Map.of("toplevel", "ok", "id", "outer-ignore-missing", "ignore_missing", true), Map.of("ingest_pipeline", "outer")),
                newEvent(Map.of("toplevel", "ok", "id", "explicit-none", "ignore_missing", true), Map.of("ingest_pipeline", "_none")),
                newEvent(Map.of("toplevel", "ok", "id", "implicit-none", "ignore_missing", true), Map.of("no_ingest_pipeline", "ok")),
                newEvent(Map.of("toplevel", "ok", "id", "inner-only", "ignore_missing", true), Map.of("ingest_pipeline", "inner")),
                newEvent(Map.of("toplevel", "ok", "id", "outer-no-ignore-missing", "ignore_missing", false), Map.of("ingest_pipeline", "outer")),
                newEvent(Map.of("toplevel", "ok", "id", "outer-recursive", "ignore_missing", true, "recursive", true), Map.of("ingest_pipeline", "outer"))
        );

        final List<Event> matchedEvents = new ArrayList<>();

        withEventProcessor(pluginConfiguration, matchedEvents::add, (eventProcessor -> {
            final Collection<Event> outputEvents = eventProcessor.processEvents(inputEvents);
            assertThat("event count is unchanged", outputEvents, hasSize(inputEvents.size()));

            validateEvent(outputEvents, eventWithId("outer-ignore-missing"), (event) -> {
                assertAll("outer fully handled", () -> {
                    assertThat(event, includesField("[handled-by-outer-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[handled-by-outer-done]").withValue(equalTo(true)));
                });

                assertAll("inner handled", () -> {
                    assertThat(event, includesField("[handled-by-inner]").withValue(equalTo(true)));
                });

                assertAll("event tagged", () -> {
                    assertThat(event, is(in(matchedEvents)));
                    assertThat(event, includesField("[@metadata][target_ingest_pipeline]").withValue(equalTo("_none")));
                });
            });

            validateEvent(outputEvents, eventWithId("explicit-none"), (event) -> {
                System.err.format("EXPLICIT-NONE: %s//%s\n", event.toMap(), event.getMetadata());
                assertThat(event, excludesField("[@metadata][target_ingest_pipeline]"));
            });

            validateEvent(outputEvents, eventWithId("implicit-none"), (event) -> {
                assertThat(event, excludesField("[@metadata][target_ingest_pipeline]"));
            });

            validateEvent(outputEvents, eventWithId("inner-only"), (event) -> {
                assertThat(event, includesField("[handled-by-inner]").withValue(equalTo(true)));

                assertThat(event, excludesField("[handled-by-outer-init]"));
                assertThat(event, excludesField("[handled-by-outer-done]"));
            });

            validateEvent(outputEvents, eventWithId("outer-no-ignore-missing"), (event) -> {
                assertThat(event, isTagged("_ingest_pipeline_failure"));
                assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][pipeline]").withValue(equalTo("outer")));
                assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][exception]").withValue(containsString("org.elasticsearch.ingest.IngestProcessorException")));
                assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][message]").withValue(stringContainsInOrder("Pipeline processor configured for non-existent pipeline", "my-undefined-pipeline")));

                assertThat(event, excludesField("[@metadata][target_ingest_pipeline]"));
            });


            validateEvent(outputEvents, eventWithId("outer-recursive"), (event) -> {
                assertThat(event, isTagged("_ingest_pipeline_failure"));
                assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][pipeline]").withValue(equalTo("outer")));
                assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][exception]").withValue(containsString("org.elasticsearch.ingest.IngestProcessorException")));
                assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][message]").withValue(stringContainsInOrder("Cycle detected for pipeline:", "outer")));

                assertThat(event, excludesField("[@metadata][target_ingest_pipeline]"));
            });

        }));
    }

    static void withEventProcessor(final PluginConfiguration pluginConfiguration, final Consumer<Event> filterMatchListener, final Consumer<EventProcessor> eventProcessorConsumer) {
        try (EventProcessor eventProcessor = EventProcessor.fromPluginConfiguration(pluginConfiguration, filterMatchListener)) {
            eventProcessorConsumer.accept(eventProcessor);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void validateEvent(final Event event, final Consumer<Event> eventConsumer) {
        eventConsumer.accept(event);
    }

    static void validateEvent(final Collection<Event> events, final Predicate<Event> predicate, final Consumer<Event> eventConsumer) {
        validateEvent(findExactlyOneEvent(events, predicate), eventConsumer);
    }

    static Event findExactlyOneEvent(final Collection<Event> events, final Predicate<Event> predicate) {
        final Event[] matchedEvents = events.stream().filter(predicate).toArray(Event[]::new);
        assertThat(matchedEvents, arrayWithSize(1));
        return matchedEvents[0];
    }

    static Predicate<Event> eventWithId(final Object id) {
        return (event -> Objects.equals(event.getField("id"), id));
    }

    static Event newEvent(final Map<String,Object> data, final Map<String,Object> metadata) {
        final Map<String,Object> intermediate = new HashMap<>(data);
        intermediate.put("@metadata", metadata);
        return BasicEventFactory.INSTANCE.newEvent(Map.copyOf(intermediate));
    }

    static Path getPreparedPipelinesResourcePath(final String packageRelativePath) {
        return getResourcePath(packageRelativePath)
                .map(SmokeTest::ensureContentsReadableNonWritable)
                .orElseThrow(() -> new IllegalArgumentException(String.format("failed to load resource for `%s`", packageRelativePath)));
    }

    static Optional<Path> getResourcePath(final String packageRelativePath) {
        return Optional.ofNullable(SmokeTest.class.getResource(packageRelativePath))
                .map(URL::getPath)
                .map(Paths::get);
    }

    static Path ensureContentsReadableNonWritable(Path path) {
        for (File file : Objects.requireNonNull(path.toFile().listFiles())) {
            if (!file.canRead() && !file.setReadable(true)) { throw new IllegalStateException("failed to make %s readable"); }
            if (file.canWrite() && !file.setWritable(false)) { throw new IllegalStateException("failed to make %s non-writable"); }
        }
        return path;
    }

}
