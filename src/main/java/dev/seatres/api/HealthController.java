package dev.seatres.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness: the JVM is up and serving HTTP. Never touches the database, so a DB outage does not
 * cause the orchestrator to restart-loop healthy processes.
 *
 * Readiness: PostgreSQL is reachable and answers a query. Deliberately uses its own short-lived,
 * unpooled connection with a 2s timeout: if it borrowed from the Hikari pool, a load burst that
 * saturates the pool would make readiness hang for the pool's connection-timeout and then report a
 * false outage.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSourceProperties dataSourceProperties;

    public HealthController(DataSourceProperties dataSourceProperties) {
        this.dataSourceProperties = dataSourceProperties;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        Properties props = new Properties();
        props.setProperty("user", dataSourceProperties.determineUsername());
        props.setProperty("password", dataSourceProperties.determinePassword());
        props.setProperty("connectTimeout", "2");
        props.setProperty("socketTimeout", "2");
        props.setProperty("loginTimeout", "2");
        try (Connection c = DriverManager.getConnection(dataSourceProperties.determineUrl(), props);
             Statement s = c.createStatement()) {
            s.setQueryTimeout(2);
            try (ResultSet rs = s.executeQuery("SELECT 1")) {
                if (rs.next() && rs.getInt(1) == 1) {
                    return ResponseEntity.ok(Map.of("status", "UP", "database", "UP"));
                }
            }
        } catch (Exception e) {
            log.warn("readiness check failed: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "DOWN", "database", "DOWN"));
    }
}
