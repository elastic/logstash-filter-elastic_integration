/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.logstash.plugins.BasicEventFactory;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.readResource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class DatastreamEventToPipelineNameResolverTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @Test
    void testNoDatastreamResolvedFromEvent() throws Exception {
        withDatastreamResolver((resolver) -> {
            final Event eventWithoutAnyDatastreamFields = BasicEventFactory.INSTANCE.newEvent();
            assertThat(resolver.resolve(eventWithoutAnyDatastreamFields), is(equalTo(Optional.empty())));

            final Event eventMissingDatastreamNamespace = datastreamConfigEvent("logs", "custom", null);
            assertThat(resolver.resolve(eventMissingDatastreamNamespace), is(equalTo(Optional.empty())));

            final Event eventMissingDatastreamDataset = datastreamConfigEvent("logs", null, "default");
            assertThat(resolver.resolve(eventMissingDatastreamDataset), is(equalTo(Optional.empty())));

            final Event eventMissingDatastreamType = datastreamConfigEvent(null, "custom", "default");
            assertThat(resolver.resolve(eventMissingDatastreamType), is(equalTo(Optional.empty())));
        });
    }

    @Test void datastreamResolvedFromEventNamedPipeline() throws Exception {
        withDatastreamResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate/logs-elastic_agent.metricbeat-default")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-with-template-settings-index-default_pipeline=(banana).json"))));

            final Event eventWithDatastreamFields = datastreamConfigEvent("logs", "elastic_agent.metricbeat", "default");
            assertThat(resolver.resolve(eventWithDatastreamFields), is(equalTo(Optional.of("banana"))));

        });
    }

    @Test void datastreamResolvedFromEventExplicitNonePipeline() throws Exception {
        withDatastreamResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate/logs-none_pipeline-default")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-with-template-settings-index-default_pipeline=(_none).json"))));

            final Event eventWithDatastreamFields = datastreamConfigEvent("logs", "none_pipeline", "default");
            assertThat(resolver.resolve(eventWithDatastreamFields), is(equalTo(Optional.of("_none"))));
        });
    }

    @Test void datastreamResolvedFromEventImplicitNonePipeline() throws Exception {
        withDatastreamResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate/logs-no_pipeline-default")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-without-template-settings-index-default_pipeline.json"))));

            final Event eventWithDatastreamFields = datastreamConfigEvent("logs", "no_pipeline", "default");
            assertThat(resolver.resolve(eventWithDatastreamFields), is(equalTo(Optional.empty())));

        });
    }

    @Test void datastreamResolvedFromEventNoTemplateSettings() throws Exception {
        withDatastreamResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate/logs-ta-sh")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-without-template-settings.json"))));

            final Event eventWithDatastreamFields = datastreamConfigEvent("logs", "ta", "sh");
            assertThat(resolver.resolve(eventWithDatastreamFields), is(equalTo(Optional.empty())));
        });
    }

    @Test void datastreamResolvedFromEventWithEscapableCharacters() throws Exception {
        withDatastreamResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate/metrics-this%2Fthat%20and%23another%3Fone-custom")
                    .willReturn(okJson(getMockResponseBody("post-simulate-index-with-template-settings-index-default_pipeline=(banana).json"))));

            final AtomicReference<Exception> lastException = new AtomicReference<>();
            final Event eventWithDatastreamFields = datastreamConfigEvent("metrics", "this/that and#another?one", "custom");
            assertThat(lastException.get(), is(nullValue()));
            assertThat(resolver.resolve(eventWithDatastreamFields, lastException::set), is(equalTo(Optional.of("banana"))));

        });
    }

    @Test void insufficientPermissionsToUseSimulateAPI() throws Exception {
        withDatastreamResolver((resolver) -> {
            wireMock.stubFor(post("/_index_template/_simulate/logs-some_pipeline-default")
                    .willReturn(aResponse().withStatus(403)));

            final AtomicReference<Exception> lastException = new AtomicReference<>();
            final Event eventWithDatastreamFields = datastreamConfigEvent("logs", "some_pipeline", "default");
            assertThat(resolver.resolve(eventWithDatastreamFields, lastException::set), is(equalTo(Optional.empty())));
            assertThat(lastException.get(), both(is(instanceOf(org.elasticsearch.client.ResponseException.class))).and(
                                                 hasToString(containsString("403 Forbidden"))));

            System.err.format("HANDLED: %s\n", lastException.get());
        });
    }

    private Event datastreamConfigEvent(final String type, final String dataset, final String namespace) {
        final Event intermediateEvent = BasicEventFactory.INSTANCE.newEvent();

        if (Objects.nonNull(type)) { intermediateEvent.setField("[data_stream][type]", type); }
        if (Objects.nonNull(dataset)) { intermediateEvent.setField("[data_stream][dataset]", dataset); }
        if (Objects.nonNull(namespace)) { intermediateEvent.setField("[data_stream][namespace]", namespace); }

        return intermediateEvent;
    }

    private void withWiremockElasticsearch(final Consumer<RestClient> handler) throws Exception{
        final URL wiremockElasticsearch = new URL("http", "127.0.0.1", wireMock.getRuntimeInfo().getHttpPort(),"/");
        try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singletonList(wiremockElasticsearch)).build()) {
            handler.accept(restClient);
        }
    }

    private void withDatastreamResolver(final Consumer<DatastreamEventToPipelineNameResolver> handler) throws Exception {
        withWiremockElasticsearch((restClient) -> {
            handler.accept(new DatastreamEventToPipelineNameResolver(restClient));
        });
    }

    static String getMockResponseBody(final String name) {
        return readResource(ElasticsearchRestClientWireMockTest.class, Path.of("elasticsearch-mock-responses",name).toString());
    }

}