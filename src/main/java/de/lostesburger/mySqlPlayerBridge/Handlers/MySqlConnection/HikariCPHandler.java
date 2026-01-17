package de.lostesburger.mySqlPlayerBridge.Handlers.MySqlConnection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;

public class HikariCPHandler {
    private HikariDataSource dataSource;
    private final boolean enabled;

    public HikariCPHandler(String host, int port, String database, String username, String password,
            FileConfiguration config) {
        this.enabled = config.getBoolean("hikaricp.enabled", true);

        if (enabled) {
            try {
                HikariConfig hikariConfig = new HikariConfig();

                hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
                hikariConfig.setUsername(username);
                hikariConfig.setPassword(password);
                hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");

                hikariConfig.setMaximumPoolSize(config.getInt("hikaricp.maximum-pool-size", 10));
                hikariConfig.setMinimumIdle(config.getInt("hikaricp.minimum-idle", 5));
                hikariConfig.setConnectionTimeout(config.getLong("hikaricp.connection-timeout", 30000));
                hikariConfig.setMaxLifetime(config.getLong("hikaricp.max-lifetime", 1800000));
                hikariConfig.setIdleTimeout(config.getLong("hikaricp.idle-timeout", 600000));
                hikariConfig.setConnectionTestQuery(config.getString("hikaricp.connection-test-query", "SELECT 1"));

                hikariConfig.setPoolName("MySqlPlayerBridge-HikariCP");

                hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
                hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
                hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
                hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
                hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
                hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
                hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
                hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
                hikariConfig.addDataSourceProperty("maintainTimeStats", "false");

                hikariConfig.addDataSourceProperty("characterEncoding", "utf8");
                hikariConfig.addDataSourceProperty("useUnicode", "true");

                this.dataSource = new HikariDataSource(hikariConfig);

                Main.getInstance().getLogger().info("HikariCP connection pool initialized successfully!");
                Main.getInstance().getLogger().info("Pool Size: " + hikariConfig.getMaximumPoolSize() +
                        " | Minimum Idle: " + hikariConfig.getMinimumIdle());
            } catch (Exception e) {
                Main.getInstance().getLogger().severe("Failed to initialize HikariCP: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("HikariCP initialization failed", e);
            }
        } else {
            Main.getInstance().getLogger().warning("HikariCP is disabled in config. Using default connection method.");
        }
    }

    public Connection getConnection() throws SQLException {
        if (!enabled || dataSource == null) {
            throw new SQLException("HikariCP is not enabled or initialized");
        }
        return dataSource.getConnection();
    }

    public boolean isEnabled() {
        return enabled && dataSource != null && !dataSource.isClosed();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            Main.getInstance().getLogger().info("HikariCP connection pool closed.");
        }
    }

    public String getPoolStats() {
        if (dataSource == null) {
            return "HikariCP is not initialized";
        }
        return String.format("Active: %d | Idle: %d | Total: %d | Threads Waiting: %d",
                dataSource.getHikariPoolMXBean().getActiveConnections(),
                dataSource.getHikariPoolMXBean().getIdleConnections(),
                dataSource.getHikariPoolMXBean().getTotalConnections(),
                dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
    }
}
