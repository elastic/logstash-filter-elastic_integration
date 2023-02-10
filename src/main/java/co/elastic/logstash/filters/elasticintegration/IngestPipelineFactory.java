package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.ingest.PipelineProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ingest.Pipeline;
import org.elasticsearch.ingest.PipelineConfiguration;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.script.ScriptService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link IngestPipelineFactory} is capable of creating {@link IngestPipeline}s
 * from {@link PipelineConfiguration}s.
 */
public class IngestPipelineFactory {
    private final ScriptService scriptService;
    private final Map<String, Processor.Factory> processorFactories;

    private static final Logger LOGGER = LogManager.getLogger(IngestPipelineFactory.class);

    public IngestPipelineFactory(final ScriptService scriptService) {
        this(scriptService, Map.of());
    }

    private IngestPipelineFactory(final ScriptService scriptService,
                                  final Map<String, Processor.Factory> processorFactories) {
        this.scriptService = scriptService;
        this.processorFactories = Map.copyOf(processorFactories);
    }

    public IngestPipelineFactory withProcessors(final Map<String, Processor.Factory> processorFactories) {
        final Map<String, Processor.Factory> intermediate = new HashMap<>(this.processorFactories);
        intermediate.putAll(processorFactories);
        return new IngestPipelineFactory(scriptService, intermediate);
    }

    public Optional<IngestPipeline> create(final PipelineConfiguration pipelineConfiguration) {
        try {
            final Pipeline pipeline = Pipeline.create(pipelineConfiguration.getId(), pipelineConfiguration.getConfigAsMap(), processorFactories, scriptService);
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
     *         resolve pipleines through the provided {@link IngestPipelineResolver}.
     */
    public IngestPipelineFactory withIngestPipelineResolver(final IngestPipelineResolver ingestPipelineResolver) {
        final Map<String, Processor.Factory> modifiedProcessorFactories = new HashMap<>(this.processorFactories);
        modifiedProcessorFactories.put(PipelineProcessor.TYPE, new PipelineProcessor.Factory(ingestPipelineResolver, this.scriptService));
        return new IngestPipelineFactory(scriptService, modifiedProcessorFactories);
    }
}
