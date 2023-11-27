/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.AbstractSimpleCacheableResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public class ElasticsearchIndexNameToPipelineNameResolver
        extends AbstractSimpleCacheableResolver<String,String>
        implements IndexNameToPipelineNameResolver.Cacheable {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient elasticsearchRestClient;

    public ElasticsearchIndexNameToPipelineNameResolver(final RestClient elasticsearchRestClient) {
        this.elasticsearchRestClient = elasticsearchRestClient;
    }

    @Override
    public Optional<String> resolveSafely(String indexName) throws Exception {
        LOGGER.debug(() -> String.format("fetching template for index `%s`", indexName));
        try {
            Request request = new Request(
                    "POST",
                    URLEncodedUtils.formatSegments("_index_template", "_simulate_index", indexName));
            Response response = elasticsearchRestClient.performRequest(request);

            final String responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            final JsonNode templateSettingsIndex = MAPPER.readTree(responseBody)
                    .path("template")
                    .path("settings")
                    .path("index");

            if (templateSettingsIndex.isMissingNode() || !templateSettingsIndex.isObject()) {
                LOGGER.warn(() -> String.format("elasticsearch simulate_index response for `%s` did not include template.settings.index (%s)", indexName, responseBody));
                return Optional.empty();
            }

            final Optional<String> defaultPipeline = Optional.ofNullable(templateSettingsIndex.get("default_pipeline")).map(JsonNode::textValue);

            defaultPipeline.ifPresentOrElse((resolvedPipeline) -> {
                LOGGER.debug(() -> String.format("resolved datastream default pipeline `%s` -> `%s`", indexName, resolvedPipeline));
            }, () -> {
                LOGGER.debug(() -> String.format("resolved datastream default pipeline for `%s` is empty", indexName));
            });

            return defaultPipeline;
        } catch (IOException e) {
            LOGGER.error(() -> String.format("error determining pipeline for datastream `%s` [%s]", indexName, describeThrowableWithCause(e)));
            throw e;
        }
    }

    private String describeThrowableWithCause(final Throwable throwable) {
        final StringBuilder description = new StringBuilder().append("(");
        Throwable current = throwable;
        while (Objects.nonNull(current)) {
            description.append(current);
            current = current.getCause();
            if (Objects.nonNull(current)) {
                description.append(") caused by (");
            }
        }
        description.append(")");
        return description.toString();
    }
}
