/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Password;
import co.elastic.logstash.filters.elasticintegration.util.Exceptions;
import co.elastic.logstash.filters.elasticintegration.util.KeyStoreUtil;
import org.apache.http.*;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * The {@code ElasticsearchRestClientBuilder} can safely build an Elasticsearch {@link RestClient}
 * using commonly-available configuration from the Logstash ecosystem. An instance is meant to be
 * acquired via one of:
 *
 * <ul>
 *     <li>{@link ElasticsearchRestClientBuilder#forCloudId(String)}</li>
 *     <li>{@link ElasticsearchRestClientBuilder#forURLs(Collection)}</li>
 * </ul>
 *
 * By default, when connecting to cloud-id or https-hosts the resulting {@code RestClient} will:
 * <ul>
 *     <li>establish trust of the server it connects to by fully-validating server-presented certificates using
 *         the system truststore (override with {@link ElasticsearchRestClientBuilder#configureTrust(Consumer)});</li>
 *     <li>present no proof-of-identity (override with {@link ElasticsearchRestClientBuilder#configureIdentity(Consumer)})</li>
 *     <li>present no request auth credentials (override with {@link ElasticsearchRestClientBuilder#configureRequestAuth(Consumer)})</li>
 * </ul>
 *
 */
public class ElasticsearchRestClientBuilder {
    private final Supplier<RestClientBuilder> restClientBuilderSupplier;
    private final TrustConfig trustConfig = new TrustConfig();
    private final IdentityConfig identityConfig = new IdentityConfig();
    private final RequestAuthConfig requestAuthConfig = new RequestAuthConfig();
    private final ElasticApiConfig elasticApiConfig = new ElasticApiConfig();

    public static ElasticsearchRestClientBuilder forCloudId(final String cloudId) {
        return ElasticsearchRestClientBuilder.forCloudId(cloudId, CloudIdRestClientBuilderFactory.DEFAULT);
    }

    static ElasticsearchRestClientBuilder forCloudId(final String cloudId, CloudIdRestClientBuilderFactory factory) {
        return new ElasticsearchRestClientBuilder(() -> factory.getBuilder(cloudId));
    }

    public static ElasticsearchRestClientBuilder forURLs(final Collection<URL> urls) {
        return ElasticsearchRestClientBuilder.forURLs(urls, HostsArrayRestClientBuilderFactory.DEFAULT);
    }

    static ElasticsearchRestClientBuilder forURLs(final Collection<URL> urls,
                                                  final HostsArrayRestClientBuilderFactory factory) {
        Objects.requireNonNull(urls, "urls must not be null");
        if (urls.isEmpty()) { throw new IllegalStateException("urls must not be empty"); }

        final String commonScheme = extractCommon(urls, URL::getProtocol, "protocol", null);
        final String commonPath = extractCommon(urls, URL::getPath, "path", "/");

        if (urls.stream().map(URL::getPort).anyMatch((given) -> given == -1)) {
            throw new IllegalStateException("URLS must include port specification");
        }

        final HttpHost[] httpHosts = urls.stream().map(url -> new HttpHost(url.getHost(), url.getPort(), commonScheme)).toArray(HttpHost[]::new);

        return new ElasticsearchRestClientBuilder(() -> factory.getBuilder(httpHosts).setPathPrefix(commonPath));
    }

    public static Optional<ElasticsearchRestClientBuilder> fromPluginConfiguration(final PluginConfiguration config) {
        return builderInit(config).map(builder ->
            builder.configureTrust(trustConfig -> {
                config.sslVerificationMode().ifPresent(trustConfig::setSSLVerificationMode);
                config.truststore().ifPresent(truststore -> {
                    final Password truststorePassword = config.truststorePassword().orElseThrow(missingRequired("truststorePassword"));
                    trustConfig.setTrustStore(truststore, truststorePassword);
                });
                config.sslCertificateAuthorities().ifPresent(trustConfig::setCertificateAuthorities);
            }).configureIdentity(identityConfig -> {
                config.keystore().ifPresent(keystore -> {
                    final Password keystorePassword = config.keystorePassword().orElseThrow(missingRequired("keystorePassword"));
                    identityConfig.setKeyStore(keystore, keystorePassword);
                });
                config.sslCertificate().ifPresent(sslCertificate -> {
                    final Path sslKey = config.sslKey().orElseThrow(missingRequired("sslKey"));
                    final Password sslKeyPassphrase = config.sslKeyPassphrase().orElseThrow(missingRequired("sslKeyPassphrase"));
                    identityConfig.setCertificateKeyPair(sslCertificate, sslKey, sslKeyPassphrase);
                });
            }).configureRequestAuth(requestAuthConfig -> {
                config.authBasicUsername().ifPresent(username -> {
                    final Password authBasicPassword = config.authBasicPassword().orElseThrow(missingRequired("authBasicPassword"));
                    requestAuthConfig.setBasicAuth(username, authBasicPassword);
                });
                config.cloudAuth().ifPresent(requestAuthConfig::setCloudAuth);
                config.apiKey().ifPresent(requestAuthConfig::setApiKey);
            })
        );
    }

    private static Supplier<IllegalArgumentException> missingRequired(final String param) {
        return () -> new IllegalArgumentException(String.format("missing required `%s`", param));
    }

    private static Optional<ElasticsearchRestClientBuilder> builderInit(final PluginConfiguration config) {
        return config.cloudId().map(ElasticsearchRestClientBuilder::forCloudId)
                .or(() -> config.hosts().map(ElasticsearchRestClientBuilder::forURLs));
    }

    private ElasticsearchRestClientBuilder(final Supplier<RestClientBuilder> restClientBuilderSupplier) {
        this.restClientBuilderSupplier = restClientBuilderSupplier;
    }

    public ElasticsearchRestClientBuilder configureIdentity(final Consumer<IdentityConfig> identityConfigurator) {
        identityConfigurator.accept(this.identityConfig);
        return this;
    }

    public ElasticsearchRestClientBuilder configureTrust(final Consumer<TrustConfig> trustConfigurator) {
        trustConfigurator.accept(this.trustConfig);
        return this;
    }
    public ElasticsearchRestClientBuilder configureRequestAuth(final Consumer<RequestAuthConfig> requestAuthConfigurator) {
        requestAuthConfigurator.accept(this.requestAuthConfig);
        return this;
    }

    public ElasticsearchRestClientBuilder configureElasticApi(final Consumer<ElasticApiConfig> elasticApiConfigConsumer) {
        elasticApiConfigConsumer.accept(this.elasticApiConfig);
        return this;
    }

    public RestClient build() {
        return build(restClientBuilderSupplier.get());
    }

    RestClient build(final RestClientBuilder restClientBuilder) {
        return configureHttpClient(restClientBuilder, httpClientBuilder -> {
            this.trustConfig.configureHttpClient(httpClientBuilder);
            this.requestAuthConfig.configureHttpClient(httpClientBuilder);

            httpClientBuilder.setSSLContext(configureSSLContext(sslContextBuilder -> {
                this.trustConfig.configureSSLContext(sslContextBuilder);
                this.identityConfig.configureSSLContext(sslContextBuilder);
            }));

            this.elasticApiConfig.configureHttpClient(httpClientBuilder);
        }).build();
    }

    private static SSLContext configureSSLContext(final SSLContextConfigurator sslContextConfigurator) {
        final SSLContextBuilder sslContextBuilder = SSLContextBuilder.create();

        sslContextConfigurator.configure(sslContextBuilder);

        try {
            return sslContextBuilder.build();
        } catch (Exception e) {
            throw Exceptions.wrap(e, "Failed to build SSL Context");
        }
    }

    private RestClientBuilder configureHttpClient(final RestClientBuilder restClientBuilder,
                                                  final HttpClientConfigurator configurator) {
        return restClientBuilder.setHttpClientConfigCallback((httpClientBuilder -> {
            configurator.configure(httpClientBuilder);
            return httpClientBuilder;
        }));
    }

    private static <T,V> V extractCommon(Collection<T> input, final Function<T,V> extractor, final String message, final V valueIfMissing) {
        final List<V> provided = input.stream().map(extractor).map((ex) -> Objects.requireNonNullElse(ex, valueIfMissing)).distinct().toList();
        if (provided.isEmpty()) { throw new IllegalStateException(String.format("non-uniform(%s):%s", message, input)); }
        if (provided.size() > 1) { throw new IllegalStateException(String.format("non-uniform(%s):%s", message, input)); }

        return provided.get(0);
    }

    @FunctionalInterface
    interface SSLContextConfigurator {
        void configure(SSLContextBuilder sslContextBuilder);
    }

    @FunctionalInterface
    interface HttpClientConfigurator {
        void configure(HttpAsyncClientBuilder httpAsyncClientBuilder);

        static HttpClientConfigurator forAddInterceptorFirst(final HttpRequestInterceptor interceptor) {
            return httpAsyncClientBuilder -> {
                httpAsyncClientBuilder.addInterceptorFirst(interceptor);
            };
        }
    }

    /**
     * These HeaderInterceptor classes are replacement of lambda object instantiation
     * HttpProcessor has ChainBuilder.ensureUnique() to ensure the classes of interceptor are unique
     * Lambda instances are unfortunately treated as the same class, hence, the first header is removed
     */
    abstract static class HeaderInterceptor implements HttpRequestInterceptor {
        private final Header header;

        HeaderInterceptor(Header h) {
            this.header = h;
        }

        @Override
        public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
            final String method = request.getRequestLine().getMethod();
            if (method.equalsIgnoreCase("CONNECT")) {
                return;
            }
            request.setHeader(header);
        }
    }

    static class ApiKeyHttpRequestInterceptor extends HeaderInterceptor {
        ApiKeyHttpRequestInterceptor(Header h) {
            super(h);
        }
    }

    static class EAVHttpRequestInterceptor extends HeaderInterceptor {
        EAVHttpRequestInterceptor(Header h) {
            super(h);
        }
    }

    private enum SSLVerificationMode {
        FULL,
        CERTIFICATE,
        NONE,
        ;
    }

    @FunctionalInterface
    interface CloudIdRestClientBuilderFactory {
        RestClientBuilder getBuilder(String cloudId);
        CloudIdRestClientBuilderFactory DEFAULT = RestClient::builder;
    }
    @FunctionalInterface
    interface HostsArrayRestClientBuilderFactory {
        RestClientBuilder getBuilder(HttpHost... hosts);
        HostsArrayRestClientBuilderFactory DEFAULT = RestClient::builder;
    }

    public static class TrustConfig {
        private SSLVerificationMode sslVerificationMode = SSLVerificationMode.FULL;
        private KeyStore trustStore;

        public TrustConfig setSSLVerificationMode(final String proposedVerificationMode) {
            Objects.requireNonNull(proposedVerificationMode, "proposedVerificationMode");
            return this.setSSLVerificationMode(SSLVerificationMode.valueOf(proposedVerificationMode.toUpperCase()));
        }

        public TrustConfig setSSLVerificationMode(final SSLVerificationMode proposedVerificationMode) {
            Objects.requireNonNull(proposedVerificationMode, "proposedVerificationMode");
            synchronized (this) {
                if (proposedVerificationMode == SSLVerificationMode.NONE && Objects.nonNull(this.trustStore)) {
                    throw new IllegalStateException("SSL Verification Mode cannot be set to NONE when connection trust configuration has been provided");
                }
                this.sslVerificationMode = proposedVerificationMode;
            }
            return this;
        }

        public TrustConfig setCertificateAuthorities(final List<Path> certificateAuthorities) {
            Objects.requireNonNull(certificateAuthorities, "certificateAuthorities");
            return this.setTrustStore(KeyStoreUtil.fromCertificateAuthorities(certificateAuthorities));
        }

        public TrustConfig setTrustStore(final Path trustStorePath, final Password trustStorePassword) {
            Objects.requireNonNull(trustStorePath, "trustStorePath");
            Objects.requireNonNull(trustStorePassword, "trustStorePassword");
            return this.setTrustStore(KeyStoreUtil.load(trustStorePath, trustStorePassword));
        }

        private synchronized TrustConfig setTrustStore(final KeyStore trustStore) {
            synchronized (this) {
                if (this.sslVerificationMode == SSLVerificationMode.NONE) {
                    throw new IllegalStateException("Configuring connection trust source is not allowed when verification is set to NONE");
                }
                if (Objects.nonNull(this.trustStore)) {
                    throw new IllegalStateException("Only one connection trust source may be provided");
                }
                this.trustStore = trustStore;
            }
            return this;
        }

        public void configureSSLContext(final SSLContextBuilder sslContextBuilder) {
            try {
                if (sslVerificationMode == SSLVerificationMode.NONE) {
                    sslContextBuilder.loadTrustMaterial(null, TrustAllStrategy.INSTANCE);
                } else {
                    if (Objects.nonNull(trustStore)) {
                        sslContextBuilder.loadTrustMaterial(trustStore, null);
                    }
                }
            } catch (Exception e) {
                throw Exceptions.wrap(e, "Failed to configure SSL Context");
            }
        }

        public void configureHttpClient(final HttpAsyncClientBuilder httpClientBuilder) {
            if (sslVerificationMode == SSLVerificationMode.CERTIFICATE) {
                httpClientBuilder.setSSLHostnameVerifier(new NoopHostnameVerifier());
            }
        }
    }

    public static class IdentityConfig {
        private KeyStore keyStore;
        private Password keyPassword;

        public IdentityConfig setKeyStore(final Path keystorePath, final Password password) {
            Objects.requireNonNull(keystorePath, "keystorePath");
            Objects.requireNonNull(password, "password");
            return this.setKeyStoreAndPassword(KeyStoreUtil.load(keystorePath, password), password);
        }

        public IdentityConfig setCertificateKeyPair(final Path certificatePath,
                                                    final Path keyPath,
                                                    final Password keyPassword) {
            Objects.requireNonNull(certificatePath, "certificatePath");
            Objects.requireNonNull(keyPath, "keyPath");
            Objects.requireNonNull(keyPassword, "keyPassword");
            return this.setKeyStoreAndPassword(KeyStoreUtil.fromCertKeyPair(certificatePath, keyPath, keyPassword), keyPassword);
        }

        private synchronized IdentityConfig setKeyStoreAndPassword(final KeyStore proposedKeyStore, final Password proposedPassword) {
            if (Objects.nonNull(this.keyStore) || Objects.nonNull(this.keyPassword)) {
                throw new IllegalStateException("Only one connection identity source may be provided");
            }
            this.keyStore = proposedKeyStore;
            this.keyPassword = proposedPassword;
            return this;
        }

        public void configureSSLContext(final SSLContextBuilder sslContextBuilder) {
            if (Objects.isNull(keyStore)) { return; }
            try {
                sslContextBuilder.loadKeyMaterial(keyStore, keyPassword.getPassword().toCharArray());
            } catch (Exception e) {
                throw Exceptions.wrap(e, "Failed to configure SSL Context");
            }
        }
    }

    public static class RequestAuthConfig {
        private HttpClientConfigurator httpClientConfigurator;

        public RequestAuthConfig setBasicAuth(final String username, final Password password) {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");

            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password.getPassword()));

            return this.setHttpClientConfigurator((httpAsyncClientBuilder -> {
                httpAsyncClientBuilder.disableAuthCaching();
                httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }));
        }

        public RequestAuthConfig setApiKey(final Password apiKey) {
            Objects.requireNonNull(apiKey, "apiKey");

            final String encodedApiKey;
            // intercept non-encoded form, which contains a single colon separating two non-empty components
            if (apiKey.getPassword().matches("[^:]+:[^:]+")) {
                encodedApiKey = Base64.getEncoder().encodeToString(apiKey.getPassword().getBytes(StandardCharsets.UTF_8));
            } else {
                encodedApiKey = apiKey.getPassword();
            }

            final Header authorizationHeader = new BasicHeader("Authorization", String.format("ApiKey %s", encodedApiKey));
            final HttpRequestInterceptor interceptor = new ApiKeyHttpRequestInterceptor(authorizationHeader);
            return this.setHttpClientConfigurator(HttpClientConfigurator. forAddInterceptorFirst(interceptor));
        }

        public RequestAuthConfig setCloudAuth(final Password cloudAuth) {
            Objects.requireNonNull(cloudAuth, "cloudAuth");

            final String cloudAuthValue = cloudAuth.getValue();
            final int colonIndex = cloudAuthValue.indexOf(":");

            final String username;
            final String password;
            if ((colonIndex <= 0)
                    || isNullOrEmpty(username = cloudAuthValue.substring(0, colonIndex))
                    || isNullOrEmpty(password = cloudAuthValue.substring(colonIndex + 1))) {
                throw new IllegalArgumentException("Invalid cloudAuth.");
            }

            return this.setBasicAuth(username, new Password(password));
        }

        private synchronized RequestAuthConfig setHttpClientConfigurator(final HttpClientConfigurator httpClientConfigurator) {
            if (Objects.nonNull(this.httpClientConfigurator)) {
                throw new IllegalStateException("Only one request authentication source may be provided");
            }
            this.httpClientConfigurator = httpClientConfigurator;
            return this;
        }

        public void configureHttpClient(final HttpAsyncClientBuilder httpClientBuilder) {
            if (Objects.nonNull(httpClientConfigurator)) {
                httpClientConfigurator.configure(httpClientBuilder);
            }
        }
    }

    public static class ElasticApiConfig {
        private String apiVersion;
        public synchronized ElasticApiConfig setApiVersion(final String apiVersion) {
            if (Objects.nonNull(this.apiVersion)) {
                throw new IllegalStateException("Only one Elastic Api Version may be provided");
            }
            this.apiVersion = apiVersion;
            return this;
        }
        public void configureHttpClient(final HttpAsyncClientBuilder httpClientBuilder) {
            if (Objects.nonNull(apiVersion)) {
                final BasicHeader elasticApiVersionHeader = new BasicHeader("Elastic-Api-Version", apiVersion);
                final HttpRequestInterceptor interceptor = new EAVHttpRequestInterceptor(elasticApiVersionHeader);
                HttpClientConfigurator. forAddInterceptorFirst(interceptor).configure(httpClientBuilder);
            }
        }
    }
}
