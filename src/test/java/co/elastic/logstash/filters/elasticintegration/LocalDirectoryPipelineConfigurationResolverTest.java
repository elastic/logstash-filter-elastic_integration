/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import org.elasticsearch.ingest.PipelineConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static co.elastic.logstash.filters.elasticintegration.util.LocalPipelinesUtil.*;
import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.ensureSetFileReadable;
import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.ensureSetFileWritable;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class LocalDirectoryPipelineConfigurationResolverTest {
    @Test
    void resolvePresent() {
        final Path pipelinesResourcePath = getPreparedPipelinesResourcePath(this.getClass(), "simple-mutate-pipelines");

        final PipelineConfigurationResolver pcr = new LocalDirectoryPipelineConfigurationResolver(pipelinesResourcePath);

        final Optional<PipelineConfiguration> resolved = pcr.resolve("simple-mutate");
        assertThat(resolved.isPresent(), is(true));
        assertThat(resolved.get().getId(), is(equalTo("simple-mutate")));
    }

    @Test
    void resolveMissing() {
        final Path pipelinesResourcePath = getPreparedPipelinesResourcePath(this.getClass(), "simple-mutate-pipelines");

        final PipelineConfigurationResolver pcr = new LocalDirectoryPipelineConfigurationResolver(pipelinesResourcePath);

        final Optional<PipelineConfiguration> resolved = pcr.resolve("not-there");
        assertThat(resolved.isPresent(), is(false));
    }

    @Test
    void resolvePresentNonReadable() {
        final Path pipelinesResourcePath = getPreparedPipelinesResourcePath(this.getClass(), "simple-mutate-pipelines");
        ensureSetFileReadable(pipelinesResourcePath.resolve("simple-mutate.json").toFile(), false);

        final PipelineConfigurationResolver pcr = new LocalDirectoryPipelineConfigurationResolver(pipelinesResourcePath);

        final Optional<PipelineConfiguration> resolved = pcr.resolve("simple-mutate");
        assertThat(resolved, is(notNullValue()));
        assertThat(resolved.isPresent(), is(false));

        final AtomicReference<Exception> lastExceptionObserved = new AtomicReference<>();
        final Optional<PipelineConfiguration> resolvedObserved = pcr.resolve("simple-mutate", lastExceptionObserved::set);
        assertAll("observed resolve", ()-> {
                    assertThat(resolvedObserved.isPresent(), is(false));
                    assertThat(lastExceptionObserved.get(), is(notNullValue()));
                    assertAll("exception observed", () -> {
                        assertThat(lastExceptionObserved.get(), is(instanceOf(IOException.class)));
                        assertThat(lastExceptionObserved.get().getMessage(), stringContainsInOrder("simple-mutate.json", "must be readable"));
                    });
                });

        // recovery
        ensureSetFileReadable(pipelinesResourcePath.resolve("simple-mutate.json").toFile(), true);
        final Optional<PipelineConfiguration> resolvedRecovered = pcr.resolve("simple-mutate");
        assertAll("recovery", () -> {
            assertThat(resolvedRecovered, is(notNullValue()));
            assertThat(resolvedRecovered.isPresent(), is(true));
            assertThat(resolvedRecovered.get().getId(), is(equalTo("simple-mutate")));
        });
    }

    @Test
    void resolvePresentWritable() {
        final Path pipelinesResourcePath = getPreparedPipelinesResourcePath(this.getClass(), "simple-mutate-pipelines");
        ensureSetFileWritable(pipelinesResourcePath.resolve("simple-mutate.json").toFile(), true);

        final PipelineConfigurationResolver pcr = new LocalDirectoryPipelineConfigurationResolver(pipelinesResourcePath);

        final Optional<PipelineConfiguration> resolved = pcr.resolve("not-there");
        assertThat(resolved, is(notNullValue()));
        assertThat(resolved.isPresent(), is(false));

        final AtomicReference<Exception> lastExceptionObserved = new AtomicReference<>();
        final Optional<PipelineConfiguration> resolvedObserved = pcr.resolve("simple-mutate", lastExceptionObserved::set);
        assertAll("observed resolve", ()-> {
            assertThat(resolvedObserved.isPresent(), is(false));
            assertThat(lastExceptionObserved.get(), is(notNullValue()));
            assertAll("exception observed", () -> {
                assertThat(lastExceptionObserved.get(), is(instanceOf(IOException.class)));
                assertThat(lastExceptionObserved.get().getMessage(), stringContainsInOrder("simple-mutate.json", "must not be writable"));
            });
        });

        // recovery
        ensureSetFileWritable(pipelinesResourcePath.resolve("simple-mutate.json").toFile(), false);
        final Optional<PipelineConfiguration> resolvedRecovered = pcr.resolve("simple-mutate");
        assertAll("recovery", () -> {
            assertThat(resolvedRecovered, is(notNullValue()));
            assertThat(resolvedRecovered.isPresent(), is(true));
            assertThat(resolvedRecovered.get().getId(), is(equalTo("simple-mutate")));
        });
    }
}