package org.harbourcu.config;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.harbourcu.common.TraceIdFilter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Bind bounded request metadata at each pool checkout, clearing it for background work. */
@Configuration
public class AuditConnectionContext {
    @Bean
    static BeanPostProcessor auditDataSources() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource source ? new ContextDataSource(source) : bean;
            }
        };
    }

    private static final class ContextDataSource extends DelegatingDataSource implements AutoCloseable {
        ContextDataSource(DataSource source) { super(source); }
        @Override public Connection getConnection() throws SQLException { return bind(super.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return bind(super.getConnection(username, password));
        }
        @Override public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
        }

        private Connection bind(Connection connection) throws SQLException {
            String ip = "", trace = "", actor = "";
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                var request = attributes.getRequest();
                String candidate = request.getRemoteAddr();
                // Never accept raw forwarded headers or arbitrary strings as database context.
                if (candidate != null && candidate.length() <= 45 && candidate.matches("[0-9a-fA-F:.]+")) ip = candidate;
                Object authenticated = request.getAttribute(org.harbourcu.security.SessionService.AUDIT_ACTOR_ATTRIBUTE);
                if (authenticated instanceof java.util.UUID userId) actor = userId.toString();
                Object id = request.getAttribute(TraceIdFilter.ATTRIBUTE);
                if (id instanceof String value && value.matches("[0-9a-fA-F-]{36}")) trace = value;
            }
            try (var statement = connection.prepareStatement(
                    "SELECT set_config('cums.request_ip', ?, false), set_config('cums.request_id', ?, false), set_config('cums.actor_id', ?, false)")) {
                statement.setString(1, ip); statement.setString(2, trace); statement.setString(3, actor); statement.execute();
                return connection;
            } catch (SQLException failure) {
                connection.close(); throw failure;
            }
        }
    }
}
