/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.resolver;

import co.elastic.logstash.filters.elasticintegration.util.PluginContext;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;

import java.util.concurrent.ScheduledExecutorService;

/**
 * A {@link CacheReloadService} is a service for scheduled reloading of resolver caches via {@link CacheReloader}.
 */
public class CacheReloadService extends AbstractScheduledService {

    /**
     * Creates a new cache reload service, wholly managing the lifecycle of the internal
     * scheduled executor service to ensure that it is shut down when this service is terminated
     * or transitions into a failed state.
     *
     * @param pluginContext
     * @param reloader
     * @param scheduler
     * @return
     */
    public static CacheReloadService newManaged(final PluginContext pluginContext,
                                                final CacheReloader reloader,
                                                final Scheduler scheduler) {
        final String threadPurpose = String.format("cache-reloader(%s)", reloader.type());
        final ScheduledExecutorService executor = pluginContext.newSingleThreadScheduledExecutor(threadPurpose);

        final CacheReloadService cacheReloadService = new CacheReloadService(reloader, executor, scheduler);
        cacheReloadService.addListener(new Service.Listener() {
            public void terminated(Service.State from) {
                executor.shutdown();
            }

            public void failed(Service.State from, Throwable failure) {
                executor.shutdown();
            }
        }, MoreExecutors.directExecutor());

        return cacheReloadService;
    }

    final CacheReloader reloader;
    final ScheduledExecutorService executor;

    final Scheduler scheduler;

    private CacheReloadService(CacheReloader reloader,
                               ScheduledExecutorService executor,
                               Scheduler scheduler) {
        this.reloader = reloader;
        this.executor = executor;
        this.scheduler = scheduler;
    }

    @Override
    protected void runOneIteration() throws Exception {
        reloader.reloadOnce();
    }

    @Override
    protected Scheduler scheduler() {
        return scheduler;
    }

    @Override
    protected ScheduledExecutorService executor() {
        return executor;
    }
}
