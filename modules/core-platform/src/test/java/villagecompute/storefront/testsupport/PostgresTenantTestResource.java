package villagecompute.storefront.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.testcontainers.containers.PostgreSQLContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Spins up a disposable PostgreSQL instance for {@link io.quarkus.test.junit.QuarkusTest} classes that need real
 * RLS/tenant behavior.
 */
public class PostgresTenantTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String POSTGRES_IMAGE = "postgres:16.3-alpine";
    private static final String APP_DB_USER = "storefront_app";
    private static final String APP_DB_PASSWORD = "storefront_app";

    private static final String JDBC_PARAM_SSLMODE_DISABLE = "sslmode=disable";

    private PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE).withDatabaseName("storefront").withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        initializeApplicationRole();

        Map<String, String> config = new HashMap<>();
        config.put("quarkus.datasource.db-kind", "postgresql");
        String jdbcUrl = buildJdbcUrl();
        config.put("quarkus.datasource.jdbc.url", jdbcUrl);
        config.put("quarkus.datasource.username", APP_DB_USER);
        config.put("quarkus.datasource.password", APP_DB_PASSWORD);
        config.put("quarkus.hibernate-orm.database.generation", "drop-and-create");
        config.put("quarkus.hibernate-orm.log.sql", "false");
        config.put("quarkus.datasource.jdbc.transaction-isolation", "read-committed");
        config.put("tenant.rls.enabled", "true");
        config.put("test.db.kind", "postgresql");
        config.put("test.jdbc.url", jdbcUrl);
        config.put("test.db.username", APP_DB_USER);
        config.put("test.db.password", APP_DB_PASSWORD);
        return config;
    }

    private void initializeApplicationRole() {
        try (Connection connection = DriverManager.getConnection(buildJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'storefront_app') THEN
                            CREATE ROLE storefront_app WITH LOGIN PASSWORD 'storefront_app' NOSUPERUSER INHERIT;
                        END IF;
                    END $$;
                    """);
            stmt.execute("GRANT ALL PRIVILEGES ON DATABASE " + postgres.getDatabaseName() + " TO storefront_app;");
            stmt.execute("GRANT ALL ON SCHEMA public TO storefront_app;");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize PostgreSQL application role", e);
        }
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private String buildJdbcUrl() {
        String baseUrl = postgres.getJdbcUrl();
        return baseUrl.contains("?") ? baseUrl + "&" + JDBC_PARAM_SSLMODE_DISABLE
                : baseUrl + "?" + JDBC_PARAM_SSLMODE_DISABLE;
    }
}
