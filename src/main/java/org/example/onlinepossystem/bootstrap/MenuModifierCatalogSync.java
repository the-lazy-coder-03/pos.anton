package org.example.onlinepossystem.bootstrap;

import org.example.onlinepossystem.catalog.api.CatalogMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("postgres-test")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MenuModifierCatalogSync implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(MenuModifierCatalogSync.class);

    private final CatalogMaintenance catalogMaintenance;

    public MenuModifierCatalogSync(CatalogMaintenance catalogMaintenance) {
        this.catalogMaintenance = catalogMaintenance;
    }

    @Override
    public void run(String... args) {
        try {
            catalogMaintenance.syncMenuModifierCatalog();
        } catch (DataAccessException ex) {
            logger.warn("Could not sync chip modifier catalog rows. SQL files/migration.sql may not have run yet.", ex);
        }
    }
}
