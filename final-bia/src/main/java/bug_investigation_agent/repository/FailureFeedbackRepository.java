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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

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
                root_cause_type TEXT,
                severity TEXT,
                recommended_action TEXT,
                suggested_fix TEXT,
                similar_patterns TEXT,
                screenshot_observation TEXT,
                screenshot_hash TEXT,
                evidence_json TEXT,
                missing_evidence_json TEXT,
                prevention_tips_json TEXT,
                steps_to_reproduce_json TEXT,
                source TEXT,
                confidence INTEGER,
                created_at TEXT,
                updated_at TEXT
            )
            """;

    private static final Map<String, String> MIGRATION_COLUMNS = new LinkedHashMap<>();

    static {
        MIGRATION_COLUMNS.put("root_cause_type", "TEXT");
        MIGRATION_COLUMNS.put("severity", "TEXT");
        MIGRATION_COLUMNS.put("recommended_action", "TEXT");
        MIGRATION_COLUMNS.put("suggested_fix", "TEXT");
        MIGRATION_COLUMNS.put("similar_patterns", "TEXT");
        MIGRATION_COLUMNS.put("screenshot_observation", "TEXT");
        MIGRATION_COLUMNS.put("screenshot_hash", "TEXT");
        MIGRATION_COLUMNS.put("evidence_json", "TEXT");
        MIGRATION_COLUMNS.put("missing_evidence_json", "TEXT");
        MIGRATION_COLUMNS.put("prevention_tips_json", "TEXT");
        MIGRATION_COLUMNS.put("steps_to_reproduce_json", "TEXT");
        MIGRATION_COLUMNS.put("source", "TEXT");
    }

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
            migrateSchema(connection);
        } catch (SQLException e) {
            log.error("Failed to initialize failure_feedback schema: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to initialize failure feedback database", e);
        }
    }

    private void migrateSchema(Connection connection) throws SQLException {
        Set<String> existingColumns = loadExistingColumns(connection);
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, String> entry : MIGRATION_COLUMNS.entrySet()) {
                String column = entry.getKey();
                if (!existingColumns.contains(column.toLowerCase())) {
                    statement.execute("ALTER TABLE failure_feedback ADD COLUMN " + column + " " + entry.getValue());
                    log.info("Migrated failure_feedback schema: added missing column {}", column);
                }
            }
        }
    }

    private Set<String> loadExistingColumns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(failure_feedback)")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null) {
                    columns.add(name.toLowerCase());
                }
            }
        }
        return columns;
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
                    ai_classification, human_classification, root_cause, root_cause_type,
                    severity, recommended_action, suggested_fix, similar_patterns,
                    screenshot_observation, screenshot_hash, evidence_json, missing_evidence_json,
                    prevention_tips_json, steps_to_reproduce_json, source,
                    confidence, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    root_cause_type = excluded.root_cause_type,
                    severity = excluded.severity,
                    recommended_action = excluded.recommended_action,
                    suggested_fix = excluded.suggested_fix,
                    similar_patterns = excluded.similar_patterns,
                    screenshot_observation = excluded.screenshot_observation,
                    screenshot_hash = excluded.screenshot_hash,
                    evidence_json = excluded.evidence_json,
                    missing_evidence_json = excluded.missing_evidence_json,
                    prevention_tips_json = excluded.prevention_tips_json,
                    steps_to_reproduce_json = excluded.steps_to_reproduce_json,
                    source = excluded.source,
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
            statement.setString(13, feedback.getRootCauseType());
            statement.setString(14, feedback.getSeverity());
            statement.setString(15, feedback.getRecommendedAction());
            statement.setString(16, feedback.getSuggestedFix());
            statement.setString(17, feedback.getSimilarPatterns());
            statement.setString(18, feedback.getScreenshotObservation());
            statement.setString(19, feedback.getScreenshotHash());
            statement.setString(20, feedback.getEvidenceJson());
            statement.setString(21, feedback.getMissingEvidenceJson());
            statement.setString(22, feedback.getPreventionTipsJson());
            statement.setString(23, feedback.getStepsToReproduceJson());
            statement.setString(24, feedback.getSource());
            if (feedback.getConfidence() != null) {
                statement.setInt(25, feedback.getConfidence());
            } else {
                statement.setNull(25, java.sql.Types.INTEGER);
            }
            statement.setString(26, toText(feedback.getCreatedAt()));
            statement.setString(27, toText(feedback.getUpdatedAt()));
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
        feedback.setRootCauseType(rs.getString("root_cause_type"));
        feedback.setSeverity(rs.getString("severity"));
        feedback.setRecommendedAction(rs.getString("recommended_action"));
        feedback.setSuggestedFix(rs.getString("suggested_fix"));
        feedback.setSimilarPatterns(rs.getString("similar_patterns"));
        feedback.setScreenshotObservation(rs.getString("screenshot_observation"));
        feedback.setScreenshotHash(rs.getString("screenshot_hash"));
        feedback.setEvidenceJson(rs.getString("evidence_json"));
        feedback.setMissingEvidenceJson(rs.getString("missing_evidence_json"));
        feedback.setPreventionTipsJson(rs.getString("prevention_tips_json"));
        feedback.setStepsToReproduceJson(rs.getString("steps_to_reproduce_json"));
        feedback.setSource(rs.getString("source"));
        int confidence = rs.getInt("confidence");
        feedback.setConfidence(rs.wasNull() ? null : confidence);
        feedback.setCreatedAt(parseInstant(rs.getString("created_at")));
        feedback.setUpdatedAt(parseInstant(rs.getString("updated_at")));
        return feedback;
    }
}

