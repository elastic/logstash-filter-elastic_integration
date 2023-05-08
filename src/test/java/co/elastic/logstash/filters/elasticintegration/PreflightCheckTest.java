/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.readResource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

class PreflightCheckTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig()
                    .dynamicPort()
                    .extensions(new ResponseTemplateTransformer(false))).build();

    @Test
    void checkCredentialsPrivilegesOK() throws Exception {
        wireMock.stubFor(
                post("/_security/user/_has_privileges")
                        .withRequestBody(equalToJson(getBodyFixture("has_privileges.request.json"), true, true))
                        .willReturn(okJson(getBodyFixture("has_privileges.ok.json"))));
        withWiremockElasticsearch((restClient -> {
            new PreflightCheck(restClient).checkUserPrivileges();
        }));
    }

    @Test
    void checkCredentialsPrivilegesMissingMonitor() throws Exception {
        wireMock.stubFor(
                post("/_security/user/_has_privileges")
                        .withRequestBody(equalToJson(getBodyFixture("has_privileges.request.json"), true, true))
                        .willReturn(okJson(getBodyFixture("has_privileges.missing-monitor.json"))));
        withWiremockElasticsearch((restClient -> {
            final PreflightCheck.Failure failure = assertThrows(PreflightCheck.Failure.class, () -> {
                new PreflightCheck(restClient).checkUserPrivileges();
            });
            assertThat(failure.getMessage(), hasToString(stringContainsInOrder("cluster privilege `monitor` is REQUIRED", "validate Elasticsearch license")));
        }));
    }

    @Test
    void checkCredentialsPrivilegesMissingReadPipeline() throws Exception {
        wireMock.stubFor(
                post("/_security/user/_has_privileges")
                        .withRequestBody(equalToJson(getBodyFixture("has_privileges.request.json"), true, true))
                        .willReturn(okJson(getBodyFixture("has_privileges.missing-read_pipeline.json"))));
        withWiremockElasticsearch((restClient -> {
            final PreflightCheck.Failure failure = assertThrows(PreflightCheck.Failure.class, () -> {
                new PreflightCheck(restClient).checkUserPrivileges();
            });
            assertThat(failure.getMessage(), hasToString(stringContainsInOrder("cluster privilege `read_pipeline` is REQUIRED", "retrieve Elasticsearch ingest pipeline definitions")));
        }));
    }



    @Test
    void checkCredentialsPrivilegesMissingManageIndexTemplates() throws Exception {
        wireMock.stubFor(
                post("/_security/user/_has_privileges")
                        .withRequestBody(equalToJson(getBodyFixture("has_privileges.request.json"), true, true))
                        .willReturn(okJson(getBodyFixture("has_privileges.missing-manage_index_templates.json"))));
        withWiremockElasticsearch((restClient -> {
            final PreflightCheck.Failure failure = assertThrows(PreflightCheck.Failure.class, () -> {
                new PreflightCheck(restClient).checkUserPrivileges();
            });
            assertThat(failure.getMessage(), hasToString(stringContainsInOrder("cluster privilege `manage_index_templates` is REQUIRED", "resolve a data stream name to its default pipeline")));
        }));
    }


    @Test
    void checkCredentialsPrivileges403Response() throws Exception {
        wireMock.stubFor(
                post("/_security/user/_has_privileges")
                        .withRequestBody(equalToJson(getBodyFixture("has_privileges.request.json"), true, true))
                        .willReturn(status(403)));
        withWiremockElasticsearch((restClient -> {
            final PreflightCheck.Failure failure = assertThrows(PreflightCheck.Failure.class, () -> {
                new PreflightCheck(restClient).checkUserPrivileges();
            });
            assertThat(failure.getMessage(), hasToString(stringContainsInOrder("Preflight check failed", "403 Forbidden")));
        }));
    }

    @Test
    void checkCredentialsPrivilegesConnectionError() throws Exception {
        wireMock.stubFor(
                post("/_security/user/_has_privileges")
                        .withRequestBody(equalToJson(getBodyFixture("has_privileges.request.json"), true, true))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        withWiremockElasticsearch((restClient -> {
            final PreflightCheck.Failure failure = assertThrows(PreflightCheck.Failure.class, () -> {
                new PreflightCheck(restClient).checkUserPrivileges();
            });
            assertThat(failure.getMessage(), hasToString(stringContainsInOrder("Preflight check failed", "Connection reset")));
        }));
    }

    @Test
    void checkLicenseActiveEnterprise() throws Exception {
        wireMock.stubFor(get("/_license")
                .willReturn(okJson(getBodyFixture("license.active-enterprise.json"))
                        .withTransformers("response-template")));
        withWiremockElasticsearch((restClient -> {
            final Logger logger = Mockito.mock(Logger.class);
            new PreflightCheck(logger, restClient).checkLicense();

            Mockito.verify(logger).info(argThat(containsString("Elasticsearch license OK")));
        }));
    }

    @Test
    void checkLicenseActiveBasic() throws Exception {
        wireMock.stubFor(get("/_license")
                .willReturn(okJson(getBodyFixture("license.active-basic.json"))
                        .withTransformers("response-template")));
        withWiremockElasticsearch((restClient -> {
            final Logger logger = Mockito.mock(Logger.class);
            new PreflightCheck(logger, restClient).checkLicense();

            Mockito.verify(logger).warn(argThat(containsString("Elasticsearch license.type is `basic`")));
        }));
    }

    @Test
    void checkLicenseInvalid() throws Exception {
        wireMock.stubFor(get("/_license")
                .willReturn(okJson(getBodyFixture("license.invalid.json"))
                        .withTransformers("response-template")));
        withWiremockElasticsearch((restClient -> {
            final Logger logger = Mockito.mock(Logger.class);
            new PreflightCheck(logger, restClient).checkLicense();

            Mockito.verify(logger).warn(argThat(containsString("Elasticsearch license.status is `invalid`")));
        }));
    }

    @Test
    void checkLicenseExpired() throws Exception {
        wireMock.stubFor(get("/_license")
                .willReturn(okJson(getBodyFixture("license.expired.json"))
                        .withTransformers("response-template")));
        withWiremockElasticsearch((restClient -> {
            final Logger logger = Mockito.mock(Logger.class);
            new PreflightCheck(logger, restClient).checkLicense();

            Mockito.verify(logger).warn(argThat(containsString("Elasticsearch license.status is `expired`")));
        }));
    }

    @Test
    void checkLicense403() throws Exception {
        wireMock.stubFor(get("/_license").willReturn(status(403)));
        withWiremockElasticsearch((restClient -> {
            final PreflightCheck.Failure failure = assertThrows(PreflightCheck.Failure.class, () -> {
                new PreflightCheck(restClient).checkLicense();
            });
            assertThat(failure.getMessage(), hasToString(stringContainsInOrder("Preflight check failed", "403 Forbidden")));
        }));
    }

    @Test
    void checkLicenseConnectionError() throws Exception {
        wireMock.stubFor(get("/_license")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        withWiremockElasticsearch((restClient -> {
            final PreflightCheck.Failure failure = assertThrows(PreflightCheck.Failure.class, () -> {
                new PreflightCheck(restClient).checkLicense();
            });
            assertThat(failure.getMessage(), hasToString(stringContainsInOrder("Preflight check failed", "Connection reset")));
        }));
    }

    private void withWiremockElasticsearch(final Consumer<RestClient> handler) throws Exception{
        final URL wiremockElasticsearch = new URL("http", "127.0.0.1", wireMock.getRuntimeInfo().getHttpPort(),"/");
        try (RestClient restClient = ElasticsearchRestClientBuilder.forURLs(Collections.singletonList(wiremockElasticsearch)).build()) {
            handler.accept(restClient);
        }
    }

    static String getBodyFixture(final String name) {
        return readResource(PreflightCheck.class, Path.of("preflight-check", name).toString());
    }
}