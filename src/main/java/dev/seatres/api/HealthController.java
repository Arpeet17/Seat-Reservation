package dev.seatres.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness: the JVM is up and serving HTTP. Never touches the database, so a DB outage does not
 * cause the orchestrator to restart-loop healthy processes.
 *
 * Readiness: PostgreSQL is reachable and answers a query. Uses its own one-connection pool, kept
 * warm, separate from the application pool:
 * - borrowing from the main pool would make readiness hang behind a load burst and report a false
 *   outage;
 * - opening a fresh connection per probe (the first version) costs a full SCRAM handshake, which
 *   on a 0.1-CPU instance under load exceeded the timeout and briefly took the instance out of the
 *   load balancer during a live burst test.
 * If the database goes away, the warm connection breaks, a reconnect fails within 3s, and readiness
 * reports DOWN.
 */
@RestController
public class HealthController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HikariDataSource probePool;

    public HealthController(DataSourceProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("readiness-probe");
        cfg.setJdbcUrl(props.determineUrl());
        cfg.setUsername(props.determineUsername());
        cfg.setPassword(props.determinePassword());
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(3000);
        cfg.setValidationTimeout(2000);
        cfg.setInitializationFailTimeout(-1);   // never block startup on the probe pool
        cfg.addDataSourceProperty("connectTimeout", "3");
        cfg.addDataSourceProperty("socketTimeout", "5");
        this.probePool = new HikariDataSource(cfg);
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try (Connection c = probePool.getConnection();
             Statement s = c.createStatement()) {
            s.setQueryTimeout(3);
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

    @Override
    public void destroy() {
        probePool.close();
    }
}
