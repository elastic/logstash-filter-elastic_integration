package co.elastic.logstash.filters.elasticintegration.geoip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ingest.geoip.IpDatabase;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

public class ManagedIpDatabaseHolder implements IpDatabaseHolder, Closeable {

    private static final Logger LOGGER = LogManager.getLogger();

    private IpDatabaseAdapter currentDatabase;
    private final String databaseTypeIdentifier;

    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;
    {
        final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        readLock = readWriteLock.readLock();
        writeLock = readWriteLock.writeLock();
    }

    public ManagedIpDatabaseHolder(final String databaseTypeIdentifier) {
        this.databaseTypeIdentifier = databaseTypeIdentifier;
    }

    @Override
    public boolean isValid() {
        return withLock(readLock, () -> Objects.nonNull(this.currentDatabase));
    }

    @Override
    public IpDatabase getDatabase() {
        return withLock(readLock, () -> this.currentDatabase);
    }

    @Override
    public String getTypeIdentifier() {
        return databaseTypeIdentifier;
    }

    @Override
    public String info() {
        return withLock(readLock, () -> String.format("ManagedIpDatabase{type=%s, valid=%s}", getTypeIdentifier(), isValid()));
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
                try {
                    this.currentDatabase.closeReader();
                } catch (IOException e) {
                    this.currentDatabase = null;
                    throw new RuntimeException(e);
                }
                this.currentDatabase = null;
            }
        });
    }

    private IpDatabaseAdapter loadDatabase(final Path databasePath) {
        try {
            final IpDatabaseAdapter candidate = IpDatabaseAdapter.defaultForPath(databasePath);
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
