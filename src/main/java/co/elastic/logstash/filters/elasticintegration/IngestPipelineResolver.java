package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.Resolver;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * An {@code IngestPipelineResolver} is capable of resolving a pipeline name
 * into an {@link IngestPipeline}.
 */
@FunctionalInterface
public interface IngestPipelineResolver extends Resolver<String, IngestPipeline> {
    @Override
    Optional<IngestPipeline> resolve(String pipelineName, Consumer<Exception> exceptionHandler);
}