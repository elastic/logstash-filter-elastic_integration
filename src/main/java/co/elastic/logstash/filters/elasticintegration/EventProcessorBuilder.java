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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.ingest.IngestService;
import org.elasticsearch.ingest.LogstashInternalBridge;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.ingest.common.IngestCommonPlugin;
import org.elasticsearch.ingest.useragent.IngestUserAgentPlugin;
import org.elasticsearch.painless.PainlessPlugin;
import org.elasticsearch.painless.PainlessScriptEngine;
import org.elasticsearch.painless.spi.Whitelist;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.script.IngestConditionalScript;
import org.elasticsearch.script.IngestScript;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.mustache.MustacheScriptEngine;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
        this.addProcessorsFromPlugin(IngestCommonPlugin::new, Set.of(
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
                org.elasticsearch.ingest.common.TrimProcessor.TYPE,
                org.elasticsearch.ingest.common.URLDecodeProcessor.TYPE,
                org.elasticsearch.ingest.common.UppercaseProcessor.TYPE,
                org.elasticsearch.ingest.common.UriPartsProcessor.TYPE));
        this.addProcessorsFromPlugin(IngestUserAgentPlugin::new);
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

    private final List<Supplier<IngestPlugin>> ingestPlugins = new ArrayList<>();

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

    public EventProcessorBuilder addProcessor(final String type, final Supplier<Processor.Factory> processorFactorySupplier) {
        return this.addProcessorsFromPlugin(SingleProcessorIngestPlugin.of(type, processorFactorySupplier));
    }

    public EventProcessorBuilder addProcessorsFromPlugin(Supplier<IngestPlugin> pluginSupplier, Set<String> requiredProcessors) {
        return this.addProcessorsFromPlugin(safeSubset(pluginSupplier, requiredProcessors));
    }

    public synchronized EventProcessorBuilder addProcessorsFromPlugin(Supplier<IngestPlugin> pluginSupplier) {
        this.ingestPlugins.add(pluginSupplier);
        return this;
    }

    public synchronized EventProcessor build(final PluginContext pluginContext) {
        Objects.requireNonNull(this.pipelineConfigurationResolver, "pipeline configuration resolver is REQUIRED");
        Objects.requireNonNull(this.eventToIndexNameResolver, "event index name resolver is REQUIRED");
        Objects.requireNonNull(this.indexNameToPipelineNameResolver, "pipeline name resolver is REQUIRED");

        final Settings settings = Settings.builder()
                .put("path.home", "/")
                .put("node.name", "logstash.filter.elastic_integration." + pluginContext.pluginId())
                .put("ingest.grok.watchdog.interval", "1s")
                .put("ingest.grok.watchdog.max_execution_time", "1s")
                .build();

        final List<Closeable> resourcesToClose = new ArrayList<>();

        try {
            final ArrayList<Service> services = new ArrayList<>();

            final ThreadPool threadPool = LogstashInternalBridge.createThreadPool(settings);
            resourcesToClose.add(() -> ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS));

            final ScriptService scriptService = initScriptService(settings, threadPool);
            resourcesToClose.add(scriptService);

            final Environment env = new Environment(settings, null);
            final Processor.Parameters processorParameters = new Processor.Parameters(
                    env,
                    scriptService,
                    null,
                    threadPool.getThreadContext(),
                    threadPool::relativeTimeInMillis,
                    (delay, command) -> threadPool.schedule(command, TimeValue.timeValueMillis(delay), threadPool.generic()),
                    null,
                    null,
                    threadPool.generic()::execute,
                    IngestService.createGrokThreadWatchdog(env, threadPool)
            );

            IngestPipelineFactory ingestPipelineFactory = new IngestPipelineFactory(scriptService);
            for (Supplier<IngestPlugin> ingestPluginSupplier : ingestPlugins) {
                final IngestPlugin ingestPlugin = ingestPluginSupplier.get();
                if (ingestPlugin instanceof Closeable closeableIngestPlugin) {
                    resourcesToClose.add(closeableIngestPlugin);
                }
                final Map<String, Processor.Factory> processorFactories = ingestPlugin.getProcessors(processorParameters);
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
            IOUtils.closeWhileHandlingException(resourcesToClose);
            throw Exceptions.wrap(e, "Failed to build EventProcessor");
        }
    }

    private static ScriptService initScriptService(final Settings settings, final ThreadPool threadPool) {
        final List<Whitelist> painlessBaseWhitelist = getPainlessBaseWhiteList();
        final Map<ScriptContext<?>, List<Whitelist>> scriptContexts = Map.of(
                IngestScript.CONTEXT, painlessBaseWhitelist,
                IngestConditionalScript.CONTEXT, painlessBaseWhitelist);

        Map<String, ScriptEngine> engines = new HashMap<>();
        engines.put(PainlessScriptEngine.NAME, new PainlessScriptEngine(settings, scriptContexts));
        engines.put(MustacheScriptEngine.NAME, new MustacheScriptEngine());
        return new ScriptService(settings, engines, ScriptModule.CORE_CONTEXTS, threadPool::absoluteTimeInMillis);
    }

    /**
     * @implNote handles breaking changes introduced in Elasticsearch 8.14 series; once 8.14 is
     *           released and all builds of this plugin depend on Elasticsearch 8.14+, this can
     *           be simplified to call {@code PainlessPlugin.baseWhiteList()} directly.
     * @return the PainlessPlugin's default base whitelists
     */
    @SuppressWarnings({"JavaReflectionMemberAccess", "unchecked"})
    static List<Whitelist> getPainlessBaseWhiteList() {
        Class<PainlessPlugin> cls = PainlessPlugin.class;
        try {
            try {
                // In 8.14+: PainlessPlugin.baseWhiteList()
                return (List<Whitelist>) cls.getMethod("baseWhiteList").invoke(null);
            } catch (NoSuchMethodException e) {
                // in 8.x->8.13.x: PainlessPlugin.BASE_WHITELISTS
                return (List<Whitelist>) cls.getField("BASE_WHITELISTS").get(null);
            }
        } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException("Unsupported PainlessPlugin does not provide access to its base whitelists", e);
        }
    }
}
