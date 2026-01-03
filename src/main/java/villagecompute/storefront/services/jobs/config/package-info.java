/**
 * Background job framework with priority queues, retry policies, dead-letter handling, and Prometheus metrics.
 *
 * <p>
 * This package provides the infrastructure for reliable, observable background job processing:
 *
 * <h2>Core Components</h2>
 * <ul>
 * <li>{@link villagecompute.storefront.services.jobs.config.JobPriority} - Priority levels (CRITICAL → BULK)</li>
 * <li>{@link villagecompute.storefront.services.jobs.config.RetryPolicy} - Configurable retry with exponential
 * backoff</li>
 * <li>{@link villagecompute.storefront.services.jobs.config.JobConfig} - Framework configuration per priority</li>
 * <li>{@link villagecompute.storefront.services.jobs.config.PriorityJobQueue} - Multi-priority queue with metrics</li>
 * <li>{@link villagecompute.storefront.services.jobs.config.DeadLetterQueue} - Failed job retention for inspection</li>
 * <li>{@link villagecompute.storefront.services.jobs.config.JobProcessor} - Generic processor with tenant context
 * handling</li>
 * <li>{@link villagecompute.storefront.services.jobs.config.JobExecution} - Execution metadata wrapper for retry
 * tracking</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * 
 * <pre>
 * {
 *     &#64;code
 *     &#64;ApplicationScoped
 *     public class MyJobService {
 *
 *         &#64;Inject
 *         MeterRegistry meterRegistry;
 *
 *         private PriorityJobQueue<MyPayload> queue;
 *         private DeadLetterQueue<MyPayload> dlq;
 *         private JobProcessor<MyPayload> processor;
 *
 *         &#64;PostConstruct
 *         void init() {
 *             JobConfig config = JobConfig.defaults();
 *             queue = new PriorityJobQueue<>("my.job", meterRegistry, config);
 *             dlq = new DeadLetterQueue<>("my.job", meterRegistry);
 *             processor = new JobProcessor<>("my.job", meterRegistry, queue, dlq, config, this::handleJob, // JobHandler
 *                     MyPayload::getTenantId); // TenantContextExtractor
 *         }
 *
 *         public void enqueueJob(MyPayload payload, JobPriority priority) {
 *             queue.enqueue(payload, priority);
 *         }
 *
 *         &#64;Scheduled(
 *                 every = "5s")
 *         void processJobs() {
 *             processor.processAllPending();
 *         }
 *
 *         private void handleJob(MyPayload payload) throws Exception {
 *             // Actual job logic here
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Metrics Exported</h2>
 * <ul>
 * <li>{@code <queue_name>.queue.depth} - Gauge per priority (tags: priority)</li>
 * <li>{@code <queue_name>.job.enqueued} - Counter (tags: priority)</li>
 * <li>{@code <queue_name>.job.polled} - Counter (tags: priority)</li>
 * <li>{@code <queue_name>.job.started} - Counter (tags: priority)</li>
 * <li>{@code <queue_name>.job.completed} - Counter (tags: priority)</li>
 * <li>{@code <queue_name>.job.failed} - Counter (tags: priority, attempt)</li>
 * <li>{@code <queue_name>.job.retried} - Counter (tags: priority, attempt)</li>
 * <li>{@code <queue_name>.job.exhausted} - Counter (tags: priority)</li>
 * <li>{@code <queue_name>.job.duration} - Timer (tags: priority, status)</li>
 * <li>{@code <queue_name>.job.wait_time} - Timer (tags: priority)</li>
 * <li>{@code <queue_name>.queue.overflow} - Counter (tags: priority)</li>
 * <li>{@code <queue_name>.dlq.depth} - Gauge</li>
 * <li>{@code <queue_name>.dlq.added} - Counter (tags: priority)</li>
 * <li>{@code <queue_name>.dlq.removed} - Counter</li>
 * </ul>
 *
 * <h2>Architecture References</h2>
 * <ul>
 * <li>Operational Architecture §3.6: Background Processing, Media Workloads, and Job Governance</li>
 * <li>Operational Architecture §3.7: Observability Fabric, Telemetry, and Runbook Integration</li>
 * <li>Task I3.T6: Background Job Framework Enhancements</li>
 * </ul>
 *
 * @see villagecompute.storefront.services.ReportingJobService
 * @see villagecompute.storefront.notifications.NotificationJobQueue
 */
package villagecompute.storefront.services.jobs.config;
