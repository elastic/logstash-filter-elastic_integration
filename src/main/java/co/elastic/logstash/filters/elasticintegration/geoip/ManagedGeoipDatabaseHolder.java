package co.elastic.logstash.filters.elasticintegration.geoip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.ingest.geoip.GeoIpDatabase;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

public class ManagedGeoipDatabaseHolder implements GeoipDatabaseHolder, Closeable {

    private static final Logger LOGGER = LogManager.getLogger();

    private GeoIpDatabaseAdapter currentDatabase;
    private final String databaseTypeIdentifier;

    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;
    {
        final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        readLock = readWriteLock.readLock();
        writeLock = readWriteLock.writeLock();
    }

    public ManagedGeoipDatabaseHolder(final String databaseTypeIdentifier) {
        this.databaseTypeIdentifier = databaseTypeIdentifier;
    }

    @Override
    public boolean isValid() {
        return withLock(readLock, () -> Objects.nonNull(this.currentDatabase));
    }

    @Override
    public GeoIpDatabase getDatabase() {
        return withLock(readLock, () -> this.currentDatabase);
    }

    @Override
    public String getTypeIdentifier() {
        return databaseTypeIdentifier;
    }

    @Override
    public String info() {
        return withLock(readLock, () -> String.format("ManagedGeoipDatabase{type=%s, valid=%s}", getTypeIdentifier(), isValid()));
    }

    public void setDatabasePath(final String newDatabasePath) {
        withLock(writeLock, () -> {
            this.currentDatabase = Optional.ofNullable(newDatabasePath)
                    .map(Paths::get)
                    .map(this::loadDatabase)
                    .orElse(null);
        });
    }

    @Override
    public void close() throws IOException {
        withLock(writeLock, () -> {
            if (Objects.nonNull(this.currentDatabase)) {
                IOUtils.closeWhileHandlingException(this.currentDatabase);
                this.currentDatabase = null;
            }
        });
    }

    private GeoIpDatabaseAdapter loadDatabase(final Path databasePath) {
        try {
            final GeoIpDatabaseAdapter candidate = GeoIpDatabaseAdapter.defaultForPath(databasePath);
            final String candidateType = candidate.getDatabaseType();
            if (!Objects.equals(candidateType, this.databaseTypeIdentifier)) {
                throw new IllegalStateException(String.format("Incompatible database type `%s` (expected `%s`)", candidateType, this.databaseTypeIdentifier));
            }
            return candidate;
        } catch (IOException e) {
            LOGGER.warn(() -> String.format("failed to load database from path `%s`: %s", databasePath, e));
            return null;
        }
    }

    private <T> T withLock(final Lock lock,
                           final Supplier<T> handler) {
        lock.lock();
        try {
            return handler.get();
        } finally {
            lock.unlock();
        }
    }
    private void withLock(final Lock lock,
                          final Runnable runnable) {
        this.<Void>withLock(lock, () -> {
            runnable.run();
            return null;
        });
    }

}
