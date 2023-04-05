/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.filters.elasticintegration.resolver.AbstractSimpleCacheableResolver;
import co.elastic.logstash.filters.elasticintegration.resolver.Resolver;
import co.elastic.logstash.filters.elasticintegration.resolver.ResolverCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.safeExtractString;

public class DatastreamEventToPipelineNameResolver implements EventToPipelineNameResolver {

    private static final Logger LOGGER = LogManager.getLogger(DatastreamEventToPipelineNameResolver.class);

    private static final String DATASTREAM_NAMESPACE_FIELD_REFERENCE = "[data_stream][namespace]";
    private static final String DATASTREAM_TYPE_FIELD_REFERENCE = "[data_stream][type]";
    private static final String DATASTREAM_DATASET_FIELD_REFERENCE = "[data_stream][dataset]";

    private final Resolver<String,String> datastreamToPipelineNameResolver;
    private final RestClient elasticsearchRestClient;

    public DatastreamEventToPipelineNameResolver(final RestClient elasticsearchRestClient) {
        this.elasticsearchRestClient = elasticsearchRestClient;
        this.datastreamToPipelineNameResolver = new DatastreamToPipelineNameResolver();
    }

    public DatastreamEventToPipelineNameResolver(final RestClient elasticsearchRestClient,
                                                 final ResolverCache<String,String> cache) {
        this.elasticsearchRestClient = elasticsearchRestClient;
        this.datastreamToPipelineNameResolver = new DatastreamToPipelineNameResolver().withCache(cache);
    }

    @Override
    public Optional<String> resolve(final Event event, final Consumer<Exception> exceptionHandler) {
        return resolveDatastreamName(event)
                .flatMap((datastreamName) -> datastreamToPipelineNameResolver.resolve(datastreamName, exceptionHandler));
    }

    private Optional<String> resolveDatastreamName(Event event) {
        final String namespace = safeExtractString(event, DATASTREAM_NAMESPACE_FIELD_REFERENCE);
        if (Objects.isNull(namespace)) {
            LOGGER.trace(() -> String.format("datastream not resolved from event: `%s` had no value", DATASTREAM_NAMESPACE_FIELD_REFERENCE));
            return Optional.empty();
        }

        final String type = safeExtractString(event, DATASTREAM_TYPE_FIELD_REFERENCE);
        if (Objects.isNull(type)) {
            LOGGER.trace(() -> String.format("datastream not resolved from event: `%s` had no value", DATASTREAM_TYPE_FIELD_REFERENCE));
            return Optional.empty();
        }

        final String dataset = safeExtractString(event, DATASTREAM_DATASET_FIELD_REFERENCE);
        if (Objects.isNull(dataset)) {
            LOGGER.trace(() -> String.format("datastream not resolved from event: `%s` had no value", DATASTREAM_DATASET_FIELD_REFERENCE));
            return Optional.empty();
        }

        final String composedDatastream = String.format("%s-%s-%s", type, dataset, namespace);
        LOGGER.trace(() -> String.format("datastream resolved from event: `%s`", composedDatastream));

        return Optional.of(composedDatastream);
    }

    public class DatastreamToPipelineNameResolver extends AbstractSimpleCacheableResolver<String,String> {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public Optional<String> resolveSafely(final String datastreamName) throws Exception {
            LOGGER.debug(() -> String.format("fetching template for datastream `%s`", datastreamName));
            try {
                Request request = new Request(
                        "POST",
                        URLEncodedUtils.formatSegments("_index_template", "_simulate", datastreamName));
                Response response = elasticsearchRestClient.performRequest(request);

                final String responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                final JsonNode templateSettingsIndex = MAPPER.readTree(responseBody)
                        .path("template")
                        .path("settings")
                        .path("index");

                if (templateSettingsIndex.isMissingNode() || !templateSettingsIndex.isObject()) {
                    LOGGER.warn(() -> String.format("elasticsearch simulate_index response for `%s` did not include template.settings.index (%s)", datastreamName, responseBody));
                    return Optional.empty();
                }

                final Optional<String> defaultPipeline = Optional.ofNullable(templateSettingsIndex.get("default_pipeline")).map(JsonNode::textValue);

                defaultPipeline.ifPresentOrElse((resolvedPipeline) -> {
                    LOGGER.debug(() -> String.format("resolved datastream default pipeline `%s` -> `%s`", datastreamName, resolvedPipeline));
                }, () -> {
                    LOGGER.debug(() -> String.format("resolved datastream default pipeline for `%s` is empty", datastreamName));
                });

                return defaultPipeline;
            } catch (IOException e) {
                LOGGER.error(() -> String.format("error determining pipeline for datastream `%s` [%s]", datastreamName, describeThrowableWithCause(e)));
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
}
