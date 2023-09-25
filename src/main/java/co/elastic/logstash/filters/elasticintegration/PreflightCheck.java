/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class PreflightCheck {
    private final RestClient elasticsearchRestClient;
    private final Logger logger;

    private static final Set<String> SUPPORTED_LICENSE_TYPES = Set.of("enterprise", "trial");
    private static final Logger LOGGER = LogManager.getLogger(PreflightCheck.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String,String> REQUIRED_CLUSTER_PRIVILEGES;
    static {
        final Map<String,String> prv = new LinkedHashMap<>();
        prv.put("monitor", "validate Elasticsearch license");
        prv.put("read_pipeline", "retrieve Elasticsearch ingest pipeline definitions");
        prv.put("manage_index_templates", "resolve a data stream name to its default pipeline");
        REQUIRED_CLUSTER_PRIVILEGES = Collections.unmodifiableMap(prv);
    }

    public PreflightCheck(final RestClient elasticsearchRestClient) {
        this(LOGGER, elasticsearchRestClient);
    }

    PreflightCheck(final Logger logger,
                   final RestClient elasticsearchRestClient) {
        this.logger = logger;
        this.elasticsearchRestClient = elasticsearchRestClient;
    }

    public void checkUserPrivileges() {
        try {
            final Request hasPrivilegesRequest = new Request("POST", "/_security/user/_has_privileges");
            hasPrivilegesRequest.setJsonEntity(OBJECT_MAPPER.writeValueAsString(Map.of("cluster", REQUIRED_CLUSTER_PRIVILEGES.keySet())));
            final Response hasPrivilegesResponse = elasticsearchRestClient.performRequest(hasPrivilegesRequest);

            final String responseBody = new String(hasPrivilegesResponse.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);

            final JsonNode hasPrivilegesRootNode = OBJECT_MAPPER.readTree(responseBody);
            final Map<String, Boolean> clusterPrivileges = OBJECT_MAPPER.convertValue(hasPrivilegesRootNode.path("cluster"), new TypeReference<Map<String, Boolean>>() {});

            for (Map.Entry<String, String> requiredPrivilegeAndReason : REQUIRED_CLUSTER_PRIVILEGES.entrySet()) {
                final String requiredPrivilege = requiredPrivilegeAndReason.getKey();
                if (!clusterPrivileges.get(requiredPrivilege)) {
                    logger.debug(() -> String.format("missing required privilege `%s`: %s", requiredPrivilege, responseBody));
                    throw new Failure(String.format("The cluster privilege `%s` is REQUIRED in order to %s", requiredPrivilege, requiredPrivilegeAndReason.getValue()));
                }
            }
            logger.debug(() -> String.format("has all required privileges: %s", responseBody));
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            throw new Failure(String.format("Preflight check failed: %s", e.getMessage()), e);
        }
    }

    public void checkLicense() {
        try {
            final Request licenseRequest = new Request("GET", "/_license");
            final Response licenseResponse = elasticsearchRestClient.performRequest(licenseRequest);

            final String responseBody = new String(licenseResponse.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);

            final JsonNode licenseNode = OBJECT_MAPPER.readTree(responseBody).get("license");

            final String licenseStatus = licenseNode.get("status").asText();
            final String licenseType = licenseNode.get("type").asText();


            logger.debug(() -> String.format("Elasticsearch license RAW: %s", responseBody));
            if (!SUPPORTED_LICENSE_TYPES.contains(licenseType)) {
                logger.warn(String.format("Elasticsearch license.type is `%s`", licenseType));
            } else if (!Objects.equals(licenseStatus, "active")) {
                logger.warn(String.format("Elasticsearch license.status is `%s`", licenseStatus));
            } else {
                logger.info(String.format("Elasticsearch license OK (%s %s)", licenseStatus, licenseType));
            }
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            logger.error(String.format("Exception checking license: %s", e.getMessage()));
            throw new Failure(String.format("Preflight check failed: %s", e.getMessage()), e);
        }
    }

    public boolean isServerless() {
        try {
            final Request req = new Request("GET", "/");
            final Response res = elasticsearchRestClient.performRequest(req);

            final String resBody = new String(res.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            logger.debug(() -> String.format("Elasticsearch '/' RAW: %s", resBody));

            final JsonNode versionNode = OBJECT_MAPPER.readTree(resBody).get("version");

            final String buildFlavor = versionNode.get("build_flavor").asText();
            logger.info(String.format("Elasticsearch build_flavor: %s", buildFlavor));

            return buildFlavor.equals("serverless");
        } catch (Exception e) {
            logger.error(String.format("Exception checking serverless: %s", e.getMessage()));
            throw new Failure(String.format("Preflight check failed: %s", e.getMessage()), e);
        }
    }
    
    public static class Failure extends RuntimeException {
        public Failure(String message, Throwable cause) {
            super(message, cause);
        }

        public Failure(String message) {
            super(message);
        }
    }

}
