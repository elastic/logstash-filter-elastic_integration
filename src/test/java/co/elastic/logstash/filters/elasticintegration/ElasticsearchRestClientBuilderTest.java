/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import co.elastic.logstash.filters.elasticintegration.ElasticsearchRestClientBuilder.ElasticApiConfig;

import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

class ElasticsearchRestClientBuilderTest {

    private static final ElasticsearchRestClientBuilder.CloudIdRestClientBuilderFactory CLOUD_ID_REST_CLIENT_BUILDER_FACTORY = new ElasticsearchRestClientBuilder.CloudIdRestClientBuilderFactory() {
        @Override
        public RestClientBuilder getBuilder(String cloudId) {
            return RestClient.builder(cloudId);
        }
    };

    private static final ElasticsearchRestClientBuilder.HostsArrayRestClientBuilderFactory HOSTS_ARRAY_REST_CLIENT_BUILDER_FACTORY = new ElasticsearchRestClientBuilder.HostsArrayRestClientBuilderFactory() {
        @Override
        public RestClientBuilder getBuilder(HttpHost... hosts) {
            return RestClient.builder(hosts);
        }
    };

    @Test
    public void testForCloudIdFactory() {
        final ElasticsearchRestClientBuilder.CloudIdRestClientBuilderFactory cloudIdRestClientBuilderFactory = spy(CLOUD_ID_REST_CLIENT_BUILDER_FACTORY);
        final String cloudId = "different-es-kb-port:dXMtY2VudHJhbDEuZ2NwLmNsb3VkLmVzLmlvJGFjMzFlYmI5MDI0MTc3MzE1NzA0M2MzNGZkMjZmZDQ2OjkyNDMkYTRjMDYyMzBlNDhjOGZjZTdiZTg4YTA3NGEzYmIzZTA6OTI0NA==";
        try (RestClient restClient = ElasticsearchRestClientBuilder.forCloudId(cloudId, cloudIdRestClientBuilderFactory).build()) {
            verify(cloudIdRestClientBuilderFactory).getBuilder(cloudId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testForHostsFactorySingleURL() {
        final Collection<URL> inputUrls = Collections.singleton(parseURL("https://169.0.0.254:9201/mypath"));
        final HttpHost[] expectedTransformation = {
                new HttpHost("169.0.0.254", 9201, "https"),
        };

        validateTranslationToClientBuilderFactory(inputUrls, expectedTransformation);
    }

    @Test
    public void testForHostsFactoryMultipleURLs() {
        final Collection<URL> inputUrls = List.of(parseURL("https://169.0.0.254:9201/mypath"), parseURL("https://169.0.0.253:9903/mypath"));
        final HttpHost[] expectedTransformation = {
                new HttpHost("169.0.0.254", 9201, "https"),
                new HttpHost("169.0.0.253", 9903, "https"),
        };

        validateTranslationToClientBuilderFactory(inputUrls, expectedTransformation);
    }

    @Test
    public void testForHostsFactoryEmptyURLs() {
        final IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> {
            ElasticsearchRestClientBuilder.forURLs(List.of());
        });
        assertThat(illegalStateException.getMessage(), Matchers.containsString("urls must not be empty"));
    }

    @Test
    public void testForHostsFactoryURLsWithMismatchingProtocol() {
        final Collection<URL> inputUrls = List.of(parseURL("https://169.0.0.254:9201/mypath"), parseURL("http://169.0.0.253:9903/mypath"));

        final IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> {
            ElasticsearchRestClientBuilder.forURLs(inputUrls);
        });
        assertThat(illegalStateException.getMessage(), stringContainsInOrder("non-uniform(protocol):[", "]"));
    }

    @Test
    public void testForHostsFactoryURLsWithMismatchingPath() {
        final Collection<URL> inputUrls = List.of(parseURL("https://169.0.0.254:9201/mypath"), parseURL("https://169.0.0.253:9903/"));

        final IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> {
            ElasticsearchRestClientBuilder.forURLs(inputUrls);
        });
        assertThat(illegalStateException.getMessage(), stringContainsInOrder("non-uniform(path):[", "]"));
    }

    @Test
    public void testForHostsFactoryURLsWithoutPort() {
        final Collection<URL> inputUrls = List.of(parseURL("https://169.0.0.254/"));

        final IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> {
            ElasticsearchRestClientBuilder.forURLs(inputUrls);
        });
        assertThat(illegalStateException.getMessage(), containsString("URLS must include port specification"));
    }

    @Test
    public void testElasticApiConfigAddsHeaders() throws HttpException, IOException {
        ElasticApiConfig config = new ElasticApiConfig();
        config.setApiVersion("2023-10-31");

        ElasticsearchRestClientBuilder.HttpClientConfigurator mockConfigurator =
            mock(ElasticsearchRestClientBuilder.HttpClientConfigurator.class);

        try (MockedStatic<ElasticsearchRestClientBuilder.HttpClientConfigurator> mockedStatic =
                mockStatic(ElasticsearchRestClientBuilder.HttpClientConfigurator.class)) {
            mockedStatic.when(() ->
                ElasticsearchRestClientBuilder.HttpClientConfigurator.forAddInterceptorFirst(
                    any(HttpRequestInterceptor.class)))
                .thenReturn(mockConfigurator);

            HttpAsyncClientBuilder builder = HttpAsyncClientBuilder.create();
            config.configureHttpClient(builder);

            ArgumentCaptor<HttpRequestInterceptor> interceptorCaptor = forClass(HttpRequestInterceptor.class);
            mockedStatic.verify(() ->
                ElasticsearchRestClientBuilder.HttpClientConfigurator.forAddInterceptorFirst(
                    interceptorCaptor.capture()), times(2));
            verify(mockConfigurator, times(2)).configure(builder);

            // Process interceptors and look for product origin header
            List<HttpRequestInterceptor> interceptors = interceptorCaptor.getAllValues();
            HttpRequest request = new BasicHttpRequest("GET", "/");
            for (HttpRequestInterceptor interceptor : interceptors) {
                interceptor.process(request, null);
            }
            assertThat(request.getFirstHeader("Elastic-Api-Version").getValue(),
                    is("2023-10-31"));
            assertThat(request.getFirstHeader("x-elastic-product-origin").getValue(),
                    is("logstash-filter-elastic_integration"));
        }
    }

    private <T> void validateTranslationToClientBuilderFactory(final Collection<URL> inputUrls, final HttpHost[] expectedInputReceivedByBuilderFactory) {
        final ElasticsearchRestClientBuilder.HostsArrayRestClientBuilderFactory hostsArrayRestClientBuilderFactory = spy(HOSTS_ARRAY_REST_CLIENT_BUILDER_FACTORY);
        try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(inputUrls, hostsArrayRestClientBuilderFactory).build()) {
            verify(hostsArrayRestClientBuilderFactory).getBuilder(expectedInputReceivedByBuilderFactory);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private static URL parseURL(final String urlSpec) {
        try {
            return new URL(urlSpec);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}