package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.filters.elasticintegration.resolver.AbstractSimpleResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ingest.PipelineConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public class LocalDirectoryPipelineConfigurationResolver
        extends AbstractSimpleResolver<String,PipelineConfiguration>
        implements PipelineConfigurationResolver {

    private static final Logger LOGGER = LogManager.getLogger(LocalDirectoryPipelineConfigurationResolver.class);
    final Path localDirectory;
    private final PipelineConfigurationFactory pipelineConfigurationFactory;

    public LocalDirectoryPipelineConfigurationResolver(final Path localDirectory) {
        this.localDirectory = localDirectory;
        this.pipelineConfigurationFactory = PipelineConfigurationFactory.getInstance();
    }

    @Override
    public Optional<PipelineConfiguration> resolveSafely(final String pipelineName) throws Exception {
        final Path pipelinePath = localDirectory.resolve(sanitizePath(pipelineName) + ".json");
        LOGGER.trace(() -> String.format("RESOLVING `%s` -> `%s`", pipelineName, pipelinePath));
        final File pipelineFile = pipelinePath.toFile();
        if (!pipelineFile.isFile()) {
            LOGGER.warn(() -> String.format("File not found: `%s`", pipelinePath));
            return Optional.empty();
        }
        if (!pipelineFile.canRead()) {
            LOGGER.error(() -> String.format("File not readable: `%s`", pipelinePath));
            throw new IOException(String.format("File `%s` must be readable", pipelinePath));
        }
        if (pipelineFile.canWrite()) {
            LOGGER.error(() -> String.format("Refusing to load writable pipeline file: `%s`", pipelinePath));
            throw new IOException(String.format("File `%s` must not be writable", pipelinePath));
        }

        final String pipelineDefinition;
        try {
            pipelineDefinition = Files.readString(pipelinePath);
        } catch (IOException e) {
            LOGGER.error(() -> String.format("Failed to load pipeline configuration from file: %s", pipelinePath));
            return Optional.empty();
        }
        return Optional.of(pipelineConfigurationFactory.parseConfigOnly(pipelineName, pipelineDefinition));
    }

    final String sanitizePath(String pipelineName) {
        return pipelineName; // TODO: make filesystem-safe
    }
}
