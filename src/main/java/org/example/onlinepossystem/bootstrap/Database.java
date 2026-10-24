package org.example.onlinepossystem.bootstrap;

import org.example.onlinepossystem.catalog.api.CatalogSeeder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
@org.springframework.context.annotation.Profile("postgres-test")
public class Database implements CommandLineRunner {

    private final DataSource dataSource;
    private final CatalogSeeder catalogSeeder;

    public Database(DataSource dataSource,
                    CatalogSeeder catalogSeeder) {
        this.dataSource = dataSource;
        this.catalogSeeder = catalogSeeder;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Testing database connection...");

        try (Connection conn = dataSource.getConnection()) {
            if (conn != null && !conn.isClosed()) {
                System.out.println("✅ Connected to database successfully!");

                catalogSeeder.seedMenuData();
            } else {
                System.out.println("❌ Failed to connect to database");
            }
        } catch (SQLException e) {
            System.err.println("❌ Connection failed: " + e.getMessage());
        }
    }
}
