package io.github.tangbailin.novamarket.service;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
/** Single-instance fence: maintenance must not reset a reservation still settling in the DB. */
@Component
public class MaintenanceGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    public <T> T claim(Supplier<T> work) {
        lock.readLock().lock();
        try { return work.get(); } finally { lock.readLock().unlock(); }
    }
    public void maintain(Runnable work) {
        lock.writeLock().lock();
        try { work.run(); } finally { lock.writeLock().unlock(); }
    }
}
