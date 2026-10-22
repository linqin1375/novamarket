package io.github.tangbailin.novamarket.service;
import io.github.tangbailin.novamarket.port.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
/** Rebuild before accepting traffic; one running instance per database. */
@Component
public class StockRecovery implements SmartInitializingSingleton {
    private final Repository repository;
    private final StockPort stock;
    private final MaintenanceGate gate;
    public StockRecovery(Repository repository, StockPort stock, MaintenanceGate gate) {
        this.repository = repository; this.stock = stock; this.gate = gate;
    }
    public void afterSingletonsInstantiated() { recover(); }
    public void recover() {
        gate.maintain(() -> repository.listActivities().forEach(a -> {
            repository.rebuildUserQuotas(a.id());
            stock.restoreState(a.id(), repository.listShards(a.id()).stream().map(Repository.ShardRow::stock).toList(),
                    repository.userClaimCounts(a.id()));
        }));
    }
}
