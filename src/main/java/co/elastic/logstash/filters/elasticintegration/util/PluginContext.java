/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@link PluginContext} holds contextual information about the plugin, and provides
 * a variety of factory methods for using that context analogous to static methods in {@link Executors}.
 */
public record PluginContext(@Nonnull String pipelineId,
                            @Nonnull String pluginId) {

    /**
     * Returns a new single-threaded scheduled executor service as {@link Executors#newSingleThreadScheduledExecutor()}
     * <em>EXCEPT</em> that it is provided with a context-aware thread factory.
     *
     * @param purpose to be included in the executor's thread names
     * @return a new single-threaded scheduled executor service with descriptive thread names
     */
    public ScheduledExecutorService newSingleThreadScheduledExecutor(final @Nonnull String purpose) {
        return Executors.newSingleThreadScheduledExecutor(newNamedThreadFactory(purpose));
    }

    /**
     * Returns a named thread factory used to create new threads with distinct names that include
     * context about the plugin that is running them and their purpose.
     *
     * @apiNote mirrors {@link Executors#defaultThreadFactory()}.
     *
     * @param purpose to be included in the thread's name
     * @return a thread factory
     */
    public ThreadFactory newNamedThreadFactory(final @Nonnull String purpose) {
        final String threadNamePrefix = "[" + pipelineId + "]filter|elastic_integration@" + pluginId + "|" + purpose + "-";
        return new ThreadFactory() {
            final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(final @Nullable Runnable runnable) {
                final Thread thread = new Thread(runnable, threadNamePrefix + threadNumber.getAndIncrement());

                if (thread.isDaemon()) {
                    thread.setDaemon(false);
                }
                if (thread.getPriority() != Thread.NORM_PRIORITY) {
                    thread.setPriority(Thread.NORM_PRIORITY);
                }
                return thread;
            }
        };
    }
}
