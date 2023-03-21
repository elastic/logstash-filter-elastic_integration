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

public class PreflightCheck {
    private final RestClient elasticsearchRestClient;

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

    public PreflightCheck(RestClient elasticsearchRestClient) {
        this.elasticsearchRestClient = elasticsearchRestClient;
    }

    public void check() {
        checkCredentialsPrivileges();
        checkLicense();
    }

    void checkCredentialsPrivileges(){
        try {
            final Request hasPrivilegesRequest = new Request("POST", "/_security/user/_has_privileges");
            hasPrivilegesRequest.setJsonEntity(OBJECT_MAPPER.writeValueAsString(Map.of("cluster", REQUIRED_CLUSTER_PRIVILEGES.keySet())));
            final Response hasPrivilegesResponse = elasticsearchRestClient.performRequest(hasPrivilegesRequest);

            final String responseBody = new String(hasPrivilegesResponse.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);

            final JsonNode hasPrivilegesRootNode = OBJECT_MAPPER.readTree(responseBody);
            final Map<String, Boolean> clusterPrivileges = OBJECT_MAPPER.convertValue(hasPrivilegesRootNode.path("cluster"), new TypeReference<Map<String, Boolean>>() {
            });

            for (Map.Entry<String, String> requiredPrivilegeAndReason : REQUIRED_CLUSTER_PRIVILEGES.entrySet()) {
                final String requiredPrivilege = requiredPrivilegeAndReason.getKey();
                if (!clusterPrivileges.get(requiredPrivilege)) {
                    LOGGER.debug(() -> String.format("missing required privilege `%s`: %s", requiredPrivilege, responseBody));
                    throw new Failure(String.format("The cluster privilege `%s` is REQUIRED in order to %s", requiredPrivilege, requiredPrivilegeAndReason.getValue()));
                }
            }
            LOGGER.debug(() -> String.format("has all required privileges: %s", responseBody));
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            LOGGER.error(() -> String.format("Exception checking has_privileges: %s", e.getMessage()));
            throw new Failure(String.format("Preflight check failed: %s", e.getMessage()), e);
        }
    }

    void checkLicense() {
        try {
            final Request licenseRequest = new Request("GET", "/_license");
            final Response licenseResponse = elasticsearchRestClient.performRequest(licenseRequest);

            final String responseBody = new String(licenseResponse.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);

            final JsonNode licenseNode = OBJECT_MAPPER.readTree(responseBody).get("license");

            final String licenseStatus = licenseNode.get("status").asText();
            if (!Objects.equals(licenseStatus, "active")) {
                LOGGER.debug(() -> String.format("license status=(`%s`): %s", licenseStatus, responseBody));
                throw new Failure(String.format("Use of the Elastic Integration filter for Logstash requires an active license, got `%s`", licenseStatus));
            }

            final String licenseType = licenseNode.get("type").asText();
            if (!Objects.equals(licenseType, "enterprise")) {
                LOGGER.debug(() -> String.format("license type=(`%s`): %s", licenseType, responseBody));
                throw new Failure(String.format("Use of the Elastic Integration filter for Logstash requires an enterprise license, got `%s`", licenseType));
            }

            LOGGER.debug(() -> String.format("license ok (`%s`): %s", licenseStatus, responseBody));
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            LOGGER.error(() -> String.format("Exception checking license: %s", e.getMessage()));
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
