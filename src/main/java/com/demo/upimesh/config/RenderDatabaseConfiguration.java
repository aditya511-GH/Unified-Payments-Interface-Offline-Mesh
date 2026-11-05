package com.demo.upimesh.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Adapts Render's postgres:// DATABASE_URL to the JDBC URL Spring expects. */
@Configuration
public class RenderDatabaseConfiguration {
    @Bean
    public DataSource dataSource(DataSourceProperties properties, Environment environment) {
        String databaseUrl = environment.getProperty("DATABASE_URL", "").trim();
        if (databaseUrl.isEmpty()) return properties.initializeDataSourceBuilder().build();

        try {
            URI uri = URI.create(databaseUrl);
            String scheme = uri.getScheme();
            if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
                throw new IllegalArgumentException("DATABASE_URL must use postgres:// or postgresql://");
            }
            String[] credentials = uri.getRawUserInfo() == null ? new String[0] : uri.getRawUserInfo().split(":", 2);
            String username = credentials.length > 0 ? decode(credentials[0]) : null;
            String password = credentials.length > 1 ? decode(credentials[1]) : null;
            String database = uri.getRawPath() == null ? "" : uri.getRawPath().replaceFirst("^/", "");
            if (uri.getHost() == null || database.isBlank() || username == null || password == null) {
                throw new IllegalArgumentException("DATABASE_URL is missing host, database, or credentials");
            }
            String query = uri.getRawQuery();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + "/" + database + (query == null || query.isBlank() ? "" : "?" + query);
            return DataSourceBuilder.create()
                    .driverClassName("org.postgresql.Driver")
                    .url(jdbcUrl)
                    .username(username)
                    .password(password)
                    .build();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not configure the Render Postgres connection from DATABASE_URL", ex);
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
