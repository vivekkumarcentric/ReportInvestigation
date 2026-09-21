package bug_investigation_agent.repository;

import bug_investigation_agent.model.FailureFeedback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain-JDBC (no Spring Data JPA, no migration framework) SQLite persistence for
 * {@link FailureFeedback} records. The project has no existing persistence layer, so this uses
 * the simplest dependency-minimal approach appropriate for a small local key-value-style table:
 * a single embedded SQLite file, one connection opened/closed per operation (fine for this
 * low-throughput local application).
 */
@Repository
public class FailureFeedbackRepository {

    private static final Logger log = LoggerFactory.getLogger(FailureFeedbackRepository.class);

    private static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS failure_feedback (
                failure_id TEXT PRIMARY KEY,
                scenario_name TEXT,
                feature_name TEXT,
                failed_step_line TEXT,
                normalized_step TEXT,
                normalized_error TEXT,
                exception_type TEXT,
                locator TEXT,
                stack_trace_pattern TEXT,
                ai_classification TEXT,
                human_classification TEXT,
                root_cause TEXT,
                confidence INTEGER,
                created_at TEXT,
                updated_at TEXT
            )
            """;

    private final String jdbcUrl;

    public FailureFeedbackRepository(
            @Value("${failure.feedback.db-path:./data/failure-feedback.db}") String dbPath) {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean created = parent.mkdirs();
            if (!created && !parent.exists()) {
                log.warn("Could not create directory for feedback database: {}", parent.getAbsolutePath());
            }
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getPath();
        initSchema();
    }

    private void initSchema() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(TABLE_DDL);
        } catch (SQLException e) {
            log.error("Failed to initialize failure_feedback schema: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to initialize failure feedback database", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public Optional<FailureFeedback> findByFailureId(String failureId) {
        String sql = "SELECT * FROM failure_feedback WHERE failure_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, failureId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read failure feedback for failureId={}: {}", failureId, e.getMessage(), e);
            throw new IllegalStateException("Failed to read failure feedback", e);
        }
    }

    /**
     * Returns all human-classified records sharing the given {@code exceptionType} (case
     * insensitive), used by {@link bug_investigation_agent.service.FailureFeedbackService} for
     * cross-report historical similarity matching (i.e. a different failure's content hash, but
     * the same underlying logical failure). Records with a null/blank {@code humanClassification}
     * are excluded here since only human-confirmed corrections are safe to reuse across
     * different failures.
     */
    public List<FailureFeedback> findHumanClassifiedByExceptionType(String exceptionType) {
        List<FailureFeedback> results = new ArrayList<>();
        if (exceptionType == null || exceptionType.isBlank()) {
            return results;
        }
        String sql = "SELECT * FROM failure_feedback "
                + "WHERE UPPER(exception_type) = UPPER(?) "
                + "AND human_classification IS NOT NULL "
                + "AND TRIM(human_classification) <> ''";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, exceptionType);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query historical candidates for exceptionType={}: {}",
                    exceptionType, e.getMessage(), e);
            throw new IllegalStateException("Failed to query historical candidates", e);
        }
        return results;
    }

    /**
     * Inserts a new record, or updates the existing one (matched by {@code failureId}) if it
     * already exists. Callers are responsible for preserving fields (e.g. an existing
     * {@code humanClassification}) that must not be clobbered - this method simply persists
     * whatever is on the given {@link FailureFeedback} instance.
     */
    public void upsert(FailureFeedback feedback) {
        String sql = """
                INSERT INTO failure_feedback (
                    failure_id, scenario_name, feature_name, failed_step_line, normalized_step,
                    normalized_error, exception_type, locator, stack_trace_pattern,
                    ai_classification, human_classification, root_cause, confidence,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(failure_id) DO UPDATE SET
                    scenario_name = excluded.scenario_name,
                    feature_name = excluded.feature_name,
                    failed_step_line = excluded.failed_step_line,
                    normalized_step = excluded.normalized_step,
                    normalized_error = excluded.normalized_error,
                    exception_type = excluded.exception_type,
                    locator = excluded.locator,
                    stack_trace_pattern = excluded.stack_trace_pattern,
                    ai_classification = excluded.ai_classification,
                    human_classification = excluded.human_classification,
                    root_cause = excluded.root_cause,
                    confidence = excluded.confidence,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, feedback.getFailureId());
            statement.setString(2, feedback.getScenarioName());
            statement.setString(3, feedback.getFeatureName());
            statement.setString(4, feedback.getFailedStepLine());
            statement.setString(5, feedback.getNormalizedStep());
            statement.setString(6, feedback.getNormalizedError());
            statement.setString(7, feedback.getExceptionType());
            statement.setString(8, feedback.getLocator());
            statement.setString(9, feedback.getStackTracePattern());
            statement.setString(10, feedback.getAiClassification());
            statement.setString(11, feedback.getHumanClassification());
            statement.setString(12, feedback.getRootCause());
            if (feedback.getConfidence() != null) {
                statement.setInt(13, feedback.getConfidence());
            } else {
                statement.setNull(13, java.sql.Types.INTEGER);
            }
            statement.setString(14, toText(feedback.getCreatedAt()));
            statement.setString(15, toText(feedback.getUpdatedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save failure feedback for failureId={}: {}",
                    feedback.getFailureId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to save failure feedback", e);
        }
    }

    private static String toText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private FailureFeedback mapRow(ResultSet rs) throws SQLException {
        FailureFeedback feedback = new FailureFeedback();
        feedback.setFailureId(rs.getString("failure_id"));
        feedback.setScenarioName(rs.getString("scenario_name"));
        feedback.setFeatureName(rs.getString("feature_name"));
        feedback.setFailedStepLine(rs.getString("failed_step_line"));
        feedback.setNormalizedStep(rs.getString("normalized_step"));
        feedback.setNormalizedError(rs.getString("normalized_error"));
        feedback.setExceptionType(rs.getString("exception_type"));
        feedback.setLocator(rs.getString("locator"));
        feedback.setStackTracePattern(rs.getString("stack_trace_pattern"));
        feedback.setAiClassification(rs.getString("ai_classification"));
        feedback.setHumanClassification(rs.getString("human_classification"));
        feedback.setRootCause(rs.getString("root_cause"));
        int confidence = rs.getInt("confidence");
        feedback.setConfidence(rs.wasNull() ? null : confidence);
        feedback.setCreatedAt(parseInstant(rs.getString("created_at")));
        feedback.setUpdatedAt(parseInstant(rs.getString("updated_at")));
        return feedback;
    }
}

