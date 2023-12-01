/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.readResource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ElasticsearchIndexNameToPipelineNameResolverTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();


    @Test void indexTemplateWithDefaultPipeline() throws Exception {
        withWiremockBackedResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate_index/logs-elastic_agent.metricbeat-default")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-with-template-settings-index-default_pipeline=(banana).json"))));

            final String indexName = "logs-elastic_agent.metricbeat-default";
            assertThat(resolver.resolve(indexName), is(equalTo(Optional.of("banana"))));
        });
    }

    @Test void indexTemplateWithExplicitNoneDefaultPipeline() throws Exception {
        withWiremockBackedResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate_index/logs-none_pipeline-default")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-with-template-settings-index-default_pipeline=(_none).json"))));

            final String indexName = "logs-none_pipeline-default";
            assertThat(resolver.resolve(indexName), is(equalTo(Optional.of("_none"))));
        });
    }

    @Test void indexTemplateWithImplicitNoneDefaultPipeline() throws Exception {
        withWiremockBackedResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate_index/logs-no_pipeline-default")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-without-template-settings-index-default_pipeline.json"))));

            final String indexName = "logs-no_pipeline-default";
            assertThat(resolver.resolve(indexName), is(equalTo(Optional.empty())));
        });
    }

    @Test void indexWithoutTemplateSettings() throws Exception {
        withWiremockBackedResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate_index/logs-ta-sh")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-without-template-settings.json"))));

            assertThat(resolver.resolve("logs-ta-sh"), is(equalTo(Optional.empty())));
        });
    }

    @Test void indexWithEscapableCharacters() throws Exception {
        withWiremockBackedResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate_index/metrics-this%2Fthat%20and%23another%3Fone-custom")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-with-template-settings-index-default_pipeline=(banana).json"))));

            final String indexName = "metrics-this/that and#another?one-custom";
            final AtomicReference<Exception> lastException = new AtomicReference<>();
            assertThat(lastException.get(), is(nullValue()));
            assertThat(resolver.resolve(indexName, lastException::set), is(equalTo(Optional.of("banana"))));
        });
    }

    @Test void insufficientPermissionsToUsSimulateAPI() throws Exception {
        withWiremockBackedResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate_index/logs-some_pipeline-default")
                    .willReturn(aResponse().withStatus(403)));

            final AtomicReference<Exception> lastException = new AtomicReference<>();
            final String indexName = "logs-some_pipeline-default";
            assertThat(resolver.resolve(indexName, lastException::set), is(equalTo(Optional.empty())));
            assertThat(lastException.get(), both(is(instanceOf(org.elasticsearch.client.ResponseException.class))).and(
                    hasToString(containsString("403 Forbidden"))));

            System.err.format("HANDLED: %s\n", lastException.get());
        });
    }

    private void withWiremockElasticsearch(final Consumer<RestClient> handler) throws Exception{
        final URL wiremockElasticsearch = new URL("http", "127.0.0.1", wireMock.getRuntimeInfo().getHttpPort(),"/");
        try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singletonList(wiremockElasticsearch)).build()) {
            handler.accept(restClient);
        }
    }

    private void withWiremockBackedResolver(final Consumer<ElasticsearchIndexNameToPipelineNameResolver> resolverConsumer) throws Exception {
        withWiremockElasticsearch((restClient -> {
            resolverConsumer.accept(new ElasticsearchIndexNameToPipelineNameResolver(restClient));
        }));
    }

    static String getMockResponseBody(final String name) {
        return readResource(ElasticsearchRestClientWireMockTest.class, Path.of("elasticsearch-mock-responses",name).toString());
    }
}