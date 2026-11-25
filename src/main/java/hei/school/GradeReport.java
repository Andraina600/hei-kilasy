package hei.school;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Grade transcript of a student.
 */
@Component
public class GradeReport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public Map<String, Object> build(long studentId) {
        List<Map<String, Object>> students = jdbcTemplate.queryForList("SELECT * FROM student WHERE id = ?", studentId);
        if (students.isEmpty()) {
            return null;
        }
        Map<String, Object> student = students.get(0);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT e.title, e.deadline, e.max_points, e.duration_minutes, es.started_at, "
                        + "s.id AS submission_id, s.submitted_at, s.raw_points, s.feedback "
                        + "FROM exercise e LEFT JOIN submission s ON s.exercise_id = e.id AND s.student_id = ? "
                        + "LEFT JOIN exercise_start es ON es.exercise_id = e.id AND es.student_id = ? "
                        + "ORDER BY e.deadline",
                studentId, studentId);

        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int graded = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            // deadline is optional for a timed exercise
            LocalDate deadline = row.get("deadline") == null ? null : ((Date) row.get("deadline")).toLocalDate();
            Integer durationMinutes = (Integer) row.get("duration_minutes");
            LocalDateTime startedAt = row.get("started_at") == null ? null : ((Timestamp) row.get("started_at")).toLocalDateTime();
            line.put("exercise", row.get("title"));
            line.put("deadline", deadline);
            // timed-only keys, so the lines of untimed exercises stay unchanged
            if (durationMinutes != null) {
                line.put("durationMinutes", durationMinutes);
                line.put("startedAt", startedAt);
            }

            if (row.get("submission_id") == null) {
                boolean timeOver = durationMinutes != null && startedAt != null
                        && LocalDateTime.now().isAfter(startedAt.plusMinutes(durationMinutes));
                if (timeOver || (deadline != null && deadline.isBefore(LocalDate.now()))) {
                    line.put("status", "NON RENDU");
                } else if (durationMinutes != null && startedAt != null) {
                    line.put("status", "EN COURS");
                } else {
                    line.put("status", "À RENDRE");
                }
                line.put("grade", null);
            } else {
                LocalDateTime submittedAt = ((Timestamp) row.get("submitted_at")).toLocalDateTime();
                line.put("submittedAt", submittedAt);
                line.put("late", deadline != null && submittedAt.toLocalDate().isAfter(deadline));
                // time used by the student on a timed exercise, from opening to submission
                if (durationMinutes != null && startedAt != null) {
                    Duration used = Duration.between(startedAt, submittedAt);
                    line.put("timeUsed", used.toMinutes() + " min " + used.toSecondsPart() + " s");
                }
                if (row.get("raw_points") == null) {
                    line.put("status", "EN ATTENTE DE CORRECTION");
                    line.put("grade", null);
                } else {
                    BigDecimal raw = (BigDecimal) row.get("raw_points");
                    BigDecimal max = (BigDecimal) row.get("max_points");
                    // late penalty: -10% per started day late, capped at -50%
                    long daysLate = deadline == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(deadline, submittedAt.toLocalDate()));
                    long penaltyPercent = Math.min(daysLate * 10, 50);
                    BigDecimal grade = raw.multiply(BigDecimal.valueOf(20 * (100 - penaltyPercent)))
                            .divide(max.multiply(BigDecimal.valueOf(100)), 1, RoundingMode.HALF_UP)
                            .max(BigDecimal.ZERO);
                    line.put("status", "CORRIGÉ");
                    line.put("penaltyPercent", penaltyPercent);
                    line.put("grade", grade);
                    line.put("feedback", row.get("feedback"));
                    total = total.add(grade);
                    graded++;
                }
            }
            lines.add(line);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reference", student.get("reference"));
        report.put("student", student.get("full_name"));
        report.put("email", student.get("email"));
        report.put("cohort", student.get("cohort"));
        report.put("exercises", lines);
        report.put("average", graded == 0 ? null : total.divide(new BigDecimal(graded), 2, RoundingMode.HALF_UP));
        return report;
    }
}
