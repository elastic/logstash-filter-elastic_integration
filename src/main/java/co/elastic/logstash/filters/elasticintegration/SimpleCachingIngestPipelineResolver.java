package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.ResolverCache;
import co.elastic.logstash.filters.elasticintegration.resolver.SimpleCachingResolver;

public class SimpleCachingIngestPipelineResolver extends SimpleCachingResolver<String, IngestPipeline> implements IngestPipelineResolver {
    public SimpleCachingIngestPipelineResolver(final ResolverCache<String, IngestPipeline> cache, final SimpleIngestPipelineResolver bindableCacheMissResolver) {
        super(cache, (Bindable<String, IngestPipeline>) bindableCacheMissResolver);
    }
}
