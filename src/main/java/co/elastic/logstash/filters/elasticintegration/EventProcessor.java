/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.FilterMatchListener;
import co.elastic.logstash.filters.elasticintegration.resolver.Resolver;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.logstashbridge.core.FailProcessorExceptionBridge;
import org.elasticsearch.logstashbridge.core.IOUtilsBridge;
import org.elasticsearch.logstashbridge.core.RefCountingRunnableBridge;
import org.elasticsearch.logstashbridge.ingest.IngestDocumentBridge;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.eventAsMap;
import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.serializeEventForLog;

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

    private final EventToIndexNameResolver eventToIndexNameResolver;
    private final IndexNameToPipelineNameResolver indexNameToPipelineNameResolver;
    private final IngestDuplexMarshaller eventMarshaller;

    private final List<Closeable> resourcesToClose;

    private static final Logger LOGGER = LogManager.getLogger(EventProcessor.class);

    private static final String METADATA_FAILURE_TEMPLATE = "[@metadata][_ingest_pipeline_failure][%s]";
    private static final String TARGET_PIPELINE_FIELD = "[@metadata][target_ingest_pipeline]";
    static final String PIPELINE_MAGIC_NONE = "_none";

    EventProcessor(final FilterMatchListener filterMatchListener,
                   final IngestPipelineResolver internalPipelineProvider,
                   final EventToPipelineNameResolver eventToPipelineNameResolver,
                   final EventToIndexNameResolver eventToIndexNameResolver,
                   final IndexNameToPipelineNameResolver indexNameToPipelineNameResolver,
                   final Collection<Closeable> resourcesToClose) {
        this.filterMatchListener = filterMatchListener;
        this.internalPipelineProvider = internalPipelineProvider;
        this.eventToIndexNameResolver = eventToIndexNameResolver;
        this.eventToPipelineNameResolver = eventToPipelineNameResolver;
        this.indexNameToPipelineNameResolver = indexNameToPipelineNameResolver;
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
    public Collection<Event> processEvents(final Collection<Event> incomingEvents) throws InterruptedException, TimeoutException {
        final CountDownLatch latch = new CountDownLatch(1);
        final IntegrationBatch batch = new IntegrationBatch(incomingEvents);

        RefCountingRunnableBridge ref = new RefCountingRunnableBridge(latch::countDown);
        try {
            batch.eachRequest(ref::acquire, this::processRequest);
        } finally {
            ref.close();
        }

        // await on work that has gone async
        if (!latch.await(300, TimeUnit.SECONDS)) {
            // because the work is async and we have no way of identifying or recovering
            // stuck resources, a failure to complete a batch is catastrophic and SHOULD
            // result in a crash of the pipeline.
            throw new TimeoutException("breaker: catastrophic batch limit reached");
        };

        return batch.events;
    }

    /**
     * Processes a singular incoming integration request, resulting in {@code IntegrationRequest#complete}.
     */
    void processRequest(final IntegrationRequest request) {
        try {
            final Optional<String> resolvedIndexName = eventToIndexNameResolver.resolve(request.event(), EventProcessor::throwingHandler);

            final Optional<String> resolvedPipelineName;
            if (Objects.nonNull(eventToPipelineNameResolver)) {
                // when configured wth an event-to-pipeline-name resolver, it OVERRIDES index-based pipeline resolving
                resolvedPipelineName = resolve(request.event(), eventToPipelineNameResolver);
            } else if (resolvedIndexName.isPresent()) {
                // when have a resolved index name, we use it to resolve the pipeline name
                resolvedPipelineName = resolve(resolvedIndexName.get(), indexNameToPipelineNameResolver);
            } else {
                resolvedPipelineName = Optional.empty();
            }

            if (resolvedPipelineName.isEmpty()) {
                LOGGER.debug(() -> String.format("No pipeline resolved for event %s", serializeEventForLog(LOGGER, request.event())));
                request.complete();
                return;
            }

            final String pipelineName = resolvedPipelineName.get();
            if (pipelineName.equals(PIPELINE_MAGIC_NONE)) {
                LOGGER.debug(() -> String.format("Ingest Pipeline bypassed with pipeline `%s` for event `%s`", pipelineName, serializeEventForLog(LOGGER, request.event())));
                request.complete();
                return;
            }

            final Optional<IngestPipeline> loadedPipeline = resolve(pipelineName, internalPipelineProvider);
            if (loadedPipeline.isEmpty()) {
                LOGGER.warn(() -> String.format("Pipeline `%s` could not be loaded", pipelineName));
                request.complete(incomingEvent -> {
                    annotateIngestPipelineFailure(incomingEvent, pipelineName, Map.of("message", "pipeline not loaded"));
                });
                return;
            }

            final IngestPipeline ingestPipeline = loadedPipeline.get();
            LOGGER.trace(() -> String.format("Using loaded pipeline `%s` (%s)", pipelineName, System.identityHashCode(ingestPipeline)));
            final IngestDocumentBridge ingestDocument = eventMarshaller.toIngestDocument(request.event());

            resolvedIndexName.ifPresent(indexName -> {
                ingestDocument.getMetadata().setIndex(indexName);
                ingestDocument.updateIndexHistory(indexName);
            });

            executePipeline(ingestDocument, ingestPipeline, request);
        } catch (Exception e) {
            LOGGER.error(() -> String.format("exception processing event: %s", e.getMessage()));
            request.complete(incomingEvent -> {
                annotateIngestPipelineFailure(incomingEvent, "UNKNOWN", Map.of(
                        "message", e.getMessage(),
                        "exception", e.getClass().getName()
                ));
            });
        }
    }

    private void executePipeline(final IngestDocumentBridge ingestDocument, final IngestPipeline ingestPipeline, final IntegrationRequest request) {
        final String pipelineName = ingestPipeline.getId();
        final String originalIndex = ingestDocument.getMetadata().getIndex();
        ingestPipeline.execute(ingestDocument, (resultIngestDocument, ingestPipelineException) -> {
            // If no exception, then the original event is to be _replaced_ by the result
            if (Objects.nonNull(ingestPipelineException)) {
                // If we had an exception in the IngestPipeline, tag and emit the original Event
                final Throwable unwrappedException = unwrapException(ingestPipelineException);
                LOGGER.warn(() -> String.format("ingest pipeline `%s` failed", pipelineName), unwrappedException);
                request.complete(incomingEvent -> {
                    annotateIngestPipelineFailure(incomingEvent, pipelineName, Map.of(
                            "message", unwrappedException.getMessage(),
                            "exception", unwrappedException.getClass().getName()
                    ));
                });
            } else if (Objects.isNull(resultIngestDocument)) {
                request.complete(incomingEvent -> {
                    LOGGER.trace(() -> String.format("event cancelled by ingest pipeline `%s`: %s", pipelineName, serializeEventForLog(LOGGER, incomingEvent)));
                    incomingEvent.cancel();
                });
            } else {

                final String newIndex = resultIngestDocument.getMetadata().getIndex();
                if (!Objects.equals(originalIndex, newIndex) && ingestDocument.isReroute()) {
                    ingestDocument.resetReroute();
                    boolean cycle = !resultIngestDocument.updateIndexHistory(newIndex);
                    if (cycle) {
                        request.complete(incomingEvent -> {
                            annotateIngestPipelineFailure(incomingEvent, pipelineName, Map.of("message",
                                    String.format(Locale.ROOT, "index cycle detected while processing pipeline [%s]: %s + %s",
                                        pipelineName,
                                        resultIngestDocument.getIndexHistory(),
                                        newIndex)
                            ));
                        });
                        return;
                    }


                    final Optional<String> reroutePipelineName = resolve(newIndex, indexNameToPipelineNameResolver);
                    if (reroutePipelineName.isPresent() && !reroutePipelineName.get().equals(PIPELINE_MAGIC_NONE)) {
                        final Optional<IngestPipeline> reroutePipeline = resolve(reroutePipelineName.get(), internalPipelineProvider);
                        if (reroutePipeline.isEmpty()) {
                            request.complete(incomingEvent -> {
                                annotateIngestPipelineFailure(
                                        incomingEvent,
                                        pipelineName,
                                        Map.of("message",
                                                String.format(Locale.ROOT, "reroute failed to load next pipeline [%s]: %s -> %s",
                                                        pipelineName,
                                                        resultIngestDocument.getIndexHistory(),
                                                        reroutePipelineName.get())));
                            });
                        } else {
                            executePipeline(resultIngestDocument, reroutePipeline.get(), request);
                        }
                        return;
                    }
                }


                request.complete(incomingEvent -> {
                    final Event resultEvent = eventMarshaller.toLogstashEvent(resultIngestDocument);
                    // provide downstream ES output with hint to avoid re-running the same pipelines
                    resultEvent.setField(TARGET_PIPELINE_FIELD, PIPELINE_MAGIC_NONE);
                    filterMatchListener.filterMatched(resultEvent);

                    LOGGER.trace(() -> String.format("event transformed by ingest pipeline `%s`%s", pipelineName, diff(incomingEvent, resultEvent)));

                    incomingEvent.cancel();
                    return resultEvent;
                });
            }
        });
    }

    static private void annotateIngestPipelineFailure(final Event event, final String pipelineName, Map<String,String> meta) {
        event.tag("_ingest_pipeline_failure");
        event.setField(String.format(METADATA_FAILURE_TEMPLATE, "pipeline"), pipelineName);
        meta.forEach((metaKey, metaValue) -> {
            event.setField(String.format(METADATA_FAILURE_TEMPLATE, metaKey), metaValue);
        });
    }

    static private Throwable unwrapException(final Exception exception) {
        if (FailProcessorExceptionBridge.isInstanceOf(exception.getCause())) {
            return exception.getCause();
        }
        return exception;
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

    static private <T,R> Optional<R> resolve(T resolvable, Resolver<T,R> resolver) {
        return resolver.resolve(resolvable, EventProcessor::throwingHandler);
    }

    @Override
    public void close() throws IOException {
        IOUtilsBridge.closeWhileHandlingException(this.resourcesToClose);
    }
}
