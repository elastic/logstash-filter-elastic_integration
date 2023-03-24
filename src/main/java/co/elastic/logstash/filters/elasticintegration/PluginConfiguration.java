package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Password;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link PluginConfiguration} is an immutable view of the subset of a plugin's configuration
 * that is needed by the Java internals of this plugin, as-provided and without any further validation.
 * We expect configuration to be pre-validated by the ruby plugin, and for fields to be {@code null}
 * when they are semantically meaningless.
 */
final class PluginConfiguration {
    private final String       id;

    // elasticsearch-source: connection target
    private final String       cloudId;
    private final List<String> hosts;
    private final Boolean      sslEnabled;

    // elasticsearch-source: ssl connection trust config
    private final String       sslVerificationMode;
    private final String       sslTruststorePath;
    private final Password     sslTruststorePassword;
    private final List<String> sslCertificateAuthorities;

    // elasticsearch-source: ssl connection identity config
    private final String       sslKeystorePath;
    private final Password     sslKeystorePassword;
    private final String       sslCertificate;
    private final String       sslKey;
    private final Password     sslKeyPassphrase;

    // elasticsearch-source: request auth schemes
    private final String       authBasicUsername;
    private final Password     authBasicPassword;
    private final Password     cloudAuth;
    private final Password     apiKey;


    private PluginConfiguration(final Builder builder) {
        this.id = builder.id;
        // elasticsearch: routing
        this.cloudId = builder.cloudId;
        this.hosts = copyOfNullableList(builder.hosts);
        this.sslEnabled = builder.sslEnabled;
        // elasticsearch: ssl trust
        this.sslVerificationMode = builder.sslVerificationMode;
        this.sslTruststorePath = builder.sslTruststorePath;
        this.sslTruststorePassword = builder.sslTruststorePassword;
        this.sslCertificateAuthorities = copyOfNullableList(builder.sslCertificateAuthorities);
        // elasticsearch: ssl identity
        this.sslKeystorePath = builder.sslKeystorePath;
        this.sslKeystorePassword = builder.sslKeystorePassword;
        this.sslCertificate = builder.sslCertificate;
        this.sslKey = builder.sslKey;
        this.sslKeyPassphrase = builder.sslKeyPassphrase;
        // elasticsearch: request auth
        this.authBasicUsername = builder.authBasicUsername;
        this.authBasicPassword = builder.authBasicPassword;
        this.cloudAuth = builder.cloudAuth;
        this.apiKey = builder.apiKey;
    }

    private static <T> List<T> copyOfNullableList(final List<T> source) {
        if (Objects.isNull(source)) { return null; }

        return List.copyOf(source);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    public Optional<String> cloudId() {
        return Optional.ofNullable(cloudId);
    }

    public Optional<List<URL>> hosts() {
        return Optional.ofNullable(hosts)
                .map(hosts -> hosts.stream().map(PluginConfiguration::uncheckedParseURL).toList());
    }

    public Optional<Boolean> sslEnabled() {
        return Optional.ofNullable(sslEnabled);
    }

    public Optional<String> sslVerificationMode() {
        return Optional.ofNullable(sslVerificationMode);
    }

    public Optional<Path> truststore() {
        return Optional.ofNullable(sslTruststorePath).map(Paths::get);
    }

    public Optional<Password> truststorePassword() {
        return Optional.ofNullable(sslTruststorePassword);
    }

    public Optional<List<Path>> sslCertificateAuthorities() {
        return Optional.ofNullable(sslCertificateAuthorities)
                .map(cas -> cas.stream().map(Paths::get).toList());
    }

    public Optional<Path> keystore() {
        return Optional.ofNullable(sslKeystorePath).map(Paths::get);
    }

    public Optional<Password> keystorePassword() {
        return Optional.ofNullable(sslKeystorePassword);
    }

    public Optional<Path> sslCertificate() {
        return Optional.ofNullable(sslCertificate).map(Paths::get);
    }

    public Optional<Path> sslKey() {
        return Optional.ofNullable(sslKey).map(Paths::get);
    }

    public Optional<Password> sslKeyPassphrase() {
        return Optional.ofNullable(sslKeyPassphrase);
    }

    public Optional<String> authBasicUsername() {
        return Optional.ofNullable(authBasicUsername);
    }

    public Optional<Password> authBasicPassword() {
        return Optional.ofNullable(authBasicPassword);
    }

    public Optional<Password> cloudAuth() {
        return Optional.ofNullable(cloudAuth);
    }

    public Optional<Password> apiKey() {
        return Optional.ofNullable(apiKey);
    }

    @Override
    public String toString() {
        final List<String> config = new ArrayList<>();
        if (Objects.nonNull(id)) { config.add(String.format("id=%s", id)); }
        if (Objects.nonNull(cloudId)) { config.add(String.format("cloudId=%s", cloudId)); }
        if (Objects.nonNull(hosts)) { config.add(String.format("hosts=%s", hosts)); }
        if (Objects.nonNull(sslEnabled)) { config.add(String.format("sslEnabled=%s", sslEnabled)); }
        if (Objects.nonNull(sslVerificationMode)) { config.add(String.format("sslVerificationMode=%s", sslVerificationMode)); }
        if (Objects.nonNull(sslTruststorePath)) { config.add(String.format("sslTruststorePath=%s", sslTruststorePath)); }
        if (Objects.nonNull(sslTruststorePassword)) { config.add(String.format("sslTruststorePassword=%s", sslTruststorePassword)); }
        if (Objects.nonNull(sslCertificateAuthorities)) { config.add(String.format("sslCertificateAuthorities=%s", sslCertificateAuthorities)); }
        if (Objects.nonNull(sslKeystorePath)) { config.add(String.format("sslKeystorePath=%s", sslKeystorePath)); }
        if (Objects.nonNull(sslKeystorePassword)) { config.add(String.format("sslKeystorePassword=%s", sslKeystorePassword)); }
        if (Objects.nonNull(sslCertificate)) { config.add(String.format("sslCertificate=%s", sslCertificate)); }
        if (Objects.nonNull(sslKey)) { config.add(String.format("sslKey=%s", sslKey)); }
        if (Objects.nonNull(sslKeyPassphrase)) { config.add(String.format("sslKeyPassphrase=%s", sslKeyPassphrase)); }
        if (Objects.nonNull(authBasicUsername)) { config.add(String.format("authBasicUsername=%s", authBasicUsername)); }
        if (Objects.nonNull(authBasicPassword)) { config.add(String.format("authBasicPassword=%s", authBasicPassword)); }
        if (Objects.nonNull(cloudAuth)) { config.add(String.format("cloudAuth=%s", cloudAuth)); }
        if (Objects.nonNull(apiKey)) { config.add(String.format("sslKeyPassphrase=%s", apiKey)); }

        return String.format("PluginConfiguration{%s}", String.join(", ", config));
    }

    static private URL uncheckedParseURL(final String urlSpec) {
        try {
            return new URL(urlSpec);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    public static class Builder {
        String id;
        String cloudId;
        List<String> hosts;
        Boolean sslEnabled;
        String sslVerificationMode;
        String sslTruststorePath;
        Password sslTruststorePassword;
        List<String> sslCertificateAuthorities;
        String sslKeystorePath;
        Password sslKeystorePassword;
        String sslCertificate;
        String sslKey;
        Password sslKeyPassphrase;
        String authBasicUsername;
        Password authBasicPassword;
        Password cloudAuth;
        Password apiKey;

        public PluginConfiguration build() {
            return new PluginConfiguration(this);
        }

        public Builder setId(final String id) {
            this.id = id;
            return this;
        }

        public Builder setCloudId(final String cloudId) {
            this.cloudId = cloudId;
            return this;
        }

        public Builder setHosts(final List<String> hosts) {
            if (Objects.nonNull(hosts)) {
                this.hosts = List.copyOf(hosts);
            }
            return this;
        }

        public Builder setSslEnabled(final Boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
            return this;
        }

        public Builder setSslVerificationMode(final String sslVerificationMode) {
            this.sslVerificationMode = sslVerificationMode;
            return this;
        }

        public Builder setSslTruststorePath(final String sslTruststorePath) {
            this.sslTruststorePath = sslTruststorePath;
            return this;
        }

        public Builder setSslTruststorePassword(final Password sslTruststorePassword) {
            this.sslTruststorePassword = sslTruststorePassword;
            return this;
        }

        public Builder setSslCertificateAuthorities(final List<String> sslCertificateAuthorities) {
            if (Objects.nonNull(sslCertificateAuthorities)) {
                this.sslCertificateAuthorities = List.copyOf(sslCertificateAuthorities);
            }
            return this;
        }

        public Builder setSslKeystorePath(final String sslKeystorePath) {
            this.sslKeystorePath = sslKeystorePath;
            return this;
        }

        public Builder setSslKeystorePassword(final Password sslKeystorePassword) {
            this.sslKeystorePassword = sslKeystorePassword;
            return this;
        }

        public Builder setSslCertificate(final String sslCertificate) {
            this.sslCertificate = sslCertificate;
            return this;
        }

        public Builder setSslKey(final String sslKey) {
            this.sslKey = sslKey;
            return this;
        }

        public Builder setSslKeyPassphrase(final Password sslKeyPassphrase) {
            this.sslKeyPassphrase = sslKeyPassphrase;
            return this;
        }

        public Builder setAuthBasicUsername(final String authBasicUsername) {
            this.authBasicUsername = authBasicUsername;
            return this;
        }

        public Builder setAuthBasicPassword(final Password authBasicPassword) {
            this.authBasicPassword = authBasicPassword;
            return this;
        }

        public Builder setCloudAuth(final Password cloudAuth) {
            this.cloudAuth = cloudAuth;
            return this;
        }

        public Builder setApiKey(final Password apiKey) {
            this.apiKey = apiKey;
            return this;
        }
    }
}
