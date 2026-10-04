package org.harbourcu;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Does not use the developer database. Run with Maven -Pcontainer-tests verify. */
class ContainerPostgresSmokeIT {
  @Test
  void allMigrationsAndImmutableLedgerWorkInAFreshPostgres16Container() throws Exception {
    try (var postgres =
        new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("synthetic_acceptance")) {
      postgres.start();
      var flyway =
          Flyway.configure()
              .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
              .locations("classpath:db/migration")
              .load();
      flyway.migrate();
      assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
      assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("37");
      try (var connection =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var sql = connection.createStatement()) {
        UUID member = UUID.randomUUID(), entry = UUID.randomUUID();
        sql.executeUpdate(
            "INSERT INTO members(id,member_number,full_name,join_date) VALUES('"
                + member
                + "','TC-001','Anonymous synthetic fixture',current_date)");
        sql.executeUpdate(
            "INSERT INTO ledger_entries(id,member_id,type,component,amount,effective_date,fiscal_year,reference) VALUES('"
                + entry
                + "','"
                + member
                + "','SEED','PRINCIPAL',125.5000,current_date,extract(year FROM current_date)::int,'TC-ENTRY')");
        assertThatThrownBy(
                () -> sql.executeUpdate("DELETE FROM ledger_entries WHERE id='" + entry + "'"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("append-only");
        try (var total = sql.executeQuery("SELECT sum(amount) FROM ledger_entries")) {
          assertThat(total.next()).isTrue();
          assertThat(total.getBigDecimal(1)).isEqualByComparingTo("125.5000");
        }
      }
    }
  }
}
