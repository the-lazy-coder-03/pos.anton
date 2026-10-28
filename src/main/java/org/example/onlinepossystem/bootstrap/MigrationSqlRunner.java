package org.example.onlinepossystem.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Locale;

@Component
@Profile("migrate")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MigrationSqlRunner implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(MigrationSqlRunner.class);
    private static final long MIGRATION_LOCK_ID = 721946110L;
    // Keep these IDs stable because they are persisted in app_migration_state.
    private static final String CATALOG_MIGRATION_ID = "migration.sql";
    private static final String RLS_MIGRATION_ID = "db/rls-v1.sql";
    private static final String RLS_SPECIALS_MIGRATION_ID = "db/rls-v2-specials.sql";
    private static final String RLS_HARDENING_MIGRATION_ID = "db/rls-v3-hardening.sql";
    private static final String RLS_AUDIT_MIGRATION_ID = "db/rls-v4-audit.sql";
    private static final String RLS_ADDRESS_MIGRATION_ID = "db/rls-v5-address.sql";
    private static final String RLS_DRIVERS_MIGRATION_ID = "db/rls-v6-drivers.sql";
    private static final String RLS_DRIVER_TIMES_MIGRATION_ID = "db/rls-v7-driver-times.sql";
    private static final String RLS_DRIVER_PICKUP_MIGRATION_ID = "db/rls-v8-driver-pickup.sql";
    private static final String RLS_DRIVER_RATES_MIGRATION_ID = "db/rls-v9-driver-rates.sql";
    private static final String CUSTOMER_ACCOUNT_MIGRATION_ID = "db/customer-account-v1.sql";
    private static final String CATALOG_MIGRATION_RESOURCE = "sql/migration.sql";
    private static final String RLS_MIGRATION_RESOURCE = "sql/rls-v1.sql";
    private static final String RLS_SPECIALS_MIGRATION_RESOURCE = "sql/rls-v2-specials.sql";
    private static final String RLS_HARDENING_MIGRATION_RESOURCE = "sql/rls-v3-hardening.sql";
    private static final String RLS_AUDIT_MIGRATION_RESOURCE = "sql/rls-v4-audit.sql";
    private static final String RLS_ADDRESS_MIGRATION_RESOURCE = "sql/rls-v5-address.sql";
    private static final String RLS_DRIVERS_MIGRATION_RESOURCE = "sql/rls-v6-drivers.sql";
    private static final String RLS_DRIVER_TIMES_MIGRATION_RESOURCE = "sql/rls-v7-driver-times.sql";
    private static final String RLS_DRIVER_PICKUP_MIGRATION_RESOURCE = "sql/rls-v8-driver-pickup.sql";
    private static final String RLS_DRIVER_RATES_MIGRATION_RESOURCE = "sql/rls-v9-driver-rates.sql";
    private static final String CUSTOMER_ACCOUNT_MIGRATION_RESOURCE = "sql/customer-account-v1.sql";

    private final DataSource dataSource;
    private final boolean catalogEnabled;
    private final String runtimeRole;
    private final String contextSecret;

    public MigrationSqlRunner(
            DataSource dataSource,
            @Value("${app.database.migration.enabled:true}") boolean catalogEnabled,
            @Value("${app.rls.runtime-role:pos_runtime}") String runtimeRole,
            @Value("${app.rls.context-secret:${RLS_CONTEXT_SECRET:}}") String contextSecret
    ) {
        this.dataSource = dataSource;
        this.catalogEnabled = catalogEnabled;
        this.runtimeRole = requireText(runtimeRole, "Runtime database username is required.");
        this.contextSecret = requireSecret(contextSecret);
    }

    @Override
    public void run(String... args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            requirePostgres(connection);
            acquireMigrationLock(connection);
            try {
                ensureStateTable(connection);
                if (catalogEnabled) {
                    applyCatalogMigration(connection);
                } else {
                    logger.info("{} startup runner is disabled.", CATALOG_MIGRATION_RESOURCE);
                }
                applyRlsMigration(connection);
            } finally {
                rollbackOpenTransaction(connection);
                releaseMigrationLock(connection);
            }
        }
    }

    private void applyCatalogMigration(Connection connection) throws Exception {
        // Existing installations retain and validate their original migration history.
        if (findLastAppliedChecksum(connection, CATALOG_MIGRATION_ID) != null) {
            String expected = sha256(readResource(CATALOG_MIGRATION_RESOURCE));
            if (!expected.equals(findLastAppliedChecksum(connection, CATALOG_MIGRATION_ID)))
                throw new IllegalStateException("Legacy catalogue migration changed; explicit upgrade required");
            return;
        }
        applyMigration(connection, "db/empty-schema-v1.sql", "sql/empty-schema-v1.sql", true, () -> {});
    }

    private void applyRlsMigration(Connection connection) throws Exception {
        applyMigration(connection, RLS_MIGRATION_ID, RLS_MIGRATION_RESOURCE, true, () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_SPECIALS_MIGRATION_ID, RLS_SPECIALS_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_HARDENING_MIGRATION_ID, RLS_HARDENING_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_AUDIT_MIGRATION_ID, RLS_AUDIT_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_ADDRESS_MIGRATION_ID, RLS_ADDRESS_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_DRIVERS_MIGRATION_ID, RLS_DRIVERS_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_DRIVER_TIMES_MIGRATION_ID, RLS_DRIVER_TIMES_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_DRIVER_PICKUP_MIGRATION_ID, RLS_DRIVER_PICKUP_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        applyMigration(connection, RLS_DRIVER_RATES_MIGRATION_ID, RLS_DRIVER_RATES_MIGRATION_RESOURCE, true,
                () -> setRuntimeRole(connection));
        if (findLastAppliedChecksum(connection, CATALOG_MIGRATION_ID) != null)
            applyMigration(connection, CUSTOMER_ACCOUNT_MIGRATION_ID, CUSTOMER_ACCOUNT_MIGRATION_RESOURCE, true,
                    () -> {});
        applyMigration(connection, "db/commercial-identity-v1.sql", "sql/commercial-identity-v1.sql", true,
                () -> setRuntimeRole(connection));
        synchronizeContextSecret(connection);
    }

    private void applyMigration(
            Connection connection,
            String migrationId,
            String resource,
            boolean immutable,
            SqlPreparation preparation
    ) throws Exception {
        String sql = readResource(resource);
        String checksum = sha256(sql);
        String previousChecksum = findLastAppliedChecksum(connection, migrationId);
        if (checksum.equals(previousChecksum)) {
            logger.info("{} already applied for checksum {}.", resource, shortChecksum(checksum));
            return;
        }

        if (immutable && previousChecksum != null) {
            throw new IllegalStateException("An applied immutable migration was changed: " + resource);
        }
        boolean originalAutoCommit = connection.getAutoCommit();
        if (immutable) connection.setAutoCommit(false);
        try {
            preparation.prepare();
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            recordAppliedChecksum(connection, migrationId, checksum);
            if (immutable) connection.commit();
            logger.info("{} applied successfully for checksum {}.", resource, shortChecksum(checksum));
        } catch (Exception failure) {
            if (immutable) connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void setRuntimeRole(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.runtime_role', ?, true)")) {
            statement.setString(1, runtimeRole);
            statement.execute();
        }
    }

    private void synchronizeContextSecret(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_security.rls_context_secret (singleton, secret, updated_at)
                VALUES (true, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (singleton) DO UPDATE
                    SET secret = EXCLUDED.secret, updated_at = EXCLUDED.updated_at
                """)) {
            statement.setString(1, contextSecret);
            statement.executeUpdate();
        }
    }

    private void requirePostgres(Connection connection) throws SQLException {
        String databaseName = connection.getMetaData().getDatabaseProductName();
        if (databaseName == null || !databaseName.toLowerCase(Locale.ROOT).contains("postgresql")) {
            throw new IllegalStateException("SQL migrations can only be run automatically against PostgreSQL.");
        }
    }

    private void ensureStateTable(Connection connection) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS public.app_migration_state (
                    id TEXT PRIMARY KEY,
                    flag_value TEXT NOT NULL,
                    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String findLastAppliedChecksum(Connection connection, String id) throws SQLException {
        String sql = "SELECT flag_value FROM public.app_migration_state WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("flag_value") : null;
            }
        }
    }

    private void recordAppliedChecksum(Connection connection, String id, String checksum) throws SQLException {
        String sql = """
                INSERT INTO public.app_migration_state (id, flag_value, applied_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE
                    SET flag_value = EXCLUDED.flag_value,
                        applied_at = EXCLUDED.applied_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, checksum);
            statement.executeUpdate();
        }
    }

    private void acquireMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, MIGRATION_LOCK_ID);
            statement.execute();
        }
    }

    private void releaseMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, MIGRATION_LOCK_ID);
            statement.execute();
        }
    }

    private void rollbackOpenTransaction(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private String readResource(String resource) throws Exception {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String shortChecksum(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return "<none>";
        }
        return checksum.length() <= 12 ? checksum : checksum.substring(0, 12);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private static String requireSecret(String value) {
        if (value == null || value.isBlank() || value.length() < 32) {
            throw new IllegalStateException("RLS_CONTEXT_SECRET must contain at least 32 characters.");
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlPreparation {
        void prepare() throws Exception;
    }
}
