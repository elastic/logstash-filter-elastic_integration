/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.AbstractSimpleResolver;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.ingest.PipelineConfiguration;

import java.util.Optional;

/**
 * An {@code ElasticsearchPipelineConfigurationResolver} is a {@link PipelineConfigurationResolver}
 * that retrieves pipelines from Elasticsearch.
 */
public class ElasticsearchPipelineConfigurationResolver
        extends AbstractSimpleResolver<String,PipelineConfiguration>
        implements PipelineConfigurationResolver {
    private final RestClient elasticsearchRestClient;
    private final PipelineConfigurationFactory pipelineConfigurationFactory;

    private static final Logger LOGGER = LogManager.getLogger(ElasticsearchPipelineConfigurationResolver.class);

    public ElasticsearchPipelineConfigurationResolver(final RestClient elasticsearchRestClient) {
        this.elasticsearchRestClient = elasticsearchRestClient;
        this.pipelineConfigurationFactory = PipelineConfigurationFactory.getInstance();
    }

    @Override
    public Optional<PipelineConfiguration> resolveSafely(String pipelineName) throws Exception {
        final Response response;
        try {
            final Request request = new Request("GET", URLEncodedUtils.formatSegments("_ingest", "pipeline", pipelineName));
            response = elasticsearchRestClient.performRequest(request);
            final String jsonEncodedPayload = EntityUtils.toString(response.getEntity());
            final PipelineConfiguration pipelineConfiguration = pipelineConfigurationFactory.parseNamedObject(jsonEncodedPayload);
            return Optional.of(pipelineConfiguration);
        } catch (ResponseException re) {
            if (re.getResponse().getStatusLine().getStatusCode() == 404) {
                LOGGER.warn(String.format("pipeline not found: `%s`", pipelineName), re.getMessage());
            } else {
                LOGGER.error(String.format("failed to fetch pipeline: `%s`", pipelineName), re);
                throw re;
            }
        } catch (Exception ex) {
            LOGGER.error(String.format("failed to fetch pipeline: `%s`", pipelineName), ex);
            throw ex;
        }
        return Optional.empty();
    }
}
