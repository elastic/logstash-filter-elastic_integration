/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.FilterMatchListener;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.ingest.common.FailProcessorException;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * An {@link EventProcessor} processes {@link Event}s by:
 * <ol>
 *     <li>using an {@link EventToPipelineNameResolver} to determine which named ingest
 *         pipeline should be executed (if any),</li>
 *     <li>using an {@link IngestPipelineResolver} to retrieve an {@link IngestPipeline},</li>
 *     <li>executing the pipeline for the event,</li>
 *     <li>emitting the transformed result XOR the original event tagged with appropriate metadata</li>
 * </ol>
 */
public class EventProcessor implements Closeable {
    private final FilterMatchListener filterMatchListener;
    private final IngestPipelineResolver internalPipelineProvider;

    private final EventToPipelineNameResolver eventToPipelineNameResolver;
    private final IngestDuplexMarshaller eventMarshaller;

    private final List<Closeable> resourcesToClose;

    private static final Logger LOGGER = LogManager.getLogger(EventProcessor.class);

    private static final String METADATA_FAILURE_TEMPLATE = "[@metadata][_ingest_pipeline_failure][%s]";
    private static final String TARGET_PIPELINE_FIELD = "[@metadata][target_ingest_pipeline]";
    private static final String PIPELINE_MAGIC_NONE = "_none";

    EventProcessor(final FilterMatchListener filterMatchListener,
                   final IngestPipelineResolver internalPipelineProvider,
                   final EventToPipelineNameResolver eventToPipelineNameResolver,
                   final Collection<Closeable> resourcesToClose) {
        this.filterMatchListener = filterMatchListener;
        this.internalPipelineProvider = internalPipelineProvider;
        this.eventToPipelineNameResolver = eventToPipelineNameResolver;
        this.resourcesToClose = List.copyOf(resourcesToClose);
        this.eventMarshaller = IngestDuplexMarshaller.defaultInstance();
    }

    public static EventProcessorBuilder builder() {
        return new EventProcessorBuilder();
    }

    private static void throwingHandler(Exception e) {
        throw new RuntimeException(e);
    }

    /**
     * Processes a collection of events, returning the resulting collection
     * @param incomingEvents the incoming batch
     * @return the outgoing batch, which <em>may</em> contain cancelled events
     */
    public Collection<Event> processEvents(final Collection<Event> incomingEvents) {
        final List<Event> outgoingEvents = new ArrayList<>(incomingEvents.size());

        for (Event incomingEvent : incomingEvents) {
            processEvent(incomingEvent, outgoingEvents::add);
        }

        return outgoingEvents;
    }

    /**
     * Processes a singular incoming event, passing one or more results to the provided {@code eventConsumer}.
     */
    private void processEvent(final Event incomingEvent, final Consumer<Event> eventConsumer) {
        try {
            final Optional<String> resolvedPipelineName = eventToPipelineNameResolver.resolve(incomingEvent, EventProcessor::throwingHandler);
            if (resolvedPipelineName.isEmpty()) {
                LOGGER.debug(() -> String.format("No pipeline resolved for event %s", serializeEventForLog(incomingEvent)));
                eventConsumer.accept(incomingEvent);
                return;
            }

            final String pipelineName = resolvedPipelineName.get();
            if (pipelineName.equals(PIPELINE_MAGIC_NONE)) {
                LOGGER.debug(() -> String.format("Ingest Pipeline bypassed with pipeline `%s` for event `%s`", pipelineName, serializeEventForLog(incomingEvent)));
                eventConsumer.accept(incomingEvent);
                return;
            }

            final Optional<IngestPipeline> loadedPipeline = internalPipelineProvider.resolve(pipelineName, EventProcessor::throwingHandler);
            if (loadedPipeline.isEmpty()) {
                LOGGER.warn(() -> String.format("Pipeline `%s` could not be loaded", pipelineName));
                annotateIngestPipelineFailure(incomingEvent, pipelineName, Map.of("message", "pipeline not loaded"));
                eventConsumer.accept(incomingEvent);
                return;
            }

            final IngestPipeline ingestPipeline = loadedPipeline.get();
            LOGGER.trace(() -> String.format("Using loaded pipeline `%s` (%s)", pipelineName, System.identityHashCode(ingestPipeline)));
            ingestPipeline.execute(eventMarshaller.toIngestDocument(incomingEvent), (resultIngestDocument, ingestPipelineException) -> {
                if (Objects.nonNull(ingestPipelineException)) {
                    // If we had an exception in the IngestPipeline, tag and emit the original Event
                    final Throwable unwrappedException = unwrapException(ingestPipelineException);
                    LOGGER.warn(() -> String.format("ingest pipeline `%s` failed", pipelineName), unwrappedException);
                    annotateIngestPipelineFailure(incomingEvent, pipelineName, Map.of(
                            "message", unwrappedException.getMessage(),
                            "exception", unwrappedException.getClass().getName()
                    ));
                    eventConsumer.accept(incomingEvent);
                } else {
                    // If no exception, then the original event is to be _replaced_ by the result
                    if (Objects.isNull(resultIngestDocument)) {
                        LOGGER.trace(() -> String.format("event cancelled by ingest pipeline `%s`: %s", pipelineName, serializeEventForLog(incomingEvent)));
                        incomingEvent.cancel();
                        eventConsumer.accept(incomingEvent);
                    } else {
                        final Event resultEvent = eventMarshaller.toLogstashEvent(resultIngestDocument);
                        // provide downstream ES output with hint to avoid re-running the same pipelines
                        resultEvent.setField(TARGET_PIPELINE_FIELD, PIPELINE_MAGIC_NONE);
                        filterMatchListener.filterMatched(resultEvent);

                        LOGGER.trace(() -> String.format("event transformed by ingest pipeline `%s`%s", pipelineName, diff(incomingEvent, resultEvent)));

                        incomingEvent.cancel();
                        eventConsumer.accept(resultEvent);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error(() -> String.format("exception processing event: %s", e.getMessage()));

            annotateIngestPipelineFailure(incomingEvent, "UNKNOWN", Map.of(
                    "message", e.getMessage(),
                    "exception", e.getClass().getName()
            ));

            eventConsumer.accept(incomingEvent);
        }
    }

    static private void annotateIngestPipelineFailure(final Event event, final String pipelineName, Map<String,String> meta) {
        event.tag("_ingest_pipeline_failure");
        event.setField(String.format(METADATA_FAILURE_TEMPLATE, "pipeline"), pipelineName);
        meta.forEach((metaKey, metaValue) -> {
            event.setField(String.format(METADATA_FAILURE_TEMPLATE, metaKey), metaValue);
        });
    }

    static private Throwable unwrapException(final Exception exception) {
        if (exception.getCause() instanceof FailProcessorException) { return exception.getCause(); }
        return exception;
    }

    static private String serializeEventForLog(final Event event) {
        if (LOGGER.isTraceEnabled()) {
            return String.format("Event{%s}", eventAsMap(event));
        } else {
            return event.toString();
        }
    }

    static private Map<String,Object> eventAsMap(final Event event) {
        final Event eventClone = event.clone();
        final Map<String,Object> intermediate = new HashMap<>(eventClone.toMap());
        intermediate.put("@metadata", Map.copyOf(eventClone.getMetadata()));
        return Collections.unmodifiableMap(intermediate);
    }

    static private String diff(final Event original, final Event changed) {
        if (LOGGER.isTraceEnabled()) {
            // dot notation less than ideal for LS-internal, but better than re-writing it ourselves.
            final Map<String,Object> flatOriginal = org.elasticsearch.common.util.Maps.flatten(eventAsMap(original), true, false);
            final Map<String,Object> flatChanged = org.elasticsearch.common.util.Maps.flatten(eventAsMap(changed), true, false);

            final MapDifference<String, Object> difference = Maps.difference(flatOriginal, flatChanged);

            return String.format(": REMOVING{%s} CHANGING{%s} ADDING{%s}", difference.entriesOnlyOnLeft(), difference.entriesDiffering(), difference.entriesOnlyOnRight());
        } else {
            return ""; // TODO
        }
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeWhileHandlingException(this.resourcesToClose);
    }
}
