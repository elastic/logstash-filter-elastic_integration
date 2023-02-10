package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.CacheableResolver;
import co.elastic.logstash.filters.elasticintegration.resolver.ResolverCache;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A {@link SimpleIngestPipelineResolver} is a cacheable {@link IngestPipelineResolver} that is
 * capable of resolving named pipelines <em>through</em> a {@link PipelineConfigurationResolver},
 * using a {@link IngestPipelineFactory}.
 */
final class SimpleIngestPipelineResolver
        implements IngestPipelineResolver, CacheableResolver<String, IngestPipeline> {
    private final PipelineConfigurationResolver pipelineConfigurationResolver;
    private final IngestPipelineFactory ingestPipelineFactory;

    public SimpleIngestPipelineResolver(final PipelineConfigurationResolver pipelineConfigurationResolver,
                                        final IngestPipelineFactory ingestPipelineFactory) {
        this.pipelineConfigurationResolver = pipelineConfigurationResolver;
        this.ingestPipelineFactory = ingestPipelineFactory;
    }

    @Override
    public Optional<IngestPipeline> resolve(final String resolveKey,
                                            final Consumer<Exception> exceptionHandler) {
        return pipelineConfigurationResolver.resolve(resolveKey, exceptionHandler).flatMap((pc) -> {
            try {
                return ingestPipelineFactory.create(pc);
            } catch (Exception e) {
                exceptionHandler.accept(e);
                return Optional.empty();
            }
        });
    }


    @Override
    public SimpleCachingIngestPipelineResolver withCache(final ResolverCache<String, IngestPipeline> cache) {
        return new SimpleCachingIngestPipelineResolver(cache, this);
    }
}
