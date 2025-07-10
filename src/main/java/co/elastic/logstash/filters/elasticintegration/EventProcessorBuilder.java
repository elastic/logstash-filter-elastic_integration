/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.FilterMatchListener;
import co.elastic.logstash.filters.elasticintegration.ingest.RedactPlugin;
import co.elastic.logstash.filters.elasticintegration.ingest.SetSecurityUserProcessor;
import co.elastic.logstash.filters.elasticintegration.ingest.SingleProcessorIngestPlugin;
import co.elastic.logstash.filters.elasticintegration.resolver.CacheReloadService;
import co.elastic.logstash.filters.elasticintegration.resolver.CachingResolver;
import co.elastic.logstash.filters.elasticintegration.resolver.SimpleResolverCache;
import co.elastic.logstash.filters.elasticintegration.resolver.ResolverCache;
import co.elastic.logstash.filters.elasticintegration.util.Exceptions;
import co.elastic.logstash.filters.elasticintegration.util.PluginContext;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ServiceManager;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.logstashbridge.common.SettingsBridge;
import org.elasticsearch.logstashbridge.core.IOUtilsBridge;
import org.elasticsearch.logstashbridge.env.EnvironmentBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.plugins.IngestPluginBridge;
import org.elasticsearch.logstashbridge.script.ScriptServiceBridge;
import org.elasticsearch.logstashbridge.threadpool.ThreadPoolBridge;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static co.elastic.logstash.filters.elasticintegration.ingest.SafeSubsetIngestPlugin.safeSubset;
import static com.google.common.util.concurrent.AbstractScheduledService.Scheduler.newFixedRateSchedule;

@SuppressWarnings("UnusedReturnValue")
public class EventProcessorBuilder {

    static final Duration CACHE_MAXIMUM_AGE = Duration.ofHours(24);
    static final Duration CACHE_RELOAD_FREQUENCY = Duration.ofSeconds(60);

    private static <K,V> Supplier<ResolverCache<K,V>> defaultCacheSupplier(final String description) {
        return () -> new SimpleResolverCache<>(description, SimpleResolverCache.Configuration.PERMANENT);
    }

    public static EventProcessorBuilder fromElasticsearch(final RestClient elasticsearchRestClient, final PluginConfiguration pluginConfiguration) {
        final EventProcessorBuilder builder = new EventProcessorBuilder();

        if (pluginConfiguration.pipelineNameTemplate().isPresent()) {
            builder.setEventPipelineNameResolver(SprintfTemplateEventToPipelineNameResolver.from(pluginConfiguration.pipelineNameTemplate().get()));
        }

        builder.setEventIndexNameResolver(new DatastreamEventToIndexNameResolver());
        builder.setIndexNamePipelineNameResolver(new ElasticsearchIndexNameToPipelineNameResolver(elasticsearchRestClient));
        builder.setPipelineNameResolverCacheConfig(CACHE_MAXIMUM_AGE, CACHE_MAXIMUM_AGE);

        builder.setPipelineConfigurationResolver(new ElasticsearchPipelineConfigurationResolver(elasticsearchRestClient));
        builder.setIngestPipelineResolverCacheConfig(CACHE_MAXIMUM_AGE, CACHE_MAXIMUM_AGE);
        return builder;
    }

    public EventProcessorBuilder() {
        this.addProcessorsFromPlugin(() -> IngestPluginBridge.wrap(new org.elasticsearch.ingest.common.IngestCommonPlugin()), Set.of(
                org.elasticsearch.ingest.common.AppendProcessor.TYPE,
                org.elasticsearch.ingest.common.BytesProcessor.TYPE,
                org.elasticsearch.ingest.common.CommunityIdProcessor.TYPE,
                org.elasticsearch.ingest.common.ConvertProcessor.TYPE,
                org.elasticsearch.ingest.common.CsvProcessor.TYPE,
                org.elasticsearch.ingest.common.DateIndexNameProcessor.TYPE,
                org.elasticsearch.ingest.common.DateProcessor.TYPE,
                org.elasticsearch.ingest.common.DissectProcessor.TYPE,
                "dot_expander", // note: upstream constant is package-private
                org.elasticsearch.ingest.DropProcessor.TYPE, // note: not in ingest-common
                org.elasticsearch.ingest.common.FailProcessor.TYPE,
                org.elasticsearch.ingest.common.FingerprintProcessor.TYPE,
                org.elasticsearch.ingest.common.ForEachProcessor.TYPE,
                org.elasticsearch.ingest.common.GrokProcessor.TYPE,
                org.elasticsearch.ingest.common.GsubProcessor.TYPE,
                org.elasticsearch.ingest.common.HtmlStripProcessor.TYPE,
                org.elasticsearch.ingest.common.JoinProcessor.TYPE,
                org.elasticsearch.ingest.common.JsonProcessor.TYPE,
                org.elasticsearch.ingest.common.KeyValueProcessor.TYPE,
                org.elasticsearch.ingest.common.LowercaseProcessor.TYPE,
                org.elasticsearch.ingest.common.NetworkDirectionProcessor.TYPE,
                // note: no `pipeline` processor, as we provide our own
                org.elasticsearch.ingest.common.RegisteredDomainProcessor.TYPE,
                org.elasticsearch.ingest.common.RemoveProcessor.TYPE,
                org.elasticsearch.ingest.common.RenameProcessor.TYPE,
                org.elasticsearch.ingest.common.RerouteProcessor.TYPE,
                org.elasticsearch.ingest.common.ScriptProcessor.TYPE,
                org.elasticsearch.ingest.common.SetProcessor.TYPE,
                org.elasticsearch.ingest.common.SortProcessor.TYPE,
                org.elasticsearch.ingest.common.SplitProcessor.TYPE,
                "terminate", // note: upstream constant is package-private
                org.elasticsearch.ingest.common.TrimProcessor.TYPE,
                org.elasticsearch.ingest.common.URLDecodeProcessor.TYPE,
                org.elasticsearch.ingest.common.UppercaseProcessor.TYPE,
                org.elasticsearch.ingest.common.UriPartsProcessor.TYPE));
        this.addProcessorsFromPlugin(() -> IngestPluginBridge.wrap(new org.elasticsearch.ingest.useragent.IngestUserAgentPlugin()));
        this.addProcessorsFromPlugin(RedactPlugin::new);
        this.addProcessor(SetSecurityUserProcessor.TYPE, SetSecurityUserProcessor.Factory::new);
    }

    // event -> pipeline name
    private EventToPipelineNameResolver eventToPipelineNameResolver;

    // event -> index name
    private EventToIndexNameResolver eventToIndexNameResolver;

    // index name -> pipeline name
    private IndexNameToPipelineNameResolver indexNameToPipelineNameResolver;
    private Supplier<ResolverCache<String, String>> pipelineNameResolverCacheSupplier;


    // pipeline name -> executable ingest pipeline
    private PipelineConfigurationResolver pipelineConfigurationResolver;
    private Supplier<ResolverCache<String, IngestPipeline>> ingestPipelineResolverCacheSupplier;

    // filer match listener
    private FilterMatchListener filterMatchListener;

    private final List<Supplier<IngestPluginBridge>> ingestPlugins = new ArrayList<>();

    public synchronized EventProcessorBuilder setPipelineConfigurationResolver(final PipelineConfigurationResolver pipelineConfigurationResolver) {
        if (Objects.nonNull(this.pipelineConfigurationResolver)) {
            throw new IllegalStateException("pipelineConfigurationResolver already set");
        }
        this.pipelineConfigurationResolver = pipelineConfigurationResolver;
        return this;
    }

    public EventProcessorBuilder setIngestPipelineResolverCacheConfig(final Duration maxHitTtl,
                                                                      final Duration maxMissTtl) {
        return this.setIngestPipelineResolverCacheSupplier(() -> new SimpleResolverCache<>("pipeline", new SimpleResolverCache.Configuration(maxHitTtl, maxMissTtl)));
    }

    public synchronized EventProcessorBuilder setIngestPipelineResolverCacheSupplier(final Supplier<ResolverCache<String, IngestPipeline>> cacheSupplier) {
        this.ingestPipelineResolverCacheSupplier = cacheSupplier;
        return this;
    }

    public synchronized EventProcessorBuilder setEventPipelineNameResolver(final EventToPipelineNameResolver eventToPipelineNameResolver) {
        if (Objects.nonNull(this.eventToPipelineNameResolver)) {
            throw new IllegalStateException("eventToPipelineNameResolver already set");
        }
        this.eventToPipelineNameResolver = eventToPipelineNameResolver;
        return this;
    }

    public synchronized EventProcessorBuilder setEventIndexNameResolver(final EventToIndexNameResolver eventToIndexNameResolver) {
        if (Objects.nonNull(this.eventToIndexNameResolver)) {
            throw new IllegalStateException("eventToIndexNameResolver already set");
        }
        this.eventToIndexNameResolver = eventToIndexNameResolver;
        return this;
    }

    public EventProcessorBuilder setPipelineNameResolverCacheConfig(final Duration maxHitTtl,
                                                                    final Duration maxMissTtl) {
        return this.setPipelineNameResolverCacheSupplier(() -> new SimpleResolverCache<>("pipeline-name", new SimpleResolverCache.Configuration(maxHitTtl, maxMissTtl)));
    }
    public synchronized EventProcessorBuilder setPipelineNameResolverCacheSupplier(final Supplier<ResolverCache<String,String>> cacheSupplier) {
        this.pipelineNameResolverCacheSupplier = cacheSupplier;
        return this;
    }

    public synchronized EventProcessorBuilder setIndexNamePipelineNameResolver(final IndexNameToPipelineNameResolver indexNameToPipelineNameResolver) {
        if (Objects.nonNull(this.indexNameToPipelineNameResolver)) {
            throw new IllegalStateException("indexNameToPipelineNameResolver already set");
        }
        this.indexNameToPipelineNameResolver = indexNameToPipelineNameResolver;
        return this;
    }

    public EventProcessorBuilder setFilterMatchListener(final Consumer<Event> filterMatchListener) {
        return this.setFilterMatchListener((FilterMatchListener) filterMatchListener::accept);
    }

    private synchronized EventProcessorBuilder setFilterMatchListener(final FilterMatchListener filterMatchListener) {
        if (Objects.nonNull(this.filterMatchListener)) {
            throw new IllegalStateException("filterMatchListener already set");
        }
        this.filterMatchListener = filterMatchListener;
        return this;
    }

    public EventProcessorBuilder addProcessor(final String type, final Supplier<ProcessorBridge.Factory> processorFactorySupplier) {
        return this.addProcessorsFromPlugin(SingleProcessorIngestPlugin.of(type, processorFactorySupplier));
    }

    public EventProcessorBuilder addProcessorsFromPlugin(Supplier<IngestPluginBridge> pluginSupplier, Set<String> requiredProcessors) {
        return this.addProcessorsFromPlugin(safeSubset(pluginSupplier, requiredProcessors));
    }

    public synchronized EventProcessorBuilder addProcessorsFromPlugin(Supplier<IngestPluginBridge> pluginSupplier) {
        this.ingestPlugins.add(pluginSupplier);
        return this;
    }

    public synchronized EventProcessor build(final PluginContext pluginContext) {
        Objects.requireNonNull(this.pipelineConfigurationResolver, "pipeline configuration resolver is REQUIRED");
        Objects.requireNonNull(this.eventToIndexNameResolver, "event index name resolver is REQUIRED");
        Objects.requireNonNull(this.indexNameToPipelineNameResolver, "pipeline name resolver is REQUIRED");

        final SettingsBridge settings = SettingsBridge.builder()
                .put("path.home", "/")
                .put("node.name", "logstash.filter.elastic_integration." + pluginContext.pluginId())
                .put("ingest.grok.watchdog.interval", "1s")
                .put("ingest.grok.watchdog.max_execution_time", "1s")
                .build();

        final List<Closeable> resourcesToClose = new ArrayList<>();

        try {
            final ArrayList<Service> services = new ArrayList<>();

            final ThreadPoolBridge threadPool = new ThreadPoolBridge(settings);
            resourcesToClose.add(() -> ThreadPoolBridge.terminate(threadPool, 10, TimeUnit.SECONDS));

            final ScriptServiceBridge scriptService = new ScriptServiceBridge(settings, threadPool::absoluteTimeInMillis);
            resourcesToClose.add(scriptService);

            final EnvironmentBridge env = new EnvironmentBridge(settings, null);
            final ProcessorBridge.Parameters processorParameters = new ProcessorBridge.Parameters(env, scriptService, threadPool);

            IngestPipelineFactory ingestPipelineFactory = new IngestPipelineFactory(scriptService);
            for (Supplier<IngestPluginBridge> ingestPluginSupplier : ingestPlugins) {
                final IngestPluginBridge ingestPlugin = ingestPluginSupplier.get();
                if (ingestPlugin instanceof Closeable closeableIngestPlugin) {
                    resourcesToClose.add(closeableIngestPlugin);
                }
                final Map<String, ProcessorBridge.Factory> processorFactories = ingestPlugin.getProcessors(processorParameters);
                ingestPipelineFactory = ingestPipelineFactory.withProcessors(processorFactories);
            }

            final ResolverCache<String, IngestPipeline> ingestPipelineCache = Optional.ofNullable(ingestPipelineResolverCacheSupplier)
                    .orElse(defaultCacheSupplier("ingest-pipeline"))
                    .get();
            final SimpleCachingIngestPipelineResolver cachingInternalPipelineResolver =
                    new SimpleIngestPipelineResolver(this.pipelineConfigurationResolver, ingestPipelineFactory).withCache(ingestPipelineCache);
            services.add(CacheReloadService.newManaged(pluginContext, cachingInternalPipelineResolver.getReloader(), newFixedRateSchedule(CACHE_RELOAD_FREQUENCY, CACHE_RELOAD_FREQUENCY)));

            final FilterMatchListener filterMatchListener = Objects.requireNonNullElse(this.filterMatchListener, (event) -> {});

            final IndexNameToPipelineNameResolver indexNameToPipelineNameResolver;
            if (this.indexNameToPipelineNameResolver instanceof IndexNameToPipelineNameResolver.Cacheable cacheable) {
                final ResolverCache<String, String> pipelineNameCache = Optional.ofNullable(pipelineNameResolverCacheSupplier).orElse(defaultCacheSupplier("pipeline-name")).get();
                final CachingResolver<String, String> cachingPipelineNameResolver = cacheable.withCache(pipelineNameCache);
                services.add(CacheReloadService.newManaged(pluginContext, cachingPipelineNameResolver.getReloader(), newFixedRateSchedule(CACHE_RELOAD_FREQUENCY, CACHE_RELOAD_FREQUENCY)));
                indexNameToPipelineNameResolver = cachingPipelineNameResolver::resolve;
            } else {
                indexNameToPipelineNameResolver = this.indexNameToPipelineNameResolver;
            }

            // start the reload services for our resolvers
            final ServiceManager serviceManager = new ServiceManager(services);
            serviceManager.startAsync();
            resourcesToClose.add(() -> {
                serviceManager.stopAsync();
                serviceManager.awaitStopped();
            });

            return new EventProcessor(filterMatchListener,
                                      cachingInternalPipelineResolver,
                                      eventToPipelineNameResolver,
                                      eventToIndexNameResolver,
                                      indexNameToPipelineNameResolver,
                                      resourcesToClose);
        } catch (Exception e) {
            IOUtilsBridge.closeWhileHandlingException(resourcesToClose);
            throw Exceptions.wrap(e, "Failed to build EventProcessor");
        }
    }
}
