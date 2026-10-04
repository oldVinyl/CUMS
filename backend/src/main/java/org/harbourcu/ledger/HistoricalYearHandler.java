package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Opens a missing historical period only after independently reviewed leadership submission. */
@Component
public class HistoricalYearHandler implements ApprovalActionHandler {
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;

  public HistoricalYearHandler(JdbcTemplate jdbc, PermissionService permissions) {
    this.jdbc = jdbc;
    this.permissions = permissions;
  }

  public String actionType() {
    return "PERIOD_CREATE";
  }

  public void validateSubmission(UUID maker, JsonNode input) {
    if (!permissions.hasPermission(maker, "PERIOD_UNLOCK"))
      throw new SecurityException("Historical fiscal-year setup requires leadership permission.");
    if (input == null
        || !input.isObject()
        || input.size() != 2
        || !input.path("year").isIntegralNumber()
        || !input.path("year").canConvertToInt()
        || !input.path("reason").isTextual()
        || input.path("reason").asText().isBlank()
        || input.path("reason").asText().length() > 1000)
      throw new IllegalArgumentException(
          "A historical year and source reason up to 1,000 characters are required.");
    int year = input.path("year").intValue();
    if (year < 2000 || year >= LocalDate.now(ZoneId.of("Africa/Accra")).getYear())
      throw new IllegalArgumentException(
          "Choose a historical fiscal year from 2000 through last year.");
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM fiscal_years WHERE year=?)", Boolean.class, year)))
      throw new IllegalStateException(
          "This fiscal year already exists. Use its existing close or unlock workflow.");
  }

  public void execute(UUID request, UUID checker, JsonNode input) {
    jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
    UUID maker =
        jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id=?", UUID.class, request);
    validateSubmission(maker, input);
    int year = input.path("year").intValue();
    jdbc.update(
        "INSERT INTO fiscal_years(year,start_date,end_date) VALUES(?,make_date(?,1,1),make_date(?,12,31))",
        year,
        year,
        year);
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type,details) VALUES(?,'HISTORICAL_FISCAL_YEAR_CREATED','FISCAL_YEAR',jsonb_build_object('year',?,'approvalRequestId',?::text,'makerId',?::text,'before',NULL,'after','OPEN'))",
        checker,
        year,
        request.toString(),
        maker.toString());
  }
}
