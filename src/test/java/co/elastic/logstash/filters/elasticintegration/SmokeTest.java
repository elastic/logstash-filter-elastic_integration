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

            validateEvent(outputEvents.stream().filter(e -> e.getField("id").equals("first")).findFirst().orElseThrow(), (firstEvent) -> {
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

            validateEvent(outputEvents.stream().filter(e -> e.getField("id").equals("second")).findFirst().orElseThrow(), (secondEvent) -> {

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

            validateEvent(outputEvents.stream().filter(e -> e.getField("id").equals("third")).findFirst().orElseThrow(), (thirdEvent) -> {
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
