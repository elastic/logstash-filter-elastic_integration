package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.core.IOUtils;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.plugins.IngestPlugin;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class SafeSubsetIngestPlugin implements IngestPlugin, Closeable {
    private final IngestPlugin ingestPlugin;
    private final Set<String> requiredProcessors;

    public static Supplier<IngestPlugin> safeSubset(final @Nonnull Supplier<IngestPlugin> ingestPluginSupplier,
                                                    final @Nonnull Set<String> requiredProcessors) {
        return () -> new SafeSubsetIngestPlugin(ingestPluginSupplier, requiredProcessors);
    }

    private SafeSubsetIngestPlugin(final @Nonnull Supplier<IngestPlugin> ingestPluginSupplier,
                                   final @Nonnull Set<String> requiredProcessors) {
        try {
            this.ingestPlugin = Objects.requireNonNull(ingestPluginSupplier.get(), "an IngestPlugin must be supplied!");
            this.requiredProcessors = Set.copyOf(requiredProcessors);
        } catch (Exception e) {
            IOUtils.closeWhileHandlingException(this);
            throw e;
        }
    }

    @Override
    public Map<String, Processor.Factory> getProcessors(Processor.Parameters parameters) {
        final Map<String, Processor.Factory> providedProcessors = this.ingestPlugin.getProcessors(parameters);

        final Map<String, Processor.Factory> acceptedProcessors = new HashMap<>();
        final Set<String> missingProcessors = new HashSet<>();

        for (String requiredProcessor : this.requiredProcessors) {
            final Processor.Factory processor = providedProcessors.get(requiredProcessor);
            if (!Objects.nonNull(processor)) {
                missingProcessors.add(requiredProcessor);
            } else {
                acceptedProcessors.put(requiredProcessor, processor);
            }
        }
        if (!missingProcessors.isEmpty()) {
            throw new IllegalStateException(String.format("Expected IngestPlugin %s to provide processors %s, but they were not provided", this.ingestPlugin, missingProcessors));
        }
        return Map.copyOf(acceptedProcessors);
    }

    @Override
    public void close() throws IOException {
        if (ingestPlugin instanceof Closeable) {
            ((Closeable) ingestPlugin).close();
        }
    }
}
