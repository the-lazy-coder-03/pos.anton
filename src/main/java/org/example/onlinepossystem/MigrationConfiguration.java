package org.example.onlinepossystem;

import org.example.onlinepossystem.bootstrap.MigrationSqlRunner;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** A non-web context with no controllers, authentication services, or runtime repository pool. */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
@EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@EntityScan("org.example.onlinepossystem")
@Import(MigrationSqlRunner.class)
public class MigrationConfiguration {
}
