/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.ResolverCache;
import co.elastic.logstash.filters.elasticintegration.resolver.SimpleCachingResolver;

public class SimpleCachingIngestPipelineResolver extends SimpleCachingResolver<String, IngestPipeline> implements IngestPipelineResolver {
    public SimpleCachingIngestPipelineResolver(final ResolverCache<String, IngestPipeline> cache, final SimpleIngestPipelineResolver bindableCacheMissResolver) {
        super(cache, (Bindable<String, IngestPipeline>) bindableCacheMissResolver);
    }
}
