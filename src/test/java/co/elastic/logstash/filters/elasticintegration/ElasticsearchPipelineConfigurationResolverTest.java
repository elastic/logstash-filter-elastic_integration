package co.elastic.logstash.filters.elasticintegration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.ingest.PipelineConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.readResource;
import static com.github.seregamorph.hamcrest.OptionalMatchers.isEmpty;
import static com.github.seregamorph.hamcrest.OptionalMatchers.isPresent;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ElasticsearchPipelineConfigurationResolverTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final Map<String,Object> EXPECTED_MY_PIPELINE_ID_CONFIG_MAP = Map.of(
            "description", "describe pipeline",
            "version", 123,
            "processors", List.of(
                    Map.of("set", Map.of(
                            "field","foo",
                            "value","bar")
                    )
            )
    );

    @Test
    void testLoadConfigurationExists() throws Exception {
        withPipelineConfigurationResolver((resolver) -> {
            wireMock.stubFor(get("/_ingest/pipeline/my-pipeline-id")
                    .willReturn(okJson(getMockResponseBody("get-ingest-pipeline-(my-pipeline-id).json"))));

            final AtomicReference<Exception> lastException = new AtomicReference<>();
            final Optional<PipelineConfiguration> resolvedPipelineConfiguration = resolver.resolve("my-pipeline-id", lastException::set);
            assertThat(lastException.get(), is(nullValue()));
            assertThat(resolvedPipelineConfiguration, isPresent());
            resolvedPipelineConfiguration.ifPresent(pipelineConfiguration -> {
                assertThat(pipelineConfiguration.getId(), is(equalTo("my-pipeline-id")));
                final Map<String, Object> configAsMap = pipelineConfiguration.getConfigAsMap();
                assertThat(configAsMap, is(equalTo(EXPECTED_MY_PIPELINE_ID_CONFIG_MAP)));
            });
        });
    }

    @Test
    void testLoadConfigurationPipelineWithSpecialCharacters() throws Exception {
        withPipelineConfigurationResolver((resolver) -> {
            wireMock.stubFor(get("/_ingest/pipeline/special%20char%20pipeline")
                    .willReturn(okJson(getMockResponseBody("get-ingest-pipeline-(special char pipeline).json"))));

            final AtomicReference<Exception> lastException = new AtomicReference<>();
            final Optional<PipelineConfiguration> resolvedPipelineConfiguration = resolver.resolve("special char pipeline", lastException::set);
            assertThat(lastException.get(), is(nullValue()));
            assertThat(resolvedPipelineConfiguration, isPresent());
            resolvedPipelineConfiguration.ifPresent(pipelineConfiguration -> {
                assertThat(pipelineConfiguration.getId(), is(equalTo("special char pipeline")));
                final Map<String, Object> configAsMap = pipelineConfiguration.getConfigAsMap();
                assertThat(configAsMap, is(equalTo(EXPECTED_MY_PIPELINE_ID_CONFIG_MAP)));
            });
        });
    }

    @Test
    void testLoadConfigurationNotFound() throws Exception {
        withPipelineConfigurationResolver((resolver) -> {
            wireMock.stubFor(get("/_ingest/pipeline/where-are-you")
                    .willReturn(aResponse().withStatus(404)));

            final AtomicReference<Exception> lastException = new AtomicReference<>();
            final Optional<PipelineConfiguration> resolvedPipelineConfiguration = resolver.resolve("where-are-you", lastException::set);
            assertThat(lastException.get(), is(nullValue())); // not found is not an exception
            assertThat(resolvedPipelineConfiguration, isEmpty());
        });
    }

    @Test
    void testLoadConfigurationNotAuthorized() throws Exception {
        withPipelineConfigurationResolver((resolver) -> {
            wireMock.stubFor(get("/_ingest/pipeline/who-am-i")
                    .willReturn(aResponse().withStatus(403)));

            final AtomicReference<Exception> lastException = new AtomicReference<>();
            final Optional<PipelineConfiguration> resolvedPipelineConfiguration = resolver.resolve("who-am-i", lastException::set);
            assertThat(lastException.get(), both(is(instanceOf(org.elasticsearch.client.ResponseException.class))).and(
                                                 hasToString(containsString("403 Forbidden")))
            );
            assertThat(resolvedPipelineConfiguration, isEmpty());
        });
    }


    private void withWiremockElasticsearch(final Consumer<RestClient> handler) throws Exception{
        final URL wiremockElasticsearch = new URL("http", "127.0.0.1", wireMock.getRuntimeInfo().getHttpPort(),"/");
        try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singletonList(wiremockElasticsearch)).build()) {
            handler.accept(restClient);
        }
    }

    private void withPipelineConfigurationResolver(final Consumer<ElasticsearchPipelineConfigurationResolver> handler) throws Exception {
        withWiremockElasticsearch((restClient) -> {
            handler.accept(new ElasticsearchPipelineConfigurationResolver(restClient));
        });
    }

    static String getMockResponseBody(final String name) {
        return readResource(ElasticsearchRestClientWireMockTest.class, Path.of("elasticsearch-mock-responses",name).toString());
    }
//
//    static <T,R> void assertThat(T actual, Function<T, R> transform, Matcher<? super R> matcher) {
//        org.hamcrest.MatcherAssert.assertThat(transform.apply(actual), matcher);
//    }
//
//    static <T> void assertThat(T actual, Matcher<? super T> matcher) {
//        assertThat(actual, Function.identity(), matcher);
//    }
}