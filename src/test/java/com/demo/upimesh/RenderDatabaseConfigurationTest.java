package com.demo.upimesh;

import com.demo.upimesh.config.RenderDatabaseConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderDatabaseConfigurationTest {
    @Test
    void convertsRenderPostgresUrlToJdbcWithoutExposingCredentialsInTheUrl() {
        DataSourceProperties defaults = new DataSourceProperties();
        MockEnvironment environment = new MockEnvironment().withProperty(
                "DATABASE_URL", "postgres://mesh-user:p%40ss%3Aword@db.internal:5432/meshpay?sslmode=require");

        HikariDataSource source = (HikariDataSource) new RenderDatabaseConfiguration()
                .dataSource(defaults, environment);
        try {
            assertEquals("jdbc:postgresql://db.internal:5432/meshpay?sslmode=require", source.getJdbcUrl());
            assertEquals("mesh-user", source.getUsername());
            assertEquals("p@ss:word", source.getPassword());
        } finally {
            source.close();
        }
    }
}
