package co.elastic.logstash.filters.elasticintegration.util;

import java.nio.file.Path;

import static co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil.getResourcePath;

public class LocalPipelinesUtil {
    public static Path getPreparedPipelinesResourcePath(final Class<?> resourceProvider, final String packageRelativePath) {
        return getResourcePath(resourceProvider, packageRelativePath)
                .map(ResourcesUtil::ensureContentsReadableNonWritable)
                .orElseThrow(() -> new IllegalArgumentException(String.format("failed to load resource for `%s`", packageRelativePath)));
    }
}
