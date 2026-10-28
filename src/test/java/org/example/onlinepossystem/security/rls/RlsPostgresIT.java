package org.example.onlinepossystem.security.rls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.example.onlinepossystem.MigrationConfiguration;
import org.example.onlinepossystem.OnlinePosSystemApplication;
import org.example.onlinepossystem.bootstrap.MigrationSqlRunner;
import org.example.onlinepossystem.driver.DriverDispatchService;
import org.example.onlinepossystem.customer.persistence.AccountBootstrapStore;
import org.example.onlinepossystem.security.api.AccountPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RlsPostgresIT {
    private static final String TEST_CONTEXT_SECRET = "test-only-rls-context-secret-at-least-32-characters";
    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String database = "rls_test_" + suffix;
    private final String owner = "rls_owner_" + suffix;
    private final String runtime = "rls_runtime_" + suffix;
    private final String password = "isolated-test-only";
    private String adminUrl;
    private String adminUser;
    private String adminPassword;
    private String jdbcUrl;
    private ConfigurableApplicationContext application;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private MockMvc mvc;
    private UserDetailsService accounts;
    private long customerA;
    private long customerB;
    private long branchOne;
    private long branchTwo;
    private long namedSuperAdmin;
    private long driver;
    private long orderA;
    private long orderB;
    private boolean rolesCreated;
    private boolean databaseCreated;

    @BeforeAll
    void startIsolatedDatabaseAndApplication() throws Exception {
        adminUrl = required("RLS_TEST_ADMIN_URL");
        adminUser = required("RLS_TEST_ADMIN_USERNAME");
        adminPassword = System.getenv().getOrDefault("RLS_TEST_ADMIN_PASSWORD", "");
        int slash = adminUrl.lastIndexOf('/');
        jdbcUrl = adminUrl.substring(0, slash + 1) + database;
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + owner + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + password + "'");
            statement.execute("CREATE ROLE " + runtime + " LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT PASSWORD '" + password + "'");
            rolesCreated = true;
            statement.execute("CREATE DATABASE " + database + " OWNER " + owner);
            databaseCreated = true;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
             var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        }
        seedLegacySchemaFixture();
        migrate();
        // Reapplying uses recorded checksums and does not duplicate policies or data.
        migrate();
        seed();
        SpringApplication app = new SpringApplication(OnlinePosSystemApplication.class);
        application = app.run(runtimeArguments());
        pool = application.getBean(HikariDataSource.class);
        jdbc = application.getBean(JdbcTemplate.class);
        var activeManager = application.getBean(PlatformTransactionManager.class);
        assertThat(activeManager).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
        assertThat(((org.springframework.orm.jpa.JpaTransactionManager) activeManager).getJpaDialect()).isInstanceOf(RlsJpaDialect.class);
        transaction = new TransactionTemplate(activeManager);
        mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) application).apply(springSecurity()).build();
        accounts = application.getBean(UserDetailsService.class);
    }

    // This suite verifies compatibility with the existing restaurant deployment.
    // Fresh commercial installs use empty-schema-v1 and are tested separately.
    private void seedLegacySchemaFixture() throws Exception {
        String sql=new org.springframework.core.io.ClassPathResource("sql/migration.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        try(var connection=ownerConnection();var statement=connection.createStatement()) {
            statement.execute(sql);
            statement.execute("CREATE TABLE public.app_migration_state(id text PRIMARY KEY,flag_value text NOT NULL,applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try(var insert=connection.prepareStatement("INSERT INTO public.app_migration_state(id,flag_value) VALUES('migration.sql',?)")) {
                insert.setString(1,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                insert.executeUpdate();
            }
        }
    }

    private void migrate() {
        SpringApplication app = new SpringApplication(MigrationConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(
                "--spring.profiles.active=migrate", "--spring.config.import=",
                "--MIGRATION_DATASOURCE_URL=" + jdbcUrl, "--MIGRATION_DATASOURCE_USERNAME=" + owner,
                "--MIGRATION_DATASOURCE_PASSWORD=" + password, "--app.rls.runtime-role=" + runtime,
                "--app.rls.context-secret=" + TEST_CONTEXT_SECRET,
                "--app.database.migration.enabled=true", "--spring.jpa.show-sql=false",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect")) {
            assertThat(context.getBean(MigrationSqlRunner.class)).isNotNull();
        }
    }

    @Test @Order(1)
    void deployedCustomerAccountCanSignInWithoutAdminAccessAndIsNotReset() throws Exception {
        var account = (AccountPrincipal) accounts.loadUserByUsername("customer");
        assertThat(account.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(account.environmentAdmin()).isFalse();
        assertThat(application.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                .matches("customer", account.getPassword())).isTrue();

        mvc.perform(post("/login").with(csrf()).param("username", "customer").param("password", "customer"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers
                        .authenticated().withUsername("customer"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"customer\",\"password\":\"customer\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        mvc.perform(get("/admin").with(user(account))).andExpect(status().isForbidden());

        String sql = new org.springframework.core.io.ClassPathResource("sql/customer-account-v1.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = ownerConnection(); var statement = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                statement.executeUpdate("UPDATE customers SET password='{noop}changed-password' WHERE email='customer'");
                statement.execute(sql);
                try (var rows = statement.executeQuery("SELECT password FROM customers WHERE email='customer'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("{noop}changed-password");
                    assertThat(rows.next()).isFalse();
                }
            } finally { c.rollback(); }
        }
    }

    private String[] runtimeArguments() {
        return new String[]{"--app.business-management.enabled=true", "--spring.profiles.active=rls-test", "--spring.config.import=",
                "--spring.datasource.url=" + jdbcUrl, "--spring.datasource.username=" + runtime,
                "--spring.datasource.password=" + password, "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.hikari.maximum-pool-size=1", "--spring.datasource.hikari.minimum-idle=1",
                "--spring.datasource.hikari.connection-timeout=5000", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.open-in-view=false", "--spring.jpa.show-sql=false",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "--app.rls.enabled=true", "--app.database.migration.enabled=false", "--server.port=0",
                "--app.rls.context-secret=" + TEST_CONTEXT_SECRET,
                "--server.address=127.0.0.1", "--ADMIN_USERNAME=environment-admin", "--ADMIN_PASSWORD=test-admin-password",
                "--jwt.secret=integration-test-only-secret-at-least-32-bytes", "--resend.api-key=",
                "--SESSION_COOKIE_SECURE=false", "--logging.level.org.springframework.security=WARN"};
    }

    private void seed() throws SQLException {
        try (Connection c = ownerConnection()) {
            customerA = insert(c, "INSERT INTO customers(email,password,access_level,role) VALUES ('a@example.com','{noop}customer-pass',0,'USER') RETURNING id");
            customerB = insert(c, "INSERT INTO customers(email,password,access_level,role) VALUES ('b@example.com','{noop}customer-pass',0,'USER') RETURNING id");
            branchOne = insert(c, "INSERT INTO customers(email,password,access_level,role) VALUES ('one@example.com','{noop}admin-pass',1,'ADMIN') RETURNING id");
            branchTwo = insert(c, "INSERT INTO customers(email,password,access_level,role) VALUES ('two@example.com','{noop}admin-pass',2,'ADMIN') RETURNING id");
            namedSuperAdmin = insert(c, "INSERT INTO customers(email,password,access_level,role) VALUES ('super@example.com','{noop}admin-pass',3,'SUPER_ADMIN') RETURNING id");
            driver = insert(c, "INSERT INTO customers(email,password,access_level,role) VALUES ('driver@example.com','{noop}driver-pass',4,'DRIVER') RETURNING id");
            orderA = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) VALUES (1,"+customerA+",'Pending',CURRENT_TIMESTAMP,'pickup') RETURNING order_id");
            orderB = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) VALUES (2,"+customerB+",'Pending',CURRENT_TIMESTAMP,'pickup') RETURNING order_id");
            try (var s = c.createStatement()) {
                s.execute("UPDATE customers SET phone1='0123456789' WHERE id=" + customerA);
                // The legacy table is intentionally not part of the active entity model.
                s.execute("CREATE TABLE orders(id bigint PRIMARY KEY, customer_name text)");
                s.execute("INSERT INTO orders VALUES(1, 'Legacy private data')");
                s.execute("ALTER TABLE orders ENABLE ROW LEVEL SECURITY; ALTER TABLE orders FORCE ROW LEVEL SECURITY");
                s.execute("GRANT SELECT ON orders TO " + runtime);
            }
        }
    }

    private long insert(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test @Order(0)
    void verifierRejectsPolicyTriggerFunctionAndGrantDrift() throws Exception {
        var changes = new ArrayList<>(List.of(
                "ALTER POLICY orders_read ON customer_order USING (true)",
                "ALTER POLICY orders_insert ON customer_order WITH CHECK (true)",
                "ALTER POLICY orders_read ON customer_order TO " + owner,
                "ALTER POLICY owner_maintenance ON customers TO PUBLIC",
                "DROP POLICY orders_read ON customer_order; CREATE POLICY orders_read ON customer_order USING (true)",
                "ALTER TABLE customers DISABLE TRIGGER guard_account_update",
                "ALTER TABLE order_menu_item DISABLE TRIGGER guard_order_relationship",
                "DROP TRIGGER guard_order_relationship ON customer_order",
                "ALTER FUNCTION app_security.actor_role() RENAME TO altered_actor_role",
                "CREATE OR REPLACE FUNCTION app_security.guard_account_update() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'",
                "GRANT EXECUTE ON FUNCTION app_security.login_credentials(text) TO PUBLIC",
                "GRANT UPDATE ON password_reset_tokens TO " + runtime,
                "GRANT DELETE ON customer_notes TO " + runtime,
                "GRANT SELECT ON customers TO PUBLIC",
                "GRANT SELECT ON customers TO " + runtime + " WITH GRANT OPTION",
                "GRANT TRUNCATE ON customer_order TO " + runtime,
                "GRANT CREATE ON DATABASE " + database + " TO " + runtime,
                "GRANT " + owner + " TO " + runtime,
                "GRANT SET ON PARAMETER session_replication_role TO " + runtime,
                "CREATE POLICY leaked_legacy ON orders FOR SELECT USING (true)"
        ));
        for (String table : RlsRuntimeVerifier.REQUIRED_POLICIES.keySet()) {
            changes.add("CREATE POLICY leaked_rows ON " + table + " FOR SELECT USING (true)");
            changes.add("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
        }
        for (String change : changes) {
            // Transactional DDL and SET LOCAL ROLE let the verifier observe each unsafe
            // configuration as runtime, without exposing it to another test connection.
            try (Connection c = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword)) {
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                    s.execute(change);
                    s.execute("SET LOCAL ROLE " + runtime);
                    assertThatThrownBy(() -> RlsRuntimeVerifier.verify(c)).as(change)
                            .isInstanceOf(IllegalStateException.class);
                } finally { c.rollback(); }
            }
        }
        try (Connection c = pool.getConnection()) { RlsRuntimeVerifier.verify(c); }
    }

    @Test @Order(0)
    void disabledProtectionTriggerPreventsApplicationStartup() throws Exception {
        try (Connection c = ownerConnection(); var s = c.createStatement()) {
            s.execute("ALTER TABLE customers DISABLE TRIGGER guard_account_update");
            try {
                assertThatThrownBy(() -> {
                    try (var ignored = new SpringApplication(OnlinePosSystemApplication.class).run(runtimeArguments())) { }
                }).hasStackTraceContaining("RLS triggers differ from the reviewed security contract");
            } finally { s.execute("ALTER TABLE customers ENABLE TRIGGER guard_account_update"); }
        }
    }

    @Test @Order(0)
    void bearerIdentityOverridesSessionWithoutChangingIt() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .param("username", "environment-admin").param("password", "test-admin-password"))
                .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
        String token = application.getBean(org.example.onlinepossystem.security.api.TokenService.class)
                .generateToken(accounts.loadUserByUsername("a@example.com"));
        mvc.perform(get("/api/admin/orders").session(session).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/orders").session(session)).andExpect(status().isOk());
    }

    @Test @Order(1)
    void runtimeIsRestrictedAndEveryProtectedTableHasForcedRls() throws Exception {
        try (Connection c = pool.getConnection()) { RlsRuntimeVerifier.verify(c); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity AND c.relforcerowsecurity", Integer.class)).isEqualTo(RlsRuntimeVerifier.REQUIRED_POLICIES.size() + 1);
        int childPolicyCount = RlsRuntimeVerifier.REQUIRED_POLICIES.values().stream()
                .flatMap(Arrays::stream).mapToInt(policy -> policy.startsWith("child_") ? 1 : 0).sum();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname='public' AND policyname LIKE 'child_%'", Integer.class))
                .isEqualTo(childPolicyCount);
        try (Connection c = ownerConnection()) {
            assertThatThrownBy(() -> RlsRuntimeVerifier.verify(c)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE customer_order CASCADE")).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> jdbc.execute("SET ROLE " + owner)).hasRootCauseInstanceOf(SQLException.class);
    }

    @Test @Order(1)
    void seedsCompleteKenridgeAndUitzichtSpecialMatricesAsReadOnlyPublicData() {
        Map<String, BigDecimal> prices = jdbc.query(
                "SELECT code, bundle_price FROM special ORDER BY code",
                result -> {
                    Map<String, BigDecimal> values = new LinkedHashMap<>();
                    while (result.next()) values.put(result.getString(1), result.getBigDecimal(2));
                    return values;
                });

        assertThat(prices).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("KEN-MON-2-LARGE-FAVOURITES", new BigDecimal("234.00")),
                Map.entry("KEN-MON-STEAK-BURGERS", new BigDecimal("330.00")),
                Map.entry("KEN-TUE-FAVOURITE-SUPREME", new BigDecimal("246.00")),
                Map.entry("KEN-TUE-CHEESE-BURGERS", new BigDecimal("170.00")),
                Map.entry("KEN-WED-2-LARGE-SUPREMES", new BigDecimal("260.00")),
                Map.entry("KEN-WED-TOASTIES", new BigDecimal("135.00")),
                Map.entry("KEN-THU-RIBS", new BigDecimal("290.00")),
                Map.entry("KEN-SUN-PASTA", new BigDecimal("169.00")),
                Map.entry("KEN-SUN-RIBS-PIZZAS", new BigDecimal("365.00")),
                Map.entry("UIT-MON-2-LARGE-FAVOURITES", new BigDecimal("225.00")),
                Map.entry("UIT-MON-STEAK-BURGERS", new BigDecimal("295.00")),
                Map.entry("UIT-TUE-FAVOURITE-SUPREME", new BigDecimal("236.00")),
                Map.entry("UIT-TUE-CHEESE-BURGERS", new BigDecimal("160.00")),
                Map.entry("UIT-WED-2-LARGE-SUPREMES", new BigDecimal("247.00")),
                Map.entry("UIT-WED-TOASTIES", new BigDecimal("120.00")),
                Map.entry("UIT-THU-RIBS", new BigDecimal("276.00")),
                Map.entry("UIT-SUN-PASTA", new BigDecimal("159.00")),
                Map.entry("UIT-SUN-RIBS-PIZZAS", new BigDecimal("360.00"))
        ));
        assertThat(jdbc.queryForList("SELECT DISTINCT day_of_week FROM special_day ORDER BY day_of_week", Integer.class))
                .containsExactly(1, 2, 3, 4, 7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM special WHERE branch_id=1", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM special WHERE branch_id=2", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM special_component", Integer.class)).isEqualTo(38);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM special_component_menu_item", Integer.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM special_component_pizza", Integer.class)).isEqualTo(68);
        assertThat(jdbc.queryForObject("SELECT price FROM special_addon a JOIN special s ON s.special_id=a.special_id WHERE s.code='KEN-THU-RIBS'", BigDecimal.class))
                .isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT price FROM special_addon a JOIN special s ON s.special_id=a.special_id WHERE s.code='UIT-THU-RIBS'", BigDecimal.class))
                .isEqualByComparingTo("95.00");
        assertThat(jdbc.update("UPDATE special SET active=false WHERE code='UIT-MON-STEAK-BURGERS'"))
                .isEqualTo(1);
        assertThat(jdbc.update("UPDATE special SET active=true WHERE code='UIT-MON-STEAK-BURGERS'"))
                .isEqualTo(1);
    }

    @Test @Order(2)
    void customersAndBothBranchesAreIsolatedWithoutJavaFilters() {
        as("a@example.com");
        assertThat(ids("customer_order", "order_id")).containsExactly(orderA);
        assertThat(ids("customers", "id")).containsExactly(customerA);
        as("b@example.com");
        assertThat(ids("customer_order", "order_id")).containsExactly(orderB);
        assertThat(ids("customers", "id")).containsExactly(customerB);
        as("one@example.com");
        assertThat(ids("customer_order", "order_id")).containsExactly(orderA);
        assertThat(ids("customers", "id")).containsExactlyInAnyOrder(branchOne, customerA);
        as("two@example.com");
        assertThat(ids("customer_order", "order_id")).containsExactly(orderB);
        assertThat(ids("customers", "id")).containsExactlyInAnyOrder(branchTwo, customerB);
        as("environment-admin");
        assertThat(ids("customer_order", "order_id")).contains(orderA, orderB);
        as("driver@example.com");
        assertThat(ids("customer_order", "order_id")).isEmpty();
        assertThat(ids("customers", "id")).containsExactly(driver);
    }

    @Test @Order(3)
    void mutationsHavePositiveControlsAndRejectWrongOwnership() {
        as("a@example.com");
        long own = transaction.execute(s -> jdbc.queryForObject("INSERT INTO customer_order(branch_id,customer_id,created_at,order_type,status) VALUES(2,?,CURRENT_TIMESTAMP,'pickup','Pending') RETURNING order_id", Long.class, customerA));
        assertThat(own).isPositive(); // Customers may choose any public branch for their own order.
        denied(() -> transaction.<Integer>execute(s -> jdbc.update("INSERT INTO customer_order(branch_id,customer_id,created_at,order_type,status) VALUES(1,?,CURRENT_TIMESTAMP,'pickup','Pending')", customerB)));
        denied(() -> transaction.<Integer>execute(s -> jdbc.update("UPDATE customers SET access_level=3,role='SUPER_ADMIN' WHERE id=?", customerA)));
        assertThat(update("UPDATE customers SET first_name='A' WHERE id=?", customerA)).isEqualTo(1);
        assertThat(update("UPDATE customers SET first_name='Hacked' WHERE id=?", customerB)).isZero();
        assertThat(update("UPDATE customer_order SET status='Completed' WHERE order_id=?", orderB)).isZero();
        assertThat(update("DELETE FROM customer_order WHERE order_id=?", orderB)).isZero();
        as("one@example.com");
        assertThat(update("UPDATE customer_order SET status='Preparing' WHERE order_id=?", orderA)).isEqualTo(1);
        assertThat(update("UPDATE customer_order SET status='Completed' WHERE order_id=?", orderB)).isZero();
        denied(() -> transaction.<Integer>execute(s -> jdbc.update("INSERT INTO customer_order(branch_id,customer_id,created_at,order_type,status) VALUES(2,?,CURRENT_TIMESTAMP,'pickup','Pending')", customerA)));
        denied(() -> transaction.<Integer>execute(s -> jdbc.update("UPDATE customer_order SET branch_id=2 WHERE order_id=?", orderA)));
        denied(() -> transaction.<Integer>execute(s -> jdbc.update("UPDATE customer_order SET customer_id=? WHERE order_id=?", customerB, orderA)));
        assertThat(update("DELETE FROM customer_order WHERE order_id=?", orderB)).isZero();
        as("environment-admin");
        assertThat(update("DELETE FROM customer_order WHERE order_id=?", own)).isEqualTo(1);
    }

    @Test @Order(4)
    void missingEmptyMalformedAndUnknownContextsExposeNothing() {
        for (String table : RlsRuntimeVerifier.REQUIRED_POLICIES.keySet()) {
            if (table.equals("password_reset_tokens")) {
                assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class))
                        .hasRootCauseInstanceOf(SQLException.class);
            } else {
                Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
                assertThat(count).as(table).isZero();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        transaction.executeWithoutResult(s -> {
            jdbc.execute("SELECT set_config('app.customer_id', '', true), set_config('app.role','ADMIN',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class)).isZero();
            jdbc.execute("SELECT set_config('app.customer_id', 'invalid', true), set_config('app.role','UNKNOWN',true)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isZero();
        });
        denied(() -> transaction.<Integer>execute(s -> jdbc.update("INSERT INTO customer_order(branch_id,customer_id,created_at,order_type,status) VALUES(1,?,CURRENT_TIMESTAMP,'pickup','Pending')", customerA)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_item", Integer.class)).isPositive();
    }

    @Test @Order(4)
    void signedContextRejectsChangedIdsInvalidKeysAndCrossTransactionReplay() {
        as("a@example.com");
        String capturedSignature = transaction.execute(s -> {
            String signature = jdbc.queryForObject(
                    "SELECT current_setting('app.context_signature', true)", String.class);
            jdbc.queryForList("SELECT set_config('app.customer_id', ?, true), set_config('app.role', 'SUPER_ADMIN', true)",
                    Long.toString(customerB));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class)).isZero();
            return signature;
        });
        assertThat(capturedSignature).isNotBlank();

        SecurityContextHolder.clearContext();
        transaction.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT set_config('app.customer_id', ?, true), set_config('app.context_signature', ?, true), "
                            + "set_config('app.role', 'SUPER_ADMIN', true)",
                    Long.toString(customerA), capturedSignature);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class)).isZero();
        });
        transaction.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT set_config('app.customer_id', ?, true), set_config('app.context_signature', ?, true)",
                    Long.toString(customerA), "0".repeat(64));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class)).isZero();
        });
    }

    @Test @Order(4)
    void wrongSigningKeyFailsRuntimeVerification() throws Exception {
        try (Connection c = pool.getConnection()) {
            assertThatThrownBy(() -> RlsRuntimeVerifier.verify(c,
                    "different-test-signing-key-with-at-least-32-characters"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match");
        }
    }

    @Test @Order(5)
    void singleConnectionNeverLeaksContextAfterCommitRollbackOrFailedInitialization() {
        assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
        int backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        for (String name : List.of("a@example.com", "b@example.com", "one@example.com", "environment-admin")) {
            as(name);
            transaction.executeWithoutResult(s -> {
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backend);
                assertThat(jdbc.queryForObject("SELECT current_setting('app.role', true)", String.class)).isBlank();
                assertThat(jdbc.queryForObject("SELECT current_setting('app.context_signature', true)", String.class)).isNotBlank();
                assertThat(jdbc.queryForObject("SELECT app_security.actor_role()", String.class)).isNotBlank();
            });
            transaction.executeWithoutResult(s -> s.setRollbackOnly());
            SecurityContextHolder.clearContext();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT nullif(current_setting('app.customer_id',true),'')", String.class)).isNull();
        }
        RlsContextInitializer failing = new RlsContextInitializerForTest();
        var manager = new org.springframework.orm.jpa.JpaTransactionManager(application.getBean(EntityManagerFactory.class));
        manager.setJpaDialect(new RlsJpaDialect(failing));
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(s -> null)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class)).isZero();
        as("b@example.com");
        assertThat(ids("customer_order", "order_id")).containsExactly(orderB);
    }

    @Test @Order(6)
    void sameJpaConnectionJoinedAndRequiresNewTransactionsUseCorrectContext() {
        // REQUIRES_NEW needs a second connection while its parent holds the first.
        pool.setMaximumPoolSize(2);
        try {
            as("a@example.com");
            transaction.executeWithoutResult(status -> {
                EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(application.getBean(EntityManagerFactory.class));
                assertThat(((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue())
                        .isEqualTo(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                assertThat(ids("customer_order", "order_id")).containsExactly(orderA);
                var previous = SecurityContextHolder.getContext().getAuthentication();
                var b = new AccountPrincipal("b@example.com", "erased", customerB, 0, false);
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(b, null, b.getAuthorities()));
                TransactionTemplate inner = new TransactionTemplate(application.getBean(PlatformTransactionManager.class));
                inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                inner.executeWithoutResult(s -> assertThat(jdbc.queryForList("SELECT order_id FROM customer_order", Long.class)).containsExactly(orderB));
                SecurityContextHolder.getContext().setAuthentication(previous);
                assertThat(jdbc.queryForList("SELECT order_id FROM customer_order", Long.class)).containsExactly(orderA);
            });
        } finally { pool.setMaximumPoolSize(1); }
    }

    @Test @Order(7)
    void loginRegistrationRecoveryAndPublicRoutesWork() throws Exception {
        mvc.perform(get("/api/orders/menu").param("branch", "Kenridge")).andExpect(status().isOk());
        mvc.perform(get("/api/orders").param("branch", "Kenridge")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/staff/login").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
                .andExpect(status().isGone());
        mvc.perform(post("/login").with(csrf()).param("username", "0123456789").param("password", "customer-pass"))
                .andExpect(status().is3xxRedirection()).andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a@example.com\",\"password\":\"customer-pass\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        AccountBootstrapStore store = application.getBean(AccountBootstrapStore.class);
        var c = new org.example.onlinepossystem.customer.entity.Customer();
        c.setEmail("new@example.com"); c.setPassword("{noop}new-pass"); c.setRole("SUPER_ADMIN"); c.setAccessLevel(3);
        var created = store.register(c);
        assertThat(store.findCredentials("new@example.com").orElseThrow().accessLevel()).isZero();
        String hash = "a".repeat(64);
        assertThat(store.createReset("new@example.com", hash, LocalDateTime.now().plusMinutes(30))).contains("new@example.com");
        assertThat(store.consumeReset(hash, "{noop}changed")).contains("new@example.com");
        assertThat(store.consumeReset(hash, "{noop}reuse")).isEmpty();
        assertThat(store.findCredentials("new@example.com").orElseThrow().password()).isEqualTo("{noop}changed");
        String expiredHash = "c".repeat(64);
        assertThat(store.createReset("new@example.com", expiredHash, LocalDateTime.now().plusMinutes(30))).contains("new@example.com");
        try (Connection ownerDb = ownerConnection(); var statement = ownerDb.createStatement()) {
            statement.executeUpdate("UPDATE password_reset_tokens SET expires_at=LOCALTIMESTAMP - interval '1 minute' WHERE token_hash='" + expiredHash + "'");
        }
        assertThat(store.consumeReset(expiredHash, "{noop}expired")).isEmpty();
        assertThat(store.findCredentials("new@example.com").orElseThrow().password()).isEqualTo("{noop}changed");
        assertThat(created.getId()).isPositive();
    }

    @Test @Order(8)
    void accessChangesApplyToAlreadyAuthenticatedPrincipal() throws Exception {
        as("one@example.com");
        try (Connection c = ownerConnection(); var s = c.createStatement()) {
            s.execute("UPDATE customers SET access_level=2 WHERE id=" + branchOne);
        }
        try { assertThat(ids("customer_order", "order_id")).containsExactly(orderB); }
        finally {
            try (Connection c = ownerConnection(); var s = c.createStatement()) {
                s.execute("UPDATE customers SET access_level=1 WHERE id=" + branchOne);
            }
        }
    }

    @Test @Order(9)
    void allOrderDescendantsFollowTheirParentsAndCannotBeReassigned() throws Exception {
        long[] menus = new long[2];
        long[] pizzas = new long[2];
        try (Connection c = ownerConnection()) {
            long[] orders = {orderA, orderB};
            for (int i=0; i<orders.length; i++) {
                menus[i] = insert(c, "INSERT INTO order_menu_item(order_id,menu_item_id,qty,unit_price_at_time) SELECT " + orders[i]
                        + ",burger_id,1,80 FROM burger_recipe_assignment ORDER BY burger_id LIMIT 1 RETURNING order_menu_item_id");
                pizzas[i] = insert(c, "INSERT INTO order_pizza_item(order_id,pizza_id,pizza_size_id,qty,base_price_at_time) SELECT " + orders[i]
                        + ",pizza_id,pizza_size_id,1,90 FROM branch_pizza_price ORDER BY pizza_id,pizza_size_id LIMIT 1 RETURNING order_pizza_item_id");
                try (var st = c.createStatement()) {
                    st.execute("INSERT INTO order_menu_item_extra(order_menu_item_id,name,qty,unit_price_at_time) VALUES("+menus[i]+",'Extra',1,1)");
                    st.execute("INSERT INTO order_burger_protein(order_menu_item_id,component_id,protein_qty_per_burger,unit_price_at_time) SELECT "
                            +menus[i]+",bc.component_id,a.protein_quantity_required,0 FROM burger_component bc CROSS JOIN burger_recipe_assignment a "
                            +"JOIN order_menu_item omi ON omi.menu_item_id=a.burger_id WHERE omi.order_menu_item_id="+menus[i]
                            +" AND bc.component_type='protein' AND bc.active ORDER BY bc.component_id LIMIT 1");
                    st.execute("INSERT INTO order_burger_removed_component(order_menu_item_id,component_id) SELECT "
                            +menus[i]+",rc.component_id FROM order_menu_item omi JOIN burger_recipe_assignment a ON a.burger_id=omi.menu_item_id "
                            +"JOIN burger_recipe_component rc ON rc.recipe_id=a.recipe_id WHERE omi.order_menu_item_id="+menus[i]
                            +" AND rc.is_removable ORDER BY rc.component_id LIMIT 1");
                    st.execute("INSERT INTO order_burger_extra_component(order_menu_item_id,component_id,qty,unit_price_at_time) SELECT "
                            +menus[i]+",component_id,1,1 FROM burger_component WHERE component_type<>'protein' AND active ORDER BY component_id LIMIT 1");
                    st.execute("INSERT INTO order_pizza_item_extra(order_pizza_item_id,ingredient_id,qty,unit_price_at_time) SELECT "
                            +pizzas[i]+",ingredient_id,1,1 FROM ingredient ORDER BY ingredient_id LIMIT 1");
                    st.execute("INSERT INTO order_pizza_item_base_option(order_pizza_item_id,pizza_base_option_id,unit_price_at_time) SELECT "
                            +pizzas[i]+",pizza_base_option_id,1 FROM pizza_base_option ORDER BY pizza_base_option_id LIMIT 1");
                }
            }
        }
        Map<String,String> parentColumns = Map.of(
                "order_menu_item", "order_id", "order_pizza_item", "order_id",
                "order_menu_item_extra", "order_menu_item_id", "order_burger_protein", "order_menu_item_id",
                "order_burger_removed_component", "order_menu_item_id", "order_burger_extra_component", "order_menu_item_id",
                "order_pizza_item_extra", "order_pizza_item_id", "order_pizza_item_base_option", "order_pizza_item_id");
        for (var entry : parentColumns.entrySet()) {
            String table = entry.getKey(); String key = entry.getValue();
            long a = key.equals("order_id") ? orderA : key.equals("order_menu_item_id") ? menus[0] : pizzas[0];
            long b = key.equals("order_id") ? orderB : key.equals("order_menu_item_id") ? menus[1] : pizzas[1];
            as("a@example.com");
            assertThat(transaction.<List<Long>>execute(t -> jdbc.queryForList("SELECT "+key+" FROM "+table, Long.class))).as(table).containsExactly(a);
            assertThat(transaction.<Integer>execute(t -> jdbc.update("DELETE FROM "+table+" WHERE "+key+"=?", b))).isZero();
            as("b@example.com");
            assertThat(transaction.<List<Long>>execute(t -> jdbc.queryForList("SELECT "+key+" FROM "+table, Long.class))).containsExactly(b);
            as("one@example.com");
            assertThat(transaction.<List<Long>>execute(t -> jdbc.queryForList("SELECT "+key+" FROM "+table, Long.class))).containsExactly(a);
            as("two@example.com");
            assertThat(transaction.<List<Long>>execute(t -> jdbc.queryForList("SELECT "+key+" FROM "+table, Long.class))).containsExactly(b);
            as("environment-admin");
            assertThat(transaction.<List<Long>>execute(t -> jdbc.queryForList("SELECT "+key+" FROM "+table, Long.class))).containsExactlyInAnyOrder(a,b);
            denied(() -> transaction.<Integer>execute(t -> jdbc.update("UPDATE "+table+" SET "+key+"=? WHERE "+key+"=?", b,a)));
        }
        as("a@example.com");
        denied(() -> transaction.<Integer>execute(t -> jdbc.update("INSERT INTO order_menu_item(order_id,menu_item_id,qty,unit_price_at_time) VALUES(?,201,1,80)", orderB)));
        denied(() -> transaction.<Integer>execute(t -> jdbc.update("INSERT INTO order_menu_item_extra(order_menu_item_id,name,qty,unit_price_at_time) VALUES(?,'Forbidden',1,1)", menus[1])));
        assertThat(transaction.<Integer>execute(t -> jdbc.update("INSERT INTO order_menu_item_extra(order_menu_item_id,name,qty,unit_price_at_time) VALUES(?,'Allowed',1,1)", menus[0]))).isEqualTo(1);
    }

    @Test @Order(10)
    void parallelRequestsAndCompetingResetConsumptionRemainIsolated() throws Exception {
        var aPrincipal = accounts.loadUserByUsername("a@example.com");
        var bPrincipal = accounts.loadUserByUsername("b@example.com");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Callable<List<Long>>> work = new ArrayList<>();
            for (int i=0; i<12; i++) {
                final var principal = i%2 == 0 ? aPrincipal : bPrincipal;
                work.add(() -> {
                    try {
                        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
                        return ids("customer_order", "order_id");
                    } finally { SecurityContextHolder.clearContext(); }
                });
            }
            var results = workers.invokeAll(work);
            for (int i=0; i<results.size(); i++) assertThat(results.get(i).get()).containsExactly(i%2==0 ? orderA : orderB);
            SecurityContextHolder.clearContext();
            var store = application.getBean(AccountBootstrapStore.class);
            String hash = "b".repeat(64);
            assertThat(store.createReset("new@example.com", hash, LocalDateTime.now().plusMinutes(30))).contains("new@example.com");
            try (Connection c = ownerConnection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM password_reset_tokens WHERE token_hash='" + hash + "' AND NOT used")) {
                rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);
            }
            // Separate physical connections exercise the database lock and token recheck.
            pool.setMaximumPoolSize(2);
            List<Callable<Boolean>> resets = List.of(
                    () -> store.consumeReset(hash,"{noop}winner-one").isPresent(),
                    () -> store.consumeReset(hash,"{noop}winner-two").isPresent());
            var resetResults = workers.invokeAll(resets);
            assertThat(List.of(resetResults.get(0).get(), resetResults.get(1).get())).containsExactlyInAnyOrder(true,false);
        } finally { pool.setMaximumPoolSize(1); workers.shutdownNow(); }
    }

    @Test @Order(11)
    void publicMenuCustomerOrderHistoryAndBranchAdminApiSmoke() throws Exception {
        var customer = accounts.loadUserByUsername("a@example.com");
        var branchAdmin = accounts.loadUserByUsername("one@example.com");
        var namedSuper = accounts.loadUserByUsername("super@example.com");
        var environmentAdmin = accounts.loadUserByUsername("environment-admin");
        var driverAccount = accounts.loadUserByUsername("driver@example.com");
        String orderRequest = "{\"customerName\":\"Named Admin\",\"branchName\":\"Uitzicht\",\"items\":[{\"menuItemId\":101,\"quantity\":1}]}";
        var loginRedirect = mvc.perform(get("/order"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/login")))
                .andReturn();
        var namedSuperSession = (MockHttpSession) loginRedirect.getRequest().getSession(false);
        mvc.perform(post("/login").session(namedSuperSession).with(csrf())
                        .with(request -> { request.setRemoteAddr("198.51.100.20"); return request; })
                        .param("username", "super@example.com").param("password", "admin-pass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/order")));
        mvc.perform(get("/api/orders/menu").param("branch", "Kenridge"))
                .andExpect(status().isOk());
        String customerOrderKey = "rls-customer-order-0001";
        String customerOrderBody = "{\"customerName\":\"A\",\"branchName\":\"Kenridge\",\"items\":[{\"menuItemId\":101,\"quantity\":1}]}";
        var firstCustomerOrder = mvc.perform(post("/api/orders").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(user(customer))
                        .header("Idempotency-Key", customerOrderKey).contentType(MediaType.APPLICATION_JSON)
                        .content(customerOrderBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber()).andReturn();
        var replayedCustomerOrder = mvc.perform(post("/api/orders").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(user(customer))
                        .header("Idempotency-Key", customerOrderKey).contentType(MediaType.APPLICATION_JSON)
                        .content(customerOrderBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber()).andReturn();
        assertThat(application.getBean(ObjectMapper.class).readTree(replayedCustomerOrder.getResponse().getContentAsString()).path("id").asLong())
                .isEqualTo(application.getBean(ObjectMapper.class).readTree(firstCustomerOrder.getResponse().getContentAsString()).path("id").asLong());
        String concurrentKey = "rls-concurrent-order-0001";
        ExecutorService replayWorkers = Executors.newFixedThreadPool(2);
        try {
            pool.setMaximumPoolSize(2);
            Callable<Long> submit = () -> {
                var response = mvc.perform(post("/api/orders").with(csrf()).with(user(customer))
                                .header("Idempotency-Key", concurrentKey).contentType(MediaType.APPLICATION_JSON)
                                .content(customerOrderBody))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                return application.getBean(ObjectMapper.class).readTree(response).path("id").asLong();
            };
            var submissions = replayWorkers.invokeAll(List.of(submit, submit));
            assertThat(submissions.get(0).get()).isEqualTo(submissions.get(1).get());
        } finally {
            pool.setMaximumPoolSize(1);
            replayWorkers.shutdownNow();
        }
        mvc.perform(get("/profile/edit").with(user(customer)))
                .andExpect(status().isOk());
        mvc.perform(get("/order").session(namedSuperSession))
                .andExpect(status().isOk()).andExpect(view().name("PlaceOrder"));
        var superOrder = mvc.perform(post("/api/orders").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).session(namedSuperSession)
                        .header("Idempotency-Key", "rls-super-order-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(orderRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        long superOrderId = application.getBean(ObjectMapper.class)
                .readTree(superOrder.getResponse().getContentAsString()).path("id").asLong();
        try (Connection c = ownerConnection(); var statement = c.prepareStatement(
                "SELECT customer_id, branch_id FROM customer_order WHERE order_id=?")) {
            statement.setLong(1, superOrderId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("customer_id")).isEqualTo(namedSuperAdmin);
                assertThat(result.getLong("branch_id")).isEqualTo(2);
            }
        }
        mvc.perform(get("/order").with(user(environmentAdmin))).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(user(environmentAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content(orderRequest))
                .andExpect(status().isForbidden());
        mvc.perform(get("/profile/edit").with(user(environmentAdmin))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/orders").with(user(environmentAdmin)))
                .andExpect(status().isOk());
        try (Connection c = ownerConnection(); var statement = c.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM customers WHERE environment_admin")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
        }
        for (var blocked : List.of(branchAdmin, driverAccount)) {
            mvc.perform(get("/order").with(user(blocked))).andExpect(status().isForbidden());
            mvc.perform(post("/api/orders").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(user(blocked))
                    .contentType(MediaType.APPLICATION_JSON).content(orderRequest))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/orders").with(user(branchAdmin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].branchId").value(1));
        mvc.perform(get("/api/admin/orders").with(user(branchAdmin)).param("branchId", "2"))
                .andExpect(status().isForbidden());
    }

    @Test @Order(12)
    void driverDispatchIsBranchScopedAndCashUpUsesPersistedPrices() throws Exception {
        DriverDispatchService dispatch = application.getBean(DriverDispatchService.class);
        long newDriver;
        long firstOrder;
        long secondOrder;
        long otherBranchOrder;
        try (Connection c = ownerConnection()) {
            newDriver = insert(c, "INSERT INTO customers(email,password,access_level,role,first_name,last_name) "
                    + "VALUES ('dispatch-driver@example.com','{noop}pass',0,'USER','Test','Driver') RETURNING id");
            firstOrder = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type,customer_name) "
                    + "VALUES (1," + customerA + ",'Pending',now()-interval '2 minutes','delivery','Customer A') RETURNING order_id");
            secondOrder = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type,customer_name) "
                    + "VALUES (1," + customerA + ",'Pending',now()-interval '1 minute','delivery','Customer A') RETURNING order_id");
            otherBranchOrder = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type,customer_name) "
                    + "VALUES (2," + customerB + ",'Pending',now(),'delivery','Customer B') RETURNING order_id");
            try (var statement = c.createStatement()) {
                statement.execute("INSERT INTO order_menu_item(order_id,menu_item_id,qty,unit_price_at_time) "
                        + "SELECT " + firstOrder + ",burger_id,1,100 FROM burger_recipe_assignment ORDER BY burger_id LIMIT 1");
                statement.execute("INSERT INTO order_menu_item(order_id,menu_item_id,qty,unit_price_at_time) "
                        + "SELECT " + secondOrder + ",burger_id,1,100 FROM burger_recipe_assignment ORDER BY burger_id LIMIT 1");
            }
        }

        as("one@example.com");
        var manager = SecurityContextHolder.getContext().getAuthentication();
        var initialManager = manager;
        assertThat(dispatch.promote(manager, "dispatch-driver@example.com", 1).id()).isEqualTo(newDriver);
        dispatch.updateRates(manager, newDriver, 1, new BigDecimal("60.00"), new BigDecimal("10.00"));
        assertThat(dispatch.managerDashboard(manager, 1).drivers()).extracting(DriverDispatchService.Profile::id)
                .contains(newDriver);
        assertThatThrownBy(() -> dispatch.managerDashboard(initialManager, 2))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        mvc.perform(get("/api/driver").with(user(accounts.loadUserByUsername("one@example.com"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/dispatch/drivers").with(user(accounts.loadUserByUsername("one@example.com")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"b@example.com\",\"branchId\":2}"))
                .andExpect(status().isForbidden());

        as("dispatch-driver@example.com");
        var driverAuth = SecurityContextHolder.getContext().getAuthentication();
        var initialDriver = driverAuth;
        mvc.perform(post("/api/driver/clock-in")
                .with(user(accounts.loadUserByUsername("dispatch-driver@example.com"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/driver").with(user(accounts.loadUserByUsername("a@example.com"))))
                .andExpect(status().isForbidden());
        as("dispatch-driver@example.com");
        assertThat(ids("customer_order", "order_id")).isEmpty();
        var shift = dispatch.clockIn(driverAuth);
        String token = new ObjectMapper().readTree(mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"dispatch-driver@example.com\",\"password\":\"pass\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("ROLE_DRIVER"))
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(post("/api/driver/clock-in").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(shift.id()));
        as("dispatch-driver@example.com");
        assertThat(shift.hourlyRate()).isEqualByComparingTo("60.00");
        assertThatCode(() -> dispatch.claimNext(initialDriver)).doesNotThrowAnyException();
        assertThat(dispatch.driverDashboard(driverAuth).deliveries()).extracting(d -> d.order().getId())
                .containsExactly(firstOrder);
        assertThatThrownBy(() -> dispatch.claimNext(initialDriver)).isInstanceOf(RuntimeException.class);
        assertThat(ids("customer_order", "order_id")).containsExactly(firstOrder);
        assertThat(update("UPDATE customer_order SET status='Completed' WHERE order_id=?", firstOrder)).isZero();
        assertThatThrownBy(() -> dispatch.delivered(initialDriver, firstOrder)).isInstanceOf(IllegalStateException.class);

        as("one@example.com");
        manager = SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(post("/api/admin/dispatch/assignments")
                .with(user(accounts.loadUserByUsername("one@example.com")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":" + secondOrder + ",\"driverId\":" + newDriver + ",\"branchId\":1}"))
                .andExpect(status().isForbidden());
        as("one@example.com");
        manager = SecurityContextHolder.getContext().getAuthentication();
        dispatch.updateRates(manager, newDriver, 1, new BigDecimal("80.00"), new BigDecimal("15.00"));
        dispatch.assign(manager, secondOrder, newDriver, 1);
        as("dispatch-driver@example.com");
        assertThatThrownBy(() -> dispatch.claimNext(initialDriver)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> dispatch.clockOut(initialDriver)).isInstanceOf(IllegalStateException.class);
        as("one@example.com");
        assertThat(update("UPDATE customer_order SET status='Completed' WHERE order_id=?", firstOrder)).isEqualTo(1);
        assertThat(update("UPDATE customer_order SET status='Completed' WHERE order_id=?", secondOrder)).isEqualTo(1);
        as("dispatch-driver@example.com");
        driverAuth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(ids("customer_order", "order_id")).containsExactly(firstOrder, secondOrder);
        assertThat(ids("customer_order", "order_id")).doesNotContain(otherBranchOrder);
        dispatch.pickup(driverAuth, firstOrder);
        dispatch.delivered(driverAuth, firstOrder);
        dispatch.pickup(driverAuth, secondOrder);
        dispatch.delivered(driverAuth, secondOrder);
        assertThat(dispatch.returning(driverAuth).returningAt()).isNotNull();
        var recordedClockOut = dispatch.clockOut(driverAuth);
        assertThat(dispatch.clockOut(driverAuth).clockedOutAt()).isEqualTo(recordedClockOut.clockedOutAt());
        assertThatThrownBy(() -> dispatch.clockIn(initialDriver)).isInstanceOf(IllegalStateException.class);

        as("one@example.com");
        manager = SecurityContextHolder.getContext().getAuthentication();
        var preview = dispatch.preview(manager, shift.id(), 1, java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT);
        assertThat(preview.deliveredCount()).isEqualTo(2);
        assertThat(preview.orderSales()).isEqualByComparingTo("200.00");
        assertThat(preview.onlineOrderPaid()).isZero();
        assertThat(preview.onlineTips()).isZero();
        assertThat(preview.hourlyPay()).isZero();
        assertThat(preview.deliveryPay()).isEqualByComparingTo("30.00");
        assertThat(preview.expectedCashDue()).isEqualByComparingTo("170.00");
        var currentManager = manager;
        assertThatThrownBy(() -> dispatch.cashUp(currentManager, shift.id(), 1,
                java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT,
                new BigDecimal("169.00"), BigDecimal.ZERO, "", null))
                .isInstanceOf(IllegalArgumentException.class);
        var result = dispatch.cashUp(manager, shift.id(), 1,
                java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT,
                new BigDecimal("170.00"), BigDecimal.ZERO, "", "Test shift adjustment");
        assertThat(result.variance()).isZero();
        assertThat(dispatch.settlements(manager, 1)).extracting(DriverDispatchService.Settlement::shiftId)
                .contains(shift.id());
        assertThat(dispatch.settlements(manager, 1).get(0).timeAdjustmentReason()).isEqualTo("Test shift adjustment");
        assertThat(dispatch.settlements(manager, 1).get(0).approvedClockInAt()).isNotNull();
        assertThat(dispatch.settlements(manager, 1).get(0).approvedClockOutAt()).isNotNull();
        try (Connection c = ownerConnection();
             var statement = c.prepareStatement("SELECT clocked_in_at, clocked_out_at, approved_minutes "
                     + "FROM driver_shift WHERE shift_id = ?")) {
            statement.setLong(1, shift.id());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, OffsetDateTime.class)).isEqualTo(shift.clockedInAt());
                assertThat(rows.getObject(2, OffsetDateTime.class)).isEqualTo(recordedClockOut.clockedOutAt());
                assertThat(rows.getInt(3)).isZero();
            }
        }
        assertThatThrownBy(() -> dispatch.cashUp(currentManager, shift.id(), 1,
                java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT,
                new BigDecimal("170.00"), BigDecimal.ZERO, "", null))
                .isInstanceOf(IllegalStateException.class);
        dispatch.updateRates(manager, newDriver, 1, new BigDecimal("60.00"), new BigDecimal("70.00"));

        long payoutOrder;
        try (Connection c = ownerConnection()) {
            payoutOrder = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                    + "VALUES (1," + customerA + ",'Completed',now(),'delivery') RETURNING order_id");
            try (var statement = c.createStatement()) {
                statement.execute("INSERT INTO order_menu_item(order_id,menu_item_id,qty,unit_price_at_time) "
                        + "SELECT " + payoutOrder + ",burger_id,1,50 FROM burger_recipe_assignment ORDER BY burger_id LIMIT 1");
            }
        }
        as("dispatch-driver@example.com");
        driverAuth = SecurityContextHolder.getContext().getAuthentication();
        var payoutShift = dispatch.clockIn(driverAuth);
        as("one@example.com");
        manager = SecurityContextHolder.getContext().getAuthentication();
        dispatch.assign(manager, payoutOrder, newDriver, 1);
        as("dispatch-driver@example.com");
        driverAuth = SecurityContextHolder.getContext().getAuthentication();
        dispatch.pickup(driverAuth, payoutOrder);
        dispatch.delivered(driverAuth, payoutOrder);
        dispatch.clockOut(driverAuth);
        as("one@example.com");
        manager = SecurityContextHolder.getContext().getAuthentication();
        var payoutPreview = dispatch.preview(manager, payoutShift.id(), 1,
                java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT);
        assertThat(payoutPreview.orderSales()).isEqualByComparingTo("50.00");
        assertThat(payoutPreview.expectedCashDue()).isEqualByComparingTo("-20.00");
        assertThat(dispatch.cashUp(manager, payoutShift.id(), 1,
                java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT, BigDecimal.ZERO,
                new BigDecimal("21.00"), "One rand paid out above expected", null).variance())
                .isEqualByComparingTo("-1.00");
        as("dispatch-driver@example.com");
        assertThat(dispatch.driverDashboard(SecurityContextHolder.getContext().getAuthentication()).settlements())
                .hasSize(2);
        assertThat(ids("customer_order", "order_id")).containsExactly(firstOrder, secondOrder, payoutOrder);
        mvc.perform(get("/api/admin/dispatch").with(user(accounts.loadUserByUsername("dispatch-driver@example.com"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/driver").with(anonymous())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/driver").with(user(accounts.loadUserByUsername("dispatch-driver@example.com"))))
                .andExpect(status().isOk());
    }

    @Test @Order(13)
    void simultaneousClaimsAreDistinctAndRejectedAssignmentCanBeCancelled() throws Exception {
        DriverDispatchService dispatch = application.getBean(DriverDispatchService.class);
        long first;
        long second;
        long third;
        long fourth;
        long otherBranch;
        try (Connection c = ownerConnection()) {
            insert(c, "INSERT INTO customers(email,password,access_level,role) "
                    + "VALUES ('claim-one@example.com','{noop}pass',0,'USER') RETURNING id");
            insert(c, "INSERT INTO customers(email,password,access_level,role) "
                    + "VALUES ('claim-two@example.com','{noop}pass',0,'USER') RETURNING id");
            first = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                    + "VALUES (1," + customerA + ",'Pending',now()-interval '4 minutes','delivery') RETURNING order_id");
            second = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                    + "VALUES (1," + customerA + ",'Pending',now()-interval '3 minutes','delivery') RETURNING order_id");
            third = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                    + "VALUES (1," + customerA + ",'Pending',now()-interval '2 minutes','delivery') RETURNING order_id");
            fourth = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                    + "VALUES (1," + customerA + ",'Pending',now()-interval '1 minute','delivery') RETURNING order_id");
            otherBranch = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                    + "VALUES (2," + customerB + ",'Pending',now(),'delivery') RETURNING order_id");
        }
        as("one@example.com");
        var manager = SecurityContextHolder.getContext().getAuthentication();
        long firstDriverId = dispatch.promote(manager, "claim-one@example.com", 1).id();
        dispatch.promote(manager, "claim-two@example.com", 1);
        as("claim-one@example.com");
        var firstAuth = SecurityContextHolder.getContext().getAuthentication();
        pool.setMaximumPoolSize(3);
        ExecutorService signins = Executors.newFixedThreadPool(2);
        CountDownLatch loginGo = new CountDownLatch(1);
        try {
            Callable<Long> signin = () -> {
                SecurityContextHolder.getContext().setAuthentication(firstAuth);
                try { loginGo.await(5, TimeUnit.SECONDS); return dispatch.clockIn(firstAuth).id(); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var a = signins.submit(signin); var b = signins.submit(signin); loginGo.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        } finally { signins.shutdownNow(); pool.setMaximumPoolSize(1); }
        as("claim-two@example.com");
        var secondAuth = SecurityContextHolder.getContext().getAuthentication();
        dispatch.clockIn(secondAuth);
        pool.setMaximumPoolSize(3);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<Long>> tasks = List.of(firstAuth, secondAuth).stream().map(auth -> (Callable<Long>) () -> {
                SecurityContextHolder.getContext().setAuthentication(auth);
                try {
                    go.await(5, TimeUnit.SECONDS);
                    return dispatch.claimNext(auth).deliveries().stream().filter(DriverDispatchService.Delivery::selfClaimed)
                            .findFirst().orElseThrow().order().getId();
                } finally { SecurityContextHolder.clearContext(); }
            }).toList();
            var results = tasks.stream().map(workers::submit).toList();
            go.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(first, second);
        } finally { workers.shutdownNow(); pool.setMaximumPoolSize(1); }
        as("one@example.com");
        manager = SecurityContextHolder.getContext().getAuthentication();
        var finalManager = manager;
        assertThatThrownBy(() -> dispatch.assignBatch(finalManager, List.of(third, otherBranch), firstDriverId, 1))
                .isInstanceOf(RuntimeException.class);
        assertThat(dispatch.managerDashboard(manager, 1).queue()).extracting(DriverDispatchService.OrderBrief::id)
                .contains(third, fourth);
        dispatch.assignBatch(manager, List.of(fourth, third), firstDriverId, 1);
        assertThat(dispatch.managerDashboard(manager, 1).assignments()).extracting(d -> d.order().getId())
                .contains(third, fourth);
        assertThat(update("UPDATE customer_order SET status='Rejected' WHERE order_id=?", first)).isEqualTo(1);
        dispatch.cancelRejected(manager, first, 1);
        assertThat(dispatch.managerDashboard(manager, 1).assignments()).extracting(d -> d.order().getId())
                .doesNotContain(first);
        assertThatThrownBy(() -> dispatch.cancelRejected(finalManager, second, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test @Order(13)
    void claimAndClockOutCannotLeaveAnAssignmentOnAClosedShift() throws Exception {
        DriverDispatchService dispatch=application.getBean(DriverDispatchService.class);
        long raceOrder;
        try(Connection c=ownerConnection()) {
            insert(c,"INSERT INTO customers(email,password,access_level,role) VALUES ('race-driver@example.com','{noop}pass',0,'USER') RETURNING id");
            raceOrder=insert(c,"INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) VALUES (1,"
                    +customerA+",'Pending','2000-01-01','delivery') RETURNING order_id");
        }
        as("one@example.com");var manager=SecurityContextHolder.getContext().getAuthentication();
        dispatch.promote(manager,"race-driver@example.com",1);
        as("race-driver@example.com");var auth=SecurityContextHolder.getContext().getAuthentication();
        dispatch.clockIn(auth);
        pool.setMaximumPoolSize(3);ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch go=new CountDownLatch(1);
        try {
            List<Callable<String>> tasks=List.of(() -> {
                SecurityContextHolder.getContext().setAuthentication(auth);
                try { go.await(5,TimeUnit.SECONDS);dispatch.claimNext(auth);return "claimed"; }
                catch(IllegalStateException e) { return "claim blocked"; }
                finally { SecurityContextHolder.clearContext(); }
            },() -> {
                SecurityContextHolder.getContext().setAuthentication(auth);
                try { go.await(5,TimeUnit.SECONDS);dispatch.clockOut(auth);return "clocked out"; }
                catch(IllegalStateException e) { return "clock-out blocked"; }
                finally { SecurityContextHolder.clearContext(); }
            });
            var results=tasks.stream().map(workers::submit).toList();go.countDown();
            List<String> outcome=List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));
            assertThat(outcome.equals(List.of("claimed","clock-out blocked"))
                    || outcome.equals(List.of("claim blocked","clocked out"))).isTrue();
        } finally { workers.shutdownNow();pool.setMaximumPoolSize(1); }
        as("race-driver@example.com");var result=dispatch.driverDashboard(auth);
        assertThat(result.deliveries().stream().anyMatch(d -> "ASSIGNED".equals(d.state())))
                .isEqualTo("ACTIVE".equals(result.shift().state()));
        as("one@example.com");assertThat(update("UPDATE customer_order SET status='Rejected' WHERE order_id=?",raceOrder)).isEqualTo(1);
        if(!result.deliveries().isEmpty()) dispatch.cancelRejected(manager,raceOrder,1);
    }

    @Test @Order(14)
    void driverCanConfirmCollectionBeforeKitchenCompletionWithExistingGuards() throws Exception {
        DriverDispatchService dispatch = application.getBean(DriverDispatchService.class);
        List<Long> orders = new ArrayList<>();
        try (Connection c = ownerConnection()) {
            for (String email : List.of("pickup-driver@example.com", "pickup-other@example.com")) {
                insert(c, "INSERT INTO customers(email,password,access_level,role) VALUES ('"
                        + email + "','{noop}pass',0,'USER') RETURNING id");
            }
            for (String status : List.of("Pending", "Preparing", "Completed", "Pending", "Pending")) {
                orders.add(insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                        + "VALUES (1," + customerA + ",'" + status + "',now(),'delivery') RETURNING order_id"));
            }
        }
        as("one@example.com");
        var manager = SecurityContextHolder.getContext().getAuthentication();
        long driverId = dispatch.promote(manager, "pickup-driver@example.com", 1).id();
        dispatch.promote(manager, "pickup-other@example.com", 1);
        as("pickup-driver@example.com");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        long shiftId = dispatch.clockIn(auth).id();
        as("pickup-other@example.com");
        var other = SecurityContextHolder.getContext().getAuthentication();
        dispatch.clockIn(other);
        as("one@example.com");
        dispatch.assignBatch(manager, orders, driverId, 1);
        update("UPDATE customer_order SET status='Rejected' WHERE order_id=?", orders.get(3));
        update("UPDATE customer_order SET status='Rejected' WHERE order_id=?", orders.get(4));
        dispatch.cancelRejected(manager, orders.get(4), 1);

        as("pickup-other@example.com");
        assertThatThrownBy(() -> dispatch.pickup(other, orders.get(0))).isInstanceOf(RuntimeException.class);
        as("pickup-driver@example.com");
        assertThatThrownBy(() -> dispatch.pickup(auth, orderB)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> dispatch.pickup(auth, orders.get(3))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> dispatch.pickup(auth, orders.get(4))).isInstanceOf(RuntimeException.class);
        invalidDeliveryTransition(() -> transaction.<Integer>execute(s -> jdbc.update("""
                UPDATE delivery_assignment SET state='PICKED_UP', picked_up_at=now()
                WHERE order_id=?
                """, orders.get(3))));
        invalidDeliveryTransition(() -> transaction.<Integer>execute(s -> jdbc.update("""
                UPDATE delivery_assignment SET state='PICKED_UP', picked_up_at='2000-01-01'
                WHERE order_id=?
                """, orders.get(0))));
        for (int n = 0; n < 3; n++) {
            long orderId = orders.get(n);
            var result = dispatch.pickup(auth, orderId);
            var delivery = result.deliveries().stream().filter(d -> d.order().getId() == orderId).findFirst().orElseThrow();
            assertThat(delivery.state()).isEqualTo("PICKED_UP");
            assertThat(delivery.pickedUpAt()).isNotNull();
            assertThat(delivery.order().getStatus()).isEqualTo(List.of("Pending", "Preparing", "Completed").get(n));
            assertThatThrownBy(() -> dispatch.pickup(auth, orderId)).isInstanceOf(RuntimeException.class);
            var unchanged = dispatch.driverDashboard(auth).deliveries().stream()
                    .filter(d -> d.order().getId() == orderId).findFirst().orElseThrow();
            assertThat(unchanged.pickedUpAt()).isEqualTo(delivery.pickedUpAt());
            dispatch.delivered(auth, orderId);
        }
        assertThat(transaction.<Integer>execute(s -> jdbc.queryForObject(
                "SELECT count(*) FROM driver_dispatch_event WHERE shift_id=? AND event_type='PICKED_UP'",
                Integer.class, shiftId))).isEqualTo(3);
        as("one@example.com");
        dispatch.cancelRejected(manager, orders.get(3), 1);
        as("pickup-driver@example.com");
        dispatch.clockOut(auth);
        assertThatThrownBy(() -> dispatch.pickup(auth, orders.get(0))).isInstanceOf(IllegalStateException.class);
    }

    @Test @Order(14)
    void savingRatesRepairsUncashedShiftsAndPreservesSettledPayroll() throws Exception {
        DriverDispatchService dispatch = application.getBean(DriverDispatchService.class);
        List<Long> deliveryIds = new ArrayList<>();
        try (Connection c = ownerConnection()) {
            insert(c, "INSERT INTO customers(email,password,access_level,role) "
                    + "VALUES ('rates-driver@example.com','{noop}pass',0,'USER') RETURNING id");
            for (int price : List.of(400, 300, 300, 100)) {
                long id = insert(c, "INSERT INTO customer_order(branch_id,customer_id,status,created_at,order_type) "
                        + "VALUES (1," + customerA + ",'Completed',now(),'delivery') RETURNING order_id");
                deliveryIds.add(id);
                try (var statement = c.createStatement()) {
                    statement.execute("INSERT INTO order_menu_item(order_id,menu_item_id,qty,unit_price_at_time) "
                            + "SELECT " + id + ",burger_id,1," + price
                            + " FROM burger_recipe_assignment ORDER BY burger_id LIMIT 1");
                }
            }
        }
        as("one@example.com");
        var manager = SecurityContextHolder.getContext().getAuthentication();
        long driverId = dispatch.promote(manager, "rates-driver@example.com", 1).id();
        as("rates-driver@example.com");
        var driverAuth = SecurityContextHolder.getContext().getAuthentication();
        var shift = dispatch.clockIn(driverAuth);
        assertThat(shift.hourlyRate()).isZero();
        as("one@example.com");
        dispatch.assignBatch(manager, deliveryIds.subList(0, 3), driverId, 1);
        as("rates-driver@example.com");
        for (long id : deliveryIds.subList(0, 3)) {
            dispatch.pickup(driverAuth, id);
            dispatch.delivered(driverAuth, id);
        }
        var recordedOut = dispatch.clockOut(driverAuth).clockedOutAt();
        as("one@example.com");
        // Reproduce a pre-fix profile save that left the shift's rate snapshot at zero.
        update("UPDATE driver_profile SET hourly_rate=15, per_delivery_rate=25 WHERE driver_id=?", driverId);
        assertThat(dispatch.preview(manager, shift.id(), 1, java.time.LocalTime.MIDNIGHT,
                java.time.LocalTime.MIDNIGHT).deliveryPay()).isZero();
        as("rates-driver@example.com");
        denied(() -> update("UPDATE driver_shift SET hourly_rate=15, per_delivery_rate=25 WHERE shift_id=?", shift.id()));
        assertThatThrownBy(() -> dispatch.updateRates(driverAuth, driverId, 1,
                BigDecimal.TEN, BigDecimal.TEN)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        as("two@example.com");
        var otherManager = SecurityContextHolder.getContext().getAuthentication();
        assertThatThrownBy(() -> dispatch.updateRates(otherManager, driverId, 1,
                BigDecimal.TEN, BigDecimal.TEN)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(update("UPDATE driver_shift SET hourly_rate=15 WHERE shift_id=?", shift.id())).isZero();
        as("one@example.com");
        denied(() -> update("UPDATE driver_shift SET hourly_rate=99 WHERE shift_id=?", shift.id()));
        dispatch.updateRates(manager, driverId, 1, new BigDecimal("15.00"), new BigDecimal("25.00"));
        var preview = dispatch.preview(manager, shift.id(), 1, java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT);
        assertThat(preview.orderSales()).isEqualByComparingTo("1000.00");
        assertThat(preview.deliveredCount()).isEqualTo(3);
        assertThat(preview.hourlyRate()).isEqualByComparingTo("15.00");
        assertThat(preview.perDeliveryRate()).isEqualByComparingTo("25.00");
        assertThat(preview.deliveryPay()).isEqualByComparingTo("75.00");
        assertThat(preview.expectedCashDue()).isEqualByComparingTo("925.00");
        var audit = transaction.execute(s -> jdbc.queryForMap("""
                SELECT actor_id, actor_username, details->>'oldHourlyRate' AS old_hourly,
                    details->>'newHourlyRate' AS new_hourly, details->>'oldPerDeliveryRate' AS old_delivery,
                    details->>'newPerDeliveryRate' AS new_delivery, occurred_at
                FROM driver_dispatch_event WHERE shift_id=? AND event_type='SHIFT_RATES_CHANGED'
                """, shift.id()));
        assertThat(audit.get("actor_id")).isEqualTo(branchOne);
        assertThat(audit.get("actor_username")).isEqualTo("one@example.com");
        assertThat(new BigDecimal((String) audit.get("old_hourly"))).isZero();
        assertThat(new BigDecimal((String) audit.get("old_delivery"))).isZero();
        assertThat(new BigDecimal((String) audit.get("new_hourly"))).isEqualByComparingTo("15");
        assertThat(new BigDecimal((String) audit.get("new_delivery"))).isEqualByComparingTo("25");
        assertThat(audit.get("occurred_at")).isNotNull();
        dispatch.updateRates(manager, driverId, 1, new BigDecimal("15.00"), new BigDecimal("25.00"));
        assertThat(transaction.<Integer>execute(s -> jdbc.queryForObject("""
                SELECT count(*) FROM driver_dispatch_event WHERE shift_id=? AND event_type='SHIFT_RATES_CHANGED'
                """, Integer.class, shift.id()))).isEqualTo(1);
        dispatch.cashUp(manager, shift.id(), 1, java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT,
                new BigDecimal("925.00"), BigDecimal.ZERO, "", null);
        dispatch.updateRates(manager, driverId, 1, new BigDecimal("20.00"), new BigDecimal("30.00"));
        var settlement = dispatch.settlements(manager, 1).stream().filter(s -> s.shiftId() == shift.id()).findFirst().orElseThrow();
        assertThat(settlement.deliveryPay()).isEqualByComparingTo("75.00");
        assertThat(settlement.expectedCashDue()).isEqualByComparingTo("925.00");
        denied(() -> update("UPDATE driver_shift SET hourly_rate=20, per_delivery_rate=30 WHERE shift_id=?", shift.id()));
        assertThatThrownBy(() -> dispatch.preview(manager, shift.id(), 1, null, null)).isInstanceOf(IllegalStateException.class);
        try (Connection c = ownerConnection(); var statement = c.prepareStatement(
                "SELECT hourly_rate,per_delivery_rate,clocked_in_at,clocked_out_at FROM driver_shift WHERE shift_id=?")) {
            statement.setLong(1, shift.id());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("15");
                assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("25");
                assertThat(rows.getObject(3, OffsetDateTime.class)).isEqualTo(shift.clockedInAt());
                assertThat(rows.getObject(4, OffsetDateTime.class)).isEqualTo(recordedOut);
            }
        }
        as("rates-driver@example.com");
        var nextShift = dispatch.clockIn(driverAuth);
        assertThat(nextShift.hourlyRate()).isEqualByComparingTo("20");
        assertThat(nextShift.perDeliveryRate()).isEqualByComparingTo("30");
        as("one@example.com");
        dispatch.updateRates(manager, driverId, 1, new BigDecimal("30.00"), new BigDecimal("50.00"));
        dispatch.assign(manager, deliveryIds.get(3), driverId, 1);
        as("rates-driver@example.com");
        var active = dispatch.driverDashboard(driverAuth).shift();
        assertThat(active.hourlyRate()).isEqualByComparingTo("30");
        assertThat(active.perDeliveryRate()).isEqualByComparingTo("50");
        dispatch.pickup(driverAuth, deliveryIds.get(3));
        dispatch.delivered(driverAuth, deliveryIds.get(3));
        dispatch.clockOut(driverAuth);

        pool.setMaximumPoolSize(3);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> save = workers.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(manager);
                try {
                    go.await(5, TimeUnit.SECONDS);
                    return dispatch.updateRates(manager, driverId, 1, new BigDecimal("40.00"), new BigDecimal("60.00"));
                } finally { SecurityContextHolder.clearContext(); }
            });
            var settle = workers.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(manager);
                try {
                    go.await(5, TimeUnit.SECONDS);
                    return dispatch.cashUp(manager, nextShift.id(), 1, java.time.LocalTime.MIDNIGHT,
                            java.time.LocalTime.MIDNIGHT, new BigDecimal("50.00"), BigDecimal.ZERO,
                            "Concurrent rate test", null);
                } finally { SecurityContextHolder.clearContext(); }
            });
            go.countDown();
            save.get(10, TimeUnit.SECONDS);
            var settled = settle.get(10, TimeUnit.SECONDS).preview();
            assertThat(settled.hourlyRate().intValue()).isIn(30, 40);
            assertThat(settled.perDeliveryRate().subtract(settled.hourlyRate())).isEqualByComparingTo("20");
            assertThat(settled.deliveryPay()).isEqualByComparingTo(settled.perDeliveryRate());
            assertThat(settled.expectedCashDue()).isEqualByComparingTo(
                    new BigDecimal("100.00").subtract(settled.deliveryPay()));
        } finally { workers.shutdownNow(); pool.setMaximumPoolSize(1); }
    }

    @Test @Order(14)
    void unsafeOwnerLoginCannotStartAsRuntime() {
        SpringApplication app = new SpringApplication(OnlinePosSystemApplication.class);
        String[] arguments = Arrays.stream(runtimeArguments())
                .map(value -> value.equals("--spring.datasource.username=" + runtime)
                        ? "--spring.datasource.username=" + owner : value)
                .toArray(String[]::new);
        assertThatThrownBy(() -> {
            try (var ignored = app.run(arguments)) { /* startup must fail */ }
        }).hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Runtime database role has unsafe privileges");
    }

    @Test @Order(15)
    void existingTimestampSchemaCanUpgradeAndValidate() throws Exception {
        application.close();
        application = null;
        try (Connection c = ownerConnection(); var statement = c.createStatement()) {
            statement.execute("ALTER TABLE customer_order ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'Africa/Johannesburg'");
        }
        migrate();
        application = new SpringApplication(OnlinePosSystemApplication.class).run(runtimeArguments());
        try (Connection c = application.getBean(HikariDataSource.class).getConnection()) {
            RlsRuntimeVerifier.verify(c);
        }
    }

    private List<Long> ids(String table, String column) {
        return transaction.execute(s -> jdbc.queryForList("SELECT " + column + " FROM " + table + " ORDER BY " + column, Long.class));
    }

    private int update(String sql, Object... args) {
        Integer updated = transaction.<Integer>execute(s -> jdbc.update(sql, args));
        return updated == null ? 0 : updated;
    }

    private void as(String username) {
        SecurityContextHolder.clearContext();
        var principal = accounts.loadUserByUsername(username);
        assertThat(principal).isInstanceOf(AccountPrincipal.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private void invalidDeliveryTransition(Runnable action) {
        assertThatThrownBy(action::run).rootCause().isInstanceOf(SQLException.class)
                .satisfies(cause -> assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514"));
    }

    private void denied(Runnable action) {
        assertThatThrownBy(action::run).satisfies(failure -> {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo("42501");
        });
    }

    private Connection adminConnection() throws SQLException { return DriverManager.getConnection(adminUrl, adminUser, adminPassword); }
    private Connection ownerConnection() throws SQLException { return DriverManager.getConnection(jdbcUrl, owner, password); }

    private String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("RLS integration tests require " + key + "; they must not be skipped");
        return value;
    }

    @AfterAll
    void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        if (application != null) application.close();
        if (adminUrl == null || adminUser == null) return;
        try (Connection c = adminConnection(); var s = c.createStatement()) {
            if (databaseCreated) s.execute("DROP DATABASE " + database + " WITH (FORCE)");
            if (rolesCreated) { s.execute("DROP ROLE " + runtime); s.execute("DROP ROLE " + owner); }
        }
    }

    private static class RlsContextInitializerForTest extends RlsContextInitializer {
        private RlsContextInitializerForTest() { super(TEST_CONTEXT_SECRET); }

        @Override public void initialize(Connection connection) throws SQLException {
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.role','SUPER_ADMIN',true)");
            }
            throw new SQLException("Test initialization failure", "42501");
        }
    }
    @Test @Order(14)
    void businessOwnerCreatesThirdStoreWithoutChangingOrdersOrGrantingManagersAccess() throws Exception {
        var ownerAccount=accounts.loadUserByUsername("super@example.com");
        var managerAccount=accounts.loadUserByUsername("one@example.com");
        mvc.perform(get("/api/business-management/stores").with(user(managerAccount))).andExpect(status().isForbidden());
        UUID business;
        long orders;
        try(var connection=ownerConnection();var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO public.business_owner(account_id) VALUES("+namedSuperAdmin+")");
            try(var rows=statement.executeQuery("SELECT business_id FROM public.business")){rows.next();business=rows.getObject(1,UUID.class);}
            try(var rows=statement.executeQuery("SELECT count(*) FROM public.customer_order")){rows.next();orders=rows.getLong(1);}
        }
        UUID command=UUID.randomUUID();
        String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("commandId",command,"businessId",business,
            "values",Map.of("name","Independent third store","code","THIRD","active",true)));
        var response=mvc.perform(post("/api/business-management/stores").with(user(ownerAccount)).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn();
        var created=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString());
        assertThat(created.path("branch_id").asInt()).isGreaterThan(2);
        mvc.perform(post("/api/business-management/stores").with(user(ownerAccount)).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.store_uuid").value(created.path("store_uuid").asText()));
        mvc.perform(post("/api/business-management/stores").with(user(ownerAccount)).with(csrf()).contentType("application/json")
            .content(body.replace(business.toString(),UUID.randomUUID().toString()))).andExpect(status().isForbidden());
        try(var connection=ownerConnection();var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("SELECT count(*) FROM public.customer_order")){rows.next();assertThat(rows.getLong(1)).isEqualTo(orders);}
            try(var rows=statement.executeQuery("SELECT count(*) FROM public.business_management_audit WHERE command_id='"+command+"'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
            statement.executeUpdate("UPDATE public.business_owner SET enabled=false WHERE account_id="+namedSuperAdmin);
        }
        mvc.perform(get("/api/business-management/stores").with(user(ownerAccount))).andExpect(status().isForbidden());
    }

}
