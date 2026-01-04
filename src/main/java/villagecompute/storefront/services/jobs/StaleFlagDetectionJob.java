package villagecompute.storefront.services.jobs;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import villagecompute.storefront.data.models.FeatureFlag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job to detect and report stale feature flags.
 *
 * <p>
 * Runs daily to identify flags that are:
 * <ul>
 * <li>Past their expiry date</li>
 * <li>Overdue for governance review</li>
 * </ul>
 *
 * <p>
 * Metrics are exposed to Prometheus for dashboard consumption and alerting.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Feature flag governance automation</li>
 * <li>Architecture: 05_Rationale_and_Future.md Section 4.1.12</li>
 * </ul>
 */
@ApplicationScoped
public class StaleFlagDetectionJob {

    private static final Logger LOG = Logger.getLogger(StaleFlagDetectionJob.class.getName());

    @Inject
    EntityManager entityManager;

    @Inject
    MeterRegistry meterRegistry;

    private Counter expiredFlagsDetectedCounter;
    private Counter reviewOverdueDetectedCounter;

    private final AtomicInteger expiredGauge = new AtomicInteger();
    private final AtomicInteger reviewGauge = new AtomicInteger();
    private final AtomicLong totalGauge = new AtomicLong();
    private final AtomicLong enabledGauge = new AtomicLong();
    private final AtomicLong globalGauge = new AtomicLong();
    private final AtomicLong tenantOverrideGauge = new AtomicLong();

    @PostConstruct
    void registerMeters() {
        Gauge.builder("feature_flags_expired", expiredGauge, AtomicInteger::get)
                .description("Current number of feature flags past their expiry date").register(meterRegistry);
        Gauge.builder("feature_flags_review_overdue", reviewGauge, AtomicInteger::get)
                .description("Current number of feature flags overdue for review").register(meterRegistry);
        Gauge.builder("feature_flags_total", totalGauge, AtomicLong::doubleValue)
                .description("Total feature flags (global + tenant overrides)").register(meterRegistry);
        Gauge.builder("feature_flags_enabled", enabledGauge, AtomicLong::doubleValue)
                .description("Enabled feature flags").register(meterRegistry);
        Gauge.builder("feature_flags_global", globalGauge, AtomicLong::doubleValue).description("Global feature flags")
                .register(meterRegistry);
        Gauge.builder("feature_flags_tenant_overrides", tenantOverrideGauge, AtomicLong::doubleValue)
                .description("Tenant override feature flags").register(meterRegistry);

        expiredFlagsDetectedCounter = Counter.builder("feature_flags_expired_detection_total")
                .description("Total expired flag detections by governance job").register(meterRegistry);
        reviewOverdueDetectedCounter = Counter.builder("feature_flags_review_overdue_detection_total")
                .description("Total review overdue flag detections by governance job").register(meterRegistry);
    }

    /**
     * Run stale flag detection daily at 2 AM.
     */
    @Scheduled(
            cron = "0 0 2 * * ?")
    @Transactional
    public void detectStaleFlags() {
        LOG.log(Level.INFO, "Running stale feature flag detection job");

        OffsetDateTime now = OffsetDateTime.now();

        // Find expired flags
        List<FeatureFlag> expired = entityManager
                .createQuery("SELECT ff FROM FeatureFlag ff WHERE ff.expiryDate < :now AND ff.enabled = true",
                        FeatureFlag.class)
                .setParameter("now", now).getResultList();

        // Find flags overdue for review
        List<FeatureFlag> needsReview = entityManager
                .createQuery("SELECT ff FROM FeatureFlag ff WHERE ff.reviewCadenceDays IS NOT NULL", FeatureFlag.class)
                .getResultList().stream().filter(flag -> isReviewOverdue(flag, now)).collect(Collectors.toList());

        expiredGauge.set(expired.size());
        reviewGauge.set(needsReview.size());

        expiredFlagsDetectedCounter.increment(expired.size());
        reviewOverdueDetectedCounter.increment(needsReview.size());

        // Log findings
        if (!expired.isEmpty()) {
            LOG.log(Level.WARNING, "Found {0} expired feature flags: {1}",
                    new Object[]{expired.size(), expired.stream().map(ff -> ff.flagKey).toList()});
        }

        if (!needsReview.isEmpty()) {
            LOG.log(Level.WARNING, "Found {0} flags overdue for review: {1}",
                    new Object[]{needsReview.size(), needsReview.stream().map(ff -> ff.flagKey).toList()});
        }

        if (expired.isEmpty() && needsReview.isEmpty()) {
            LOG.log(Level.INFO, "No stale feature flags found");
        }
    }

    /**
     * Get total count of enabled feature flags for tracking adoption.
     */
    @Scheduled(
            cron = "0 */15 * * * ?") // Every 15 minutes
    @Transactional
    public void updateFlagMetrics() {
        long totalFlags = entityManager.createQuery("SELECT COUNT(ff) FROM FeatureFlag ff", Long.class)
                .getSingleResult();

        long enabledFlags = entityManager
                .createQuery("SELECT COUNT(ff) FROM FeatureFlag ff WHERE ff.enabled = true", Long.class)
                .getSingleResult();

        long globalFlags = entityManager
                .createQuery("SELECT COUNT(ff) FROM FeatureFlag ff WHERE ff.tenant IS NULL", Long.class)
                .getSingleResult();

        long tenantOverrides = totalFlags - globalFlags;

        totalGauge.set(totalFlags);
        enabledGauge.set(enabledFlags);
        globalGauge.set(globalFlags);
        tenantOverrideGauge.set(tenantOverrides);
    }

    private boolean isReviewOverdue(FeatureFlag flag, OffsetDateTime now) {
        if (flag.reviewCadenceDays == null) {
            return false;
        }
        if (flag.lastReviewedAt != null) {
            return flag.lastReviewedAt.plusDays(flag.reviewCadenceDays).isBefore(now);
        }
        return flag.createdAt != null && flag.createdAt.plusDays(flag.reviewCadenceDays).isBefore(now);
    }
}
