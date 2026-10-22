package io.github.tangbailin.novamarket.api;

import io.github.tangbailin.novamarket.port.Repository;
import io.github.tangbailin.novamarket.service.ActivityService;
import io.github.tangbailin.novamarket.service.ClaimMetrics;
import io.github.tangbailin.novamarket.service.ClaimService;
import io.github.tangbailin.novamarket.service.CompensationService;
import io.github.tangbailin.novamarket.port.StockPort;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 接口。
 *
 * <p>接口设计上的两个决定：
 * <ol>
 *   <li><b>幂等键走请求头 {@code X-Request-Id}</b>，而不是请求体 —— 它是传输层语义
 *       （"这次请求的身份"），放在头里能让网关和重试中间件也识别它；</li>
 *   <li><b>业务失败用 200 + 业务码，系统错误才用 5xx</b>。库存不足、超出限领是**正常业务结果**，
 *       用 4xx/5xx 会让监控把正常流量算成故障，用户端也会触发无意义的重试。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api")
public class ClaimController {

    private final ClaimService claimService;
    private final ActivityService activityService;
    private final CompensationService compensationService;
    private final ClaimMetrics metrics;
    private final StockPort stockPort;
    private final Repository repository;

    public ClaimController(ClaimService claimService, ActivityService activityService,
                           CompensationService compensationService, ClaimMetrics metrics,
                           StockPort stockPort, Repository repository) {
        this.claimService = claimService;
        this.activityService = activityService;
        this.compensationService = compensationService;
        this.metrics = metrics;
        this.stockPort = stockPort;
        this.repository = repository;
    }

    // ------------------------------------------------------------------ 活动

    @GetMapping("/activities")
    public List<ActivityService.ActivityDetail> activities() {
        return repository.listActivities().stream().map(a -> activityService.detail(a.id())).toList();
    }

    public record CreateActivityRequest(String name, Integer totalStock, Integer ttlSeconds,
                                        Integer perUserLimit) {
    }

    @PostMapping("/activities")
    public ResponseEntity<?> createActivity(@RequestBody CreateActivityRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        int total = request.totalStock() == null ? 100 : request.totalStock();
        int ttl = request.ttlSeconds() == null ? 3600 : request.ttlSeconds();
        int limit = request.perUserLimit() == null ? 2 : request.perUserLimit();
        Repository.ActivityRow activity = activityService.create(request.name(), total,
                Duration.ofSeconds(ttl), limit);
        return ResponseEntity.ok(Map.of(
                "activityId", activity.id(),
                "totalStock", activity.totalStock(),
                "shardCount", activity.shardCount(),
                "perUserLimit", activity.perUserLimit(),
                "stockImpl", stockPort.implName()));
    }

    @GetMapping("/activities/{activityId}")
    public ResponseEntity<?> activity(@PathVariable String activityId) {
        ActivityService.ActivityDetail detail = activityService.detail(activityId);
        if (detail == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "ACTIVITY_NOT_FOUND", "activityId", activityId));
        }
        // 刻意同时返回缓存与 DB 两侧库存：对账差异一眼可见，不需要去翻日志
        return ResponseEntity.ok(detail);
    }

    // ------------------------------------------------------------------ 领取

    public record ClaimRequest(String userId) {
    }

    @PostMapping("/activities/{activityId}/claim")
    public ResponseEntity<ClaimResponse> claim(@PathVariable String activityId,
                               @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                               @RequestBody(required = false) ClaimRequest request) {
        String userId = request == null ? null : request.userId();
        ClaimService.ClaimOutcome outcome = claimService.claim(activityId, userId, requestId);
        int status = switch (outcome.code()) {
            case PROCESSING -> 202;
            case IDEMPOTENCY_CONFLICT -> 409;
            case ACTIVITY_NOT_FOUND -> 404;
            case RATE_LIMITED -> 429;
            case STORAGE_ERROR -> 503;
            case INTERNAL_ERROR -> 500;
            default -> 200;
        };
        return ResponseEntity.status(status).body(new ClaimResponse(outcome.code().name(), outcome.claimNo(), outcome.detail(), userId));
    }

    @GetMapping("/claims/{claimNo}")
    public ResponseEntity<?> claimStatus(@PathVariable String claimNo) {
        Repository.ClaimRow claim = repository.findClaimByClaimNo(claimNo);
        if (claim == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "CLAIM_NOT_FOUND", "claimNo", claimNo));
        }
        return ResponseEntity.ok(Map.of(
                "claimNo", claim.claimNo(),
                "activityId", claim.activityId(),
                "userId", claim.userId(),
                "status", claim.status(),
                "failReason", claim.failReason() == null ? "" : claim.failReason(),
                "granted", repository.benefitGranted(claimNo)));
    }

    public record ClaimResponse(String code, String claimNo, String detail, String userId) {
    }

    // ------------------------------------------------------------------ 可观测

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>(metrics.snapshot());
        snapshot.put("stockImpl", stockPort.implName());
        snapshot.put("application", "novamarket");
        snapshot.put("messages", repository.messageCounts());
        snapshot.put("database", "JDBC");
        return snapshot;
    }

    @GetMapping("/reconcile/report")
    public Map<String, Object> reconcileReport() {
        List<CompensationService.ReconcileRow> rows =
                compensationService.report(activityService.listRunning());
        boolean balanced = rows.stream().allMatch(r -> r.diff() == 0 && r.shardBalanced());
        return Map.of("balanced", balanced, "activities", rows);
    }

    /** 手动触发补偿：演示与排查时不用等定时任务。 */
    @PostMapping("/compensate/run")
    public Map<String, Object> runCompensation() {
        compensationService.settleStaleWalEntries();
        compensationService.retryPendingMessages();
        compensationService.reconcileStock();
        return Map.of("triggered", true, "metrics", metrics.snapshot());
    }

    // ------------------------------------------------------------------ 异常

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedJson() {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", "请求体须为合法 JSON"));
    }
}
