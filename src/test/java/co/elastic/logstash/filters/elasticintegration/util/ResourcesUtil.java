package co.elastic.logstash.filters.elasticintegration.util;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

public class ResourcesUtil {
    public static Optional<Path> getResourcePath(final Class<?> resourceProvider, final String packageRelativePath) {
        return Optional.ofNullable(resourceProvider.getResource(packageRelativePath))
                .map(URL::getPath)
                .map(Paths::get);
    }

    public static String readResource(final Class<?> resourceProvider, final String packageRelativePath) {
        return getResourcePath(resourceProvider, packageRelativePath)
                .filter(Files::isReadable)
                .map(ResourcesUtil::safelyReadString)
                .orElseThrow();
    }

    static Path ensureContentsReadableNonWritable(Path path) {
        for (File file : Objects.requireNonNull(path.toFile().listFiles())) {
            ensureSetFileReadable(file, true);
            ensureSetFileWritable(file, false);
        }
        return path;
    }

    public static void ensureSetFileReadable(File file, boolean desiredState) {
        if (desiredState != file.canRead()) {
            if (!file.setReadable(desiredState)) {
                throw new IllegalStateException(String.format("failed to ensure readable=%s for file: %s", desiredState, file));
            }
        }
    }

    public static void ensureSetFileWritable(File file, boolean desiredState) {
        if (desiredState != file.canWrite()) {
            if (!file.setWritable(desiredState)) {
                throw new IllegalStateException(String.format("failed to ensure writable=%s for file: %s", desiredState, file));
            }
        }
    }

    private static String safelyReadString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
