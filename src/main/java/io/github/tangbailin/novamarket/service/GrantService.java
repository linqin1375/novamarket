package io.github.tangbailin.novamarket.service;
import io.github.tangbailin.novamarket.port.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** Grant, claim status and outbox acknowledgement commit together. */
@Service
public class GrantService {
    private final Repository repository;
    private final ClaimMetrics metrics;
    public GrantService(Repository repository, ClaimMetrics metrics) { this.repository = repository; this.metrics = metrics; }
    @Transactional
    public void grant(String claimNo) {
        var claim = repository.lockClaimByClaimNo(claimNo);
        if (claim == null) return;
        if ("SUCCESS".equals(claim.status())) { repository.confirmMessage(claimNo); metrics.duplicateGrantIgnored(); return; }
        if (!"PROCESSING".equals(claim.status())) return;
        if (repository.grantBenefit(claimNo, claim.activityId(), claim.userId())) metrics.benefitGranted();
        else metrics.duplicateGrantIgnored();
        repository.updateClaimStatus(claimNo, "SUCCESS", null);
        repository.confirmMessage(claimNo);
    }
}
