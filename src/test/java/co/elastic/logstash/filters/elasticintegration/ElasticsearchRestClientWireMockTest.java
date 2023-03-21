package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Password;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;

import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ElasticsearchRestClientBuilder WireMock tests")
public class ElasticsearchRestClientWireMockTest {
    static class BareHttp {
        @RegisterExtension
        static WireMockExtension wireMock = WireMockExtension.newInstance()
                .options(wireMockConfig().dynamicPort())
                .build();

        @BeforeEach
        void setupMock() {
            wireMock.stubFor(get("/").willReturn(okJson(getMockResponseBody("get-root.json"))));
        }

        @Test void testBasicConnectivity() throws Exception {
            final URL wiremockElasticsearch = new URL("http", "127.0.0.1", wireMock.getRuntimeInfo().getHttpPort(),"/");

            try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch)).build()) {
                final Response response = restClient.performRequest(new Request("GET", "/"));
                assertThat(response.getStatusLine().getStatusCode(), is(Matchers.equalTo(200)));
            }
        }

        @Test void testBasicConnectivityHttpsToHttpEndpoint() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpPort(),"/");

            try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch)).build()) {
                final IOException exception = assertThrows(IOException.class, () -> restClient.performRequest(new Request("GET", "/")));
                assertThat(exception.getMessage(), stringContainsInOrder("Unrecognized SSL message", "plaintext"));
            }
        }
    }

    static class SimpleHttps {
        @RegisterExtension
        static WireMockExtension wireMock = WireMockExtension.newInstance()
                .options(wireMockConfig()
                        .dynamicHttpsPort().httpDisabled(true)
                        .keystorePath(generatedCertificateMaterial("server_from_root.p12").toString())
                        .keystorePassword("12345678")
                        .keystoreType("PKCS12").keyManagerPassword("12345678"))
                .build();

        @BeforeEach
        void setupMock() {
            wireMock.stubFor(get("/").willReturn(okJson(getMockResponseBody("get-root.json"))));
        }

        @Test void testBasicConnectivityWithoutConfiguringTrustFailure() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch)).build()) {
                final SSLHandshakeException ex = assertThrows(SSLHandshakeException.class, () -> restClient.performRequest(new Request("GET", "/")));
                assertThat(ex.getMessage(), stringContainsInOrder("PKIX path building failed", "unable to find valid certification path to requested target"));
            }
        }

        @Test void testBasicConnectivityDisablingVerification() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            final ElasticsearchRestClientBuilder rcb = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch));
            rcb.configureTrust(trustConfig -> trustConfig.setSSLVerificationMode("NONE"));

            try (RestClient restClient = rcb.build()) {
                final Response response = restClient.performRequest(new Request("GET", "/"));
                assertThat(response.getStatusLine().getStatusCode(), is(Matchers.equalTo(200)));
            }
        }

        @Test void testBasicConnectivityConfiguringTruststore() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            final ElasticsearchRestClientBuilder rcb = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch));
            rcb.configureTrust(trustConfig ->
                    trustConfig.setSSLVerificationMode("FULL")
                    .setTrustStore(generatedCertificateMaterial("ca.p12"), new Password("12345678")));

            try (RestClient restClient = rcb.build()) {
                final Response response = restClient.performRequest(new Request("GET", "/"));
                assertThat(response.getStatusLine().getStatusCode(), is(Matchers.equalTo(200)));
            }
        }

        @Test void testBasicConnectivityConfiguringCertificateAuthorities() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            final Path ca = generatedCertificateMaterial("root.crt");
            ensureSetFileReadable(ca.toFile(), true);
            ensureSetFileWritable(ca.toFile(), false);

            final ElasticsearchRestClientBuilder rcb = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch));
            rcb.configureTrust(trustConfig ->
                    trustConfig.setSSLVerificationMode("FULL")
                            .setCertificateAuthorities(Collections.singletonList(ca)));

            try (RestClient restClient = rcb.build()) {
                final Response response = restClient.performRequest(new Request("GET", "/"));
                assertThat(response.getStatusLine().getStatusCode(), is(Matchers.equalTo(200)));
            }
        }
    }


    static class MutualHttps {
        @RegisterExtension
        static WireMockExtension wireMock = WireMockExtension.newInstance()
                .options(wireMockConfig()
                        .dynamicHttpsPort().httpDisabled(true)
                        .keystorePath(generatedCertificateMaterial("server_from_root.p12").toString())
                        .keystorePassword("12345678").keyManagerPassword("12345678")
                        .keystoreType("PKCS12")
                        .trustStorePath(generatedCertificateMaterial("ca.p12").toString())
                        .trustStorePassword("12345678")
                        .trustStoreType("PKCS12")
                        .needClientAuth(true))
                .build();

        @BeforeEach
        void setupMock() {
            wireMock.stubFor(get("/").willReturn(okJson(getMockResponseBody("get-root.json"))));
        }

        @Test void testBasicConnectivityWithoutConfiguringTrustFailure() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch)).build()) {
                final SSLHandshakeException ex = assertThrows(SSLHandshakeException.class, () -> restClient.performRequest(new Request("GET", "/")));
                assertThat(ex.getMessage(), stringContainsInOrder("PKIX path building failed", "unable to find valid certification path to requested target"));
            }
        }

        @Test void testBasicConnectivityDisablingVerification() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            final ElasticsearchRestClientBuilder rcb = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch));
            rcb.configureTrust(trustConfig -> trustConfig.setSSLVerificationMode("NONE"));

            try (RestClient restClient = rcb.build()) {
                final SSLHandshakeException ex = assertThrows(SSLHandshakeException.class, () -> restClient.performRequest(new Request("GET", "/")));
                assertThat(ex.getMessage(), stringContainsInOrder("fatal", "bad_certificate"));
            }
        }

        @Test void testBasicConnectivityConfiguringKeystore() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            final Path keystore = generatedCertificateMaterial("client_from_root.p12");
            ensureSetFileReadable(keystore.toFile(), true);
            ensureSetFileWritable(keystore.toFile(), false);

            final ElasticsearchRestClientBuilder rcb = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch));
            rcb.configureTrust(trustConfig -> trustConfig.setSSLVerificationMode("NONE"))
               .configureIdentity(identityConfig -> identityConfig.setKeyStore(keystore, new Password("12345678")));

            try (RestClient restClient = rcb.build()) {
                final Response response = restClient.performRequest(new Request("GET", "/"));
                assertThat(response.getStatusLine().getStatusCode(), is(Matchers.equalTo(200)));
            }
        }


        @Test void testBasicConnectivityConfiguringCertificateKeyPair() throws Exception {
            final URL wiremockElasticsearch = new URL("https", "127.0.0.1", wireMock.getRuntimeInfo().getHttpsPort(),"/");

            final Path certificate = generatedCertificateMaterial("client_from_root.crt");
            ensureSetFileReadable(certificate.toFile(), true);
            ensureSetFileWritable(certificate.toFile(), false);

            final Path key = generatedCertificateMaterial("client_from_root.key.pkcs8");
            ensureSetFileReadable(key.toFile(), true);
            ensureSetFileWritable(key.toFile(), false);

            final ElasticsearchRestClientBuilder rcb = ElasticsearchRestClientBuilder.forURLs(Collections.singleton(wiremockElasticsearch));
            rcb.configureTrust(trustConfig -> trustConfig.setSSLVerificationMode("NONE"))
               .configureIdentity(identityConfig -> identityConfig.setCertificateKeyPair(certificate, key, new Password("12345678")));

            try (RestClient restClient = rcb.build()) {
                final Response response = restClient.performRequest(new Request("GET", "/"));
                assertThat(response.getStatusLine().getStatusCode(), is(Matchers.equalTo(200)));
            }
        }

    }

    static String getMockResponseBody(final String name) {
        return readResource(ElasticsearchRestClientWireMockTest.class, Path.of("elasticsearch-mock-responses",name).toString());
    }

    static Path generatedCertificateMaterial(final String name) {
        return getResourcePath(ElasticsearchRestClientWireMockTest.class, Path.of("ssl-test-certs","generated", name).toString()).orElseThrow();
    }
}
