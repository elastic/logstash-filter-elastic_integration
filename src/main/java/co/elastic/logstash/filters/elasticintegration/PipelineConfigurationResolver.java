package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.UncacheableResolver;
import org.elasticsearch.ingest.PipelineConfiguration;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A {@link PipelineConfigurationResolver} is capable of resolving a pipeline name into
 * an Elasticsearch Ingest {@link PipelineConfiguration}.
 */
@FunctionalInterface
public interface PipelineConfigurationResolver extends UncacheableResolver<String, PipelineConfiguration> {
    @Override
    Optional<PipelineConfiguration> resolve(String pipelineName, Consumer<Exception> exceptionHandler);
}
