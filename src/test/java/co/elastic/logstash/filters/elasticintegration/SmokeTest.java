/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.filters.elasticintegration.util.PluginContext;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.BasicEventFactory;

import java.io.File;
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
import java.util.regex.Pattern;

import static co.elastic.logstash.filters.elasticintegration.EventMatchers.*;
import static co.elastic.logstash.filters.elasticintegration.EventProcessor.PIPELINE_MAGIC_NONE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;

public class SmokeTest {
    @Test
    public void givenAFieldWithUserAgentStringTheCorrespondingProcessorIsAbleToParseIt() {
        final List<Event> inputEvents = List.of(
                newEvent(Map.of("webbrowser", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"),
                        Map.of("meta", "ok"))
        );
        final List<Event> matchedEvents = new ArrayList<>();
        final EventProcessorBuilder eventProcessorBuilder = EventProcessor.builder()
                .setEventPipelineNameResolver((event, exceptionConsumer) -> Optional.of("user-agent-mutate"))
                .setEventIndexNameResolver((event, handler) -> Optional.empty()) // no index name
                .setIndexNamePipelineNameResolver(((indexName, handler) -> Optional.empty())) // no default pipeline
                .setPipelineConfigurationResolver(new LocalDirectoryPipelineConfigurationResolver(getPreparedPipelinesResourcePath("simple-mutate-pipelines")))
                .setFilterMatchListener(matchedEvents::add);

        withEventProcessor(eventProcessorBuilder, (eventProcessor) -> {
            final Collection<Event> outputEvents = eventProcessor.processEvents(inputEvents);

            assertThat("event count is unchanged", outputEvents, hasSize(inputEvents.size()));
            Event firstEvent = outputEvents.iterator().next();

            assertAll("user_agent is correctly decomposed", () -> {
                assertThat(firstEvent, includesField("[user_agent][version]").withValue(equalTo("109.0.0.0")));
                assertThat(firstEvent, includesField("[user_agent][os][name]").withValue(equalTo("Windows")));
                assertThat(firstEvent, includesField("[user_agent][os][version]").withValue(equalTo("10")));
                assertThat(firstEvent, includesField("[user_agent][name]").withValue(equalTo("Chrome")));
                assertThat(firstEvent, includesField("[user_agent][device][name]").withValue(equalTo("Other")));
            });

            assertAll("matched event is tagged correctly", () -> {
                assertThat(firstEvent, is(in(matchedEvents)));
                assertThat(firstEvent, includesField("[@metadata][target_ingest_pipeline]").withValue(equalTo(PIPELINE_MAGIC_NONE)));
            });
        });
    }

    @Test
    public void testSinglePipelineMutatingEvents() {
        final List<Event> matchedEvents = new ArrayList<>();
        final EventProcessorBuilder eventProcessorBuilder = EventProcessor.builder()
                .setEventPipelineNameResolver((event, exceptionConsumer) -> Optional.of("simple-mutate"))
                .setEventIndexNameResolver((event, handler) -> Optional.empty()) // no index name
                .setIndexNamePipelineNameResolver(((indexName, handler) -> Optional.empty())) // no default pipeline
                .setPipelineConfigurationResolver(new LocalDirectoryPipelineConfigurationResolver(getPreparedPipelinesResourcePath("simple-mutate-pipelines")))
                .setFilterMatchListener(matchedEvents::add);

        final List<Event> inputEvents = List.of(
                newEvent(Map.of("toplevel", "ok", "id", "first","required-field-to-remove","present","nested", Map.of("field-to-lowercase", "sIlLyCaSe3", "field-to-remove", "nope", "field-to-keep", "ok")), Map.of("meta", "ok")),
                newEvent(Map.of("toplevel", "ok", "id", "second", "nested", Map.of("field-to-lowercase", "sIlLyCaSe3", "field-to-remove", "nope", "field-to-keep", "ok")), Map.of("meta", "ok")),
                newEvent(Map.of("toplevel", "ok", "id", "third","required-field-to-remove","present","nested", Map.of( "field-to-remove", "nope", "field-to-keep", "ok")), Map.of("meta", "ok"))
        );


        withEventProcessor(eventProcessorBuilder, (eventProcessor) -> {
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

                assertAll("matched event is tagged correctly", () -> {
                    assertThat(firstEvent, is(in(matchedEvents)));
                    assertThat(firstEvent, includesField("[@metadata][target_ingest_pipeline]").withValue(equalTo("_none")));
                });
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

                assertAll("matched event is tagged correctly", () -> {
                    assertThat(thirdEvent, is(in(matchedEvents)));
                    assertThat(thirdEvent, includesField("[@metadata][target_ingest_pipeline]").withValue(equalTo("_none")));
                });
            });
        });
    }


    @Test void testMultiplePipelinesMutatingEvents() {

        final List<Event> matchedEvents = new ArrayList<>();
        final EventProcessorBuilder eventProcessorBuilder = EventProcessor.builder()
                .setEventPipelineNameResolver(new FieldValueEventToPipelineNameResolver("[@metadata][ingest_pipeline]"))
                .setEventIndexNameResolver((event, handler) -> Optional.empty()) // no index name
                .setIndexNamePipelineNameResolver(((indexName, handler) -> Optional.empty())) // no default pipeline
                .setPipelineConfigurationResolver(new LocalDirectoryPipelineConfigurationResolver(getPreparedPipelinesResourcePath("nesting-pipelines")))
                .setFilterMatchListener(matchedEvents::add);

        final List<Event> inputEvents = List.of(
                newEvent(Map.of("toplevel", "ok", "id", "outer-ignore-missing", "ignore_missing", true), Map.of("ingest_pipeline", "outer")),
                newEvent(Map.of("toplevel", "ok", "id", "explicit-none", "ignore_missing", true), Map.of("ingest_pipeline", "_none")),
                newEvent(Map.of("toplevel", "ok", "id", "implicit-none", "ignore_missing", true), Map.of("no_ingest_pipeline", "ok")),
                newEvent(Map.of("toplevel", "ok", "id", "inner-only", "ignore_missing", true), Map.of("ingest_pipeline", "inner")),
                newEvent(Map.of("toplevel", "ok", "id", "outer-no-ignore-missing", "ignore_missing", false), Map.of("ingest_pipeline", "outer")),
                newEvent(Map.of("toplevel", "ok", "id", "outer-recursive", "ignore_missing", true, "recursive", true), Map.of("ingest_pipeline", "outer"))
        );


        withEventProcessor(eventProcessorBuilder, (eventProcessor -> {
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

    @Test void testPainlessAccessToIngestCommonProcessors() {
        final List<Event> matchedEvents = new ArrayList<>();
        final EventProcessorBuilder eventProcessorBuilder = EventProcessor.builder()
                .setEventPipelineNameResolver((event, exceptionConsumer) -> Optional.of("pipeline"))
                .setEventIndexNameResolver((event, handler) -> Optional.empty()) // no index name
                .setIndexNamePipelineNameResolver(((indexName, handler) -> Optional.empty())) // no default pipeline
                .setPipelineConfigurationResolver(new LocalDirectoryPipelineConfigurationResolver(getPreparedPipelinesResourcePath("script-processor-pipelines")))
                .setFilterMatchListener(matchedEvents::add);

        final List<Event> inputEvents = List.of(
                newEvent(Map.of("id", "baseline", "lower", "lower","mixed", "MiXeD"), Map.of())
        );

        withEventProcessor(eventProcessorBuilder, (eventProcessor) -> {
            final Collection<Event> outputEvents = eventProcessor.processEvents(inputEvents);
            assertThat("event count is unchanged", outputEvents, hasSize(inputEvents.size()));

            validateEvent(outputEvents, eventWithId("baseline"), (event) -> {
                assertAll(String.format("EVENT(data: %s; meta: %s)", event.getData(), event.getMetadata()), () -> {
                    assertThat(event, not(isTagged("_ingest_pipeline_failure")));
                    assertThat(event, includesField("[lower]").withValue(equalTo("mixed")));
                    assertThat(event, includesField("[mixed]").withValue(equalTo("MiXeD")));
                });
            });
        });
    }

    @Test void testReroutePipelinesMutatingEvents() {
        final List<Event> matchedEvents = new ArrayList<>();

        // if an index has the word `none` in it, resolve to no default pipeline;
        // else, resolve to the index name with `-pipeline` appended to the end
        final Predicate<String> indexHasPipeline = Pattern.compile("\\bnone\\b").asPredicate().negate();
        final IndexNameToPipelineNameResolver pipelineNameResolver = (idx,h) ->
                indexHasPipeline.test(idx) ? Optional.of(String.format("%s-pipeline", idx)) : Optional.empty();

        final EventProcessorBuilder eventProcessorBuilder = EventProcessor.builder()
                .setEventIndexNameResolver(new DatastreamEventToIndexNameResolver())
                .setIndexNamePipelineNameResolver(pipelineNameResolver)
                .setPipelineConfigurationResolver(new LocalDirectoryPipelineConfigurationResolver(getPreparedPipelinesResourcePath("reroute-pipelines")))
                .setFilterMatchListener(matchedEvents::add);

        final List<Event> inputEvents = List.of(
                newEvent(Map.of("toplevel", "ok", "id", "reroute-hardcoded-none", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "reroute-hardcoded-downstream", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "no-reroute", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "reroute-dataset-verify", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "reroute-dataset-none", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "reroute-dataset-missing", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "reroute-namespace-fire", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "reroute-namespace-none", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of()),
                newEvent(Map.of("toplevel", "ok", "id", "reroute-namespace-recursive", "data_stream", dataStreamMap("logs", "test", "smoke")), Map.of())
        );

        withEventProcessor(eventProcessorBuilder, (eventProcessor) -> {
            final Collection<Event> outputEvents = eventProcessor.processEvents(inputEvents);
            assertThat("event count is unchanged", outputEvents, hasSize(inputEvents.size()));

            validateEvent(outputEvents, eventWithId("reroute-hardcoded-none"), (event) -> {
                assertAll("reroute(destination: none)", () -> {
                    assertThat(event, includesField("[handled-by-root-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("none")));
                    assertThat(event, excludesField("[handled-by-root-done]"));
                });
            });

            validateEvent(outputEvents, eventWithId("reroute-hardcoded-downstream"), (event) -> {
                assertAll("reroute(destination: downstream)", () -> {
                    assertThat(event, includesField("[handled-by-root-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("downstream")));
                    assertThat(event, includesField("[handled-by-downstream-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[handled-by-downstream-done]").withValue(equalTo(true)));
                    assertThat(event, excludesField("[handled-by-root-done]"));
                });
            });

            validateEvent(outputEvents, eventWithId("reroute-dataset-verify"), (event) -> {
                assertAll("reroute(dataset: verify)", () -> {
                    assertThat(event, includesField("[handled-by-root-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("logs-verify-smoke")));
                    assertThat(event, includesField("[data_stream][type]").withValue(equalTo("logs")));
                    assertThat(event, includesField("[data_stream][dataset]").withValue(equalTo("verify")));
                    assertThat(event, includesField("[data_stream][namespace]").withValue(equalTo("smoke")));
                    assertThat(event, includesField("[handled-by-logs-verify-smoke-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[handled-by-logs-verify-smoke-done]").withValue(equalTo(true)));
                    assertThat(event, excludesField("[handled-by-root-done]"));
                });
            });

            validateEvent(outputEvents, eventWithId("reroute-dataset-none"), (event) -> {
                assertAll("reroute(dataset: none)", () -> {
                    assertThat(event, includesField("[handled-by-root-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("logs-none-smoke")));
                    assertThat(event, includesField("[data_stream][type]").withValue(equalTo("logs")));
                    assertThat(event, includesField("[data_stream][dataset]").withValue(equalTo("none")));
                    assertThat(event, includesField("[data_stream][namespace]").withValue(equalTo("smoke")));
                    assertThat(event, excludesField("[handled-by-root-done]"));
                });
            });

            validateEvent(outputEvents, eventWithId("reroute-dataset-missing"), (event) -> {
                assertAll("reroute(dataset: none)", () -> {
                    assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][pipeline]").withValue(equalTo("logs-test-smoke-pipeline")));
                    assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][message]").withValue(containsString("reroute failed to load next pipeline [logs-test-smoke-pipeline]")));
                    assertThat(event, excludesField("[handled-by-root-init]"));
                });
            });

            validateEvent(outputEvents, eventWithId("reroute-namespace-fire"), (event) -> {
                assertAll("reroute(namespace: fire)", () -> {
                    assertThat(event, includesField("[handled-by-root-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("logs-test-fire")));
                    assertThat(event, includesField("[data_stream][type]").withValue(equalTo("logs")));
                    assertThat(event, includesField("[data_stream][dataset]").withValue(equalTo("test")));
                    assertThat(event, includesField("[data_stream][namespace]").withValue(equalTo("fire")));
                    assertThat(event, includesField("[handled-by-logs-test-fire-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[handled-by-logs-test-fire-done]").withValue(equalTo(true)));
                    assertThat(event, excludesField("[handled-by-root-done]"));
                });
            });

            validateEvent(outputEvents, eventWithId("reroute-namespace-none"), (event) -> {
                assertAll("reroute(namespace: none)", () -> {
                    assertThat(event, includesField("[handled-by-root-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("logs-test-none")));
                    assertThat(event, includesField("[data_stream][type]").withValue(equalTo("logs")));
                    assertThat(event, includesField("[data_stream][dataset]").withValue(equalTo("test")));
                    assertThat(event, includesField("[data_stream][namespace]").withValue(equalTo("none")));
                    assertThat(event, excludesField("[handled-by-root-done]"));
                });
            });

            validateEvent(outputEvents, eventWithId("reroute-namespace-recursive"), (event) -> {
                assertAll("reroute(namespace: recursive)", () -> {
                    assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][pipeline]").withValue(equalTo("logs-test-recursive-pipeline")));
                    assertThat(event, includesField("[@metadata][_ingest_pipeline_failure][message]").withValue(containsString("index cycle detected while processing pipeline [logs-test-recursive-pipeline]")));
                    assertThat(event, excludesField("[handled-by-root-init]"));
                });
            });

            validateEvent(outputEvents, eventWithId("no-reroute"), (event) -> {
                assertAll("NO reroute", () -> {
                    assertThat(event, includesField("[handled-by-root-init]").withValue(equalTo(true)));
                    assertThat(event, includesField("[@metadata][_ingest_document][index]").withValue(equalTo("logs-test-smoke")));
                    assertThat(event, includesField("[handled-by-root-done]").withValue(equalTo(true)));
                });
            });
        });
    }

    private Map<String,String> dataStreamMap(final String type, final String dataset, final String namespace) {
        return Map.of("namespace", namespace, "type", type, "dataset", dataset);
    }

    static void withEventProcessor(final EventProcessorBuilder eventProcessorBuilder, final ExceptionalConsumer<EventProcessor> eventProcessorConsumer) {
        final PluginContext anonymousPluginContext = new PluginContext("NONE", "TEST");
        try (EventProcessor eventProcessor = eventProcessorBuilder.build(anonymousPluginContext)) {
            eventProcessorConsumer.accept(eventProcessor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface ExceptionalConsumer<T> {
        void accept(T thing) throws Exception;
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
