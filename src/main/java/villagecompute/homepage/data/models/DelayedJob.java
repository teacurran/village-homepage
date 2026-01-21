package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;
import villagecompute.homepage.jobs.JobQueue;
import villagecompute.homepage.jobs.JobType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Panache entity for async job orchestration via database-backed queue (Policy P7).
 *
 * <p>
 * Supports distributed job execution with priority scheduling, retry logic, and distributed locking via
 * {@code locked_at}/{@code locked_by} fields. Workers poll this table for ready jobs and update status atomically.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (BIGSERIAL, PK) - Primary identifier</li>
 * <li>{@code job_type} (TEXT) - JobType enum value</li>
 * <li>{@code queue} (TEXT) - JobQueue family (DEFAULT, HIGH, LOW, BULK, SCREENSHOT)</li>
 * <li>{@code priority} (INT) - Within-queue priority (higher = more urgent)</li>
 * <li>{@code payload} (JSONB) - Job parameters deserialized by handler</li>
 * <li>{@code status} (TEXT) - PENDING, PROCESSING, COMPLETED, FAILED</li>
 * <li>{@code attempts} (INT) - Execution attempt counter</li>
 * <li>{@code max_attempts} (INT) - Max retries before moving to FAILED</li>
 * <li>{@code scheduled_at} (TIMESTAMPTZ) - Earliest execution time (used for backoff)</li>
 * <li>{@code locked_at} (TIMESTAMPTZ) - When worker claimed job</li>
 * <li>{@code locked_by} (TEXT) - Worker identifier (hostname:pid)</li>
 * <li>{@code completed_at} (TIMESTAMPTZ) - Completion timestamp</li>
 * <li>{@code failed_at} (TIMESTAMPTZ) - Failure timestamp</li>
 * <li>{@code last_error} (TEXT) - Error message from last attempt</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Record last update timestamp</li>
 * </ul>
 *
 * @see JobType for job type definitions
 * @see JobQueue for queue family priorities
 * @see villagecompute.homepage.services.DelayedJobService for job orchestration
 */
@Entity
@Table(
        name = "delayed_jobs")
public class DelayedJob extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(DelayedJob.class);

    public static final String QUERY_FIND_READY_JOBS = "DelayedJob.findReadyJobs";
    public static final String QUERY_FIND_BY_STATUS = "DelayedJob.findByStatus";

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    @Column(
            nullable = false)
    public Long id;

    @Column(
            name = "job_type",
            nullable = false)
    @Enumerated(EnumType.STRING)
    public JobType jobType;

    @Column(
            name = "queue",
            nullable = false)
    @Enumerated(EnumType.STRING)
    public JobQueue queue;

    @Column(
            name = "priority",
            nullable = false)
    public int priority;

    @Column(
            name = "payload",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> payload;

    @Column(
            name = "status",
            nullable = false)
    @Enumerated(EnumType.STRING)
    public JobStatus status;

    @Column(
            name = "attempts",
            nullable = false)
    public int attempts;

    @Column(
            name = "max_attempts",
            nullable = false)
    public int maxAttempts;

    @Column(
            name = "scheduled_at",
            nullable = false)
    public Instant scheduledAt;

    @Column(
            name = "locked_at")
    public Instant lockedAt;

    @Column(
            name = "locked_by")
    public String lockedBy;

    @Column(
            name = "completed_at")
    public Instant completedAt;

    @Column(
            name = "failed_at")
    public Instant failedAt;

    @Column(
            name = "last_error")
    public String lastError;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Job lifecycle statuses.
     */
    public enum JobStatus {
        /**
         * Job created, awaiting execution.
         */
        PENDING,

        /**
         * Job actively executing by a worker.
         */
        PROCESSING,

        /**
         * Job completed successfully.
         */
        COMPLETED,

        /**
         * Job failed after exhausting retries.
         */
        FAILED
    }

    /**
     * Finds all ready jobs for a specific queue, ordered by priority and scheduled_at.
     *
     * <p>
     * <b>Ready criteria:</b>
     * <ul>
     * <li>status = PENDING</li>
     * <li>scheduled_at <= NOW()</li>
     * <li>locked_at IS NULL OR locked_at < NOW() - 5 minutes (stale lock recovery)</li>
     * </ul>
     *
     * @param queue
     *            the queue to poll
     * @param limit
     *            max jobs to return
     * @return list of ready jobs, ordered by priority DESC then scheduled_at ASC
     */
    public static List<DelayedJob> findReadyJobs(JobQueue queue, int limit) {
        if (queue == null) {
            return List.of();
        }
        Instant staleThreshold = Instant.now().minusSeconds(5 * 60);
        return find("#" + QUERY_FIND_READY_JOBS
                + " WHERE queue = ?1 AND status = ?2 AND scheduled_at <= NOW() AND (locked_at IS NULL OR locked_at < ?3) "
                + "ORDER BY priority DESC, scheduled_at ASC", queue, JobStatus.PENDING, staleThreshold).page(0, limit)
                .list();
    }

    /**
     * Finds all jobs with a specific status.
     *
     * @param status
     *            the job status
     * @return list of jobs with this status
     */
    public static List<DelayedJob> findByStatus(JobStatus status) {
        if (status == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_STATUS + " WHERE status = ?1 ORDER BY created_at DESC", status).list();
    }

    /**
     * Finds all failed jobs (status = FAILED) ordered by failure time.
     *
     * @return list of failed jobs
     */
    public static List<DelayedJob> findFailed() {
        return find("status = ?1 ORDER BY failed_at DESC", JobStatus.FAILED).list();
    }

    /**
     * Creates and persists a new delayed job.
     *
     * @param jobType
     *            the job type
     * @param payload
     *            job parameters (will be serialized as JSONB)
     * @return persisted DelayedJob entity
     */
    public static DelayedJob create(JobType jobType, Map<String, Object> payload) {
        return create(jobType, payload, Instant.now(), 5);
    }

    /**
     * Creates and persists a new delayed job with custom scheduled_at and max_attempts.
     *
     * @param jobType
     *            the job type
     * @param payload
     *            job parameters (will be serialized as JSONB)
     * @param scheduledAt
     *            earliest execution time
     * @param maxAttempts
     *            max retry attempts
     * @return persisted DelayedJob entity
     */
    public static DelayedJob create(JobType jobType, Map<String, Object> payload, Instant scheduledAt,
            int maxAttempts) {
        DelayedJob job = new DelayedJob();
        job.jobType = jobType;
        job.queue = jobType.getQueue();
        job.priority = jobType.getQueue().getPriority();
        job.payload = payload;
        job.status = JobStatus.PENDING;
        job.attempts = 0;
        job.maxAttempts = maxAttempts;
        job.scheduledAt = scheduledAt;
        job.createdAt = Instant.now();
        job.updatedAt = Instant.now();

        job.persist();
        LOG.infof("Created job %d (type: %s, queue: %s, scheduled: %s)", job.id, jobType, job.queue, scheduledAt);
        return job;
    }

    /**
     * Locks this job for processing by a specific worker.
     *
     * @param workerId
     *            worker identifier (hostname:pid)
     */
    public void lock(String workerId) {
        this.status = JobStatus.PROCESSING;
        this.lockedAt = Instant.now();
        this.lockedBy = workerId;
        this.attempts++;
        this.updatedAt = Instant.now();
        this.persist();
        LOG.debugf("Locked job %d for worker %s (attempt %d)", (Object) this.id, (Object) workerId,
                (Object) this.attempts);
    }

    /**
     * Marks this job as completed.
     */
    public void markCompleted() {
        this.status = JobStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.persist();
        LOG.infof("Job %d marked COMPLETED", this.id);
    }

    /**
     * Marks this job as failed and records error message.
     *
     * @param errorMessage
     *            the error description
     */
    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.failedAt = Instant.now();
        this.lastError = errorMessage;
        this.updatedAt = Instant.now();
        this.persist();
        LOG.errorf("Job %d marked FAILED: %s", this.id, errorMessage);
    }

    /**
     * Resets job to PENDING status for retry with backoff delay.
     *
     * @param backoffSeconds
     *            seconds to delay before next attempt
     */
    public void scheduleRetry(long backoffSeconds) {
        this.status = JobStatus.PENDING;
        this.scheduledAt = Instant.now().plusSeconds(backoffSeconds);
        this.lockedAt = null;
        this.lockedBy = null;
        this.updatedAt = Instant.now();
        this.persist();
        LOG.infof("Job %d scheduled for retry in %d seconds (attempt %d/%d)", this.id, backoffSeconds, this.attempts,
                this.maxAttempts);
    }
}
