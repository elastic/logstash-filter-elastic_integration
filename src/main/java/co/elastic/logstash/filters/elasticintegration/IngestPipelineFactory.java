/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.ingest.PipelineProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.logstashbridge.ingest.PipelineBridge;
import org.elasticsearch.logstashbridge.ingest.PipelineConfigurationBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.script.ScriptServiceBridge;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link IngestPipelineFactory} is capable of creating {@link IngestPipeline}s
 * from {@link PipelineConfigurationBridge}s.
 */
public class IngestPipelineFactory {
    private final ScriptServiceBridge scriptService;
    private final Map<String, ProcessorBridge.Factory> processorFactories;

    private static final Logger LOGGER = LogManager.getLogger(IngestPipelineFactory.class);

    public IngestPipelineFactory(final ScriptServiceBridge scriptService) {
        this(scriptService, Map.of());
    }

    private IngestPipelineFactory(final ScriptServiceBridge scriptService,
                                  final Map<String, ProcessorBridge.Factory> processorFactories) {
        this.scriptService = scriptService;
        this.processorFactories = Map.copyOf(processorFactories);
    }

    public IngestPipelineFactory withProcessors(final Map<String, ProcessorBridge.Factory> processorFactories) {
        final Map<String, ProcessorBridge.Factory> intermediate = new HashMap<>(this.processorFactories);
        intermediate.putAll(processorFactories);
        return new IngestPipelineFactory(scriptService, intermediate);
    }

    public Optional<IngestPipeline> create(final PipelineConfigurationBridge pipelineConfiguration) {
        try {
            final PipelineBridge pipeline = PipelineBridge.create(pipelineConfiguration.getId(), pipelineConfiguration.getConfig(false), processorFactories, scriptService);
            final IngestPipeline ingestPipeline = new IngestPipeline(pipelineConfiguration, pipeline);
            LOGGER.debug(() -> String.format("successfully created ingest pipeline `%s` from pipeline configuration", pipelineConfiguration.getId()));
            return Optional.of(ingestPipeline);
        } catch (Exception e) {
            LOGGER.error(() -> String.format("failed to create ingest pipeline `%s` from pipeline configuration", pipelineConfiguration.getId()), e);
            return Optional.empty();
        }
    }

    /**
     *
     * @param ingestPipelineResolver the {@link IngestPipelineResolver} to resolve through.
     * @return a <em>copy</em> of this {@code IngestPipelineFactory} that has a {@link PipelineProcessor.Factory} that can
     *         resolve pipelines through the provided {@link IngestPipelineResolver}.
     */
    public IngestPipelineFactory withIngestPipelineResolver(final IngestPipelineResolver ingestPipelineResolver) {
        final Map<String, ProcessorBridge.Factory> modifiedProcessorFactories = new HashMap<>(this.processorFactories);
        modifiedProcessorFactories.put(PipelineProcessor.TYPE, new PipelineProcessor.Factory(ingestPipelineResolver, this.scriptService));
        return new IngestPipelineFactory(scriptService, modifiedProcessorFactories);
    }
}
