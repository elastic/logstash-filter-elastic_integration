/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.CacheableResolver;
import co.elastic.logstash.filters.elasticintegration.resolver.ResolverCache;
import co.elastic.logstash.filters.elasticintegration.resolver.SimpleCachingResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A {@link SimpleIngestPipelineResolver} is a cacheable {@link IngestPipelineResolver} that is
 * capable of resolving named pipelines <em>through</em> a {@link PipelineConfigurationResolver},
 * using a {@link IngestPipelineFactory}.
 */
final class SimpleIngestPipelineResolver
        implements IngestPipelineResolver, CacheableResolver<String, IngestPipeline>, SimpleCachingResolver.Bindable<String, IngestPipeline> {

    private final PipelineConfigurationResolver pipelineConfigurationResolver;
    private final IngestPipelineFactory ingestPipelineFactory;

    public SimpleIngestPipelineResolver(final PipelineConfigurationResolver pipelineConfigurationResolver,
                                        final IngestPipelineFactory ingestPipelineFactory) {
        this(pipelineConfigurationResolver, ingestPipelineFactory, null);
    }

    private SimpleIngestPipelineResolver(final PipelineConfigurationResolver pipelineConfigurationResolver,
                                         final IngestPipelineFactory ingestPipelineFactory,
                                         final IngestPipelineResolver binding) {
        this.pipelineConfigurationResolver = pipelineConfigurationResolver;
        this.ingestPipelineFactory = ingestPipelineFactory.withIngestPipelineResolver(Objects.requireNonNullElse(binding, this));
    }

    /**
     * Implements {@link SimpleCachingResolver.Bindable}, producing a <em>copy</em> of this resolver whose
     * internal {@link IngestPipelineFactory} has access to the provided {@link SimpleCachingResolver}, so that
     * the pipelines it generates can lookup named pipelines <em>through</em> the cache.
     *
     * @param cachingResolver the cached resolver that the result will be bound to
     * @return a new ephemeral resolver whose pipeline factory will resolve <em>through</em> the provided cached resolver.
     */
    @Override
    public Ephemeral<String, IngestPipeline> withCachingResolverBinding(final SimpleCachingResolver<String, IngestPipeline> cachingResolver) {
        final IngestPipelineResolver boundCacheableResolver = new SimpleIngestPipelineResolver(pipelineConfigurationResolver, ingestPipelineFactory, cachingResolver::resolve);

        return boundCacheableResolver::resolve;
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
