package com.agentic.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Committed data must survive a hard crash of the process (ADR-0010). Found by a live demo: with
 * H2's default write delay, {@code kill -9} right after a run paused for approval lost the whole
 * run, including committed audit events.
 *
 * <p>This test uses the database options <b>exactly as configured</b> in application.yml, commits
 * rows from a child JVM, kills it with SIGKILL (no shutdown hooks, no flush), then checks every
 * committed row is there. Changing the URL in a way that breaks durability fails the build.
 */
class DurabilityTest {

    private static final int ROWS = 50;

    /** Child process: commit rows one by one, announce it, then wait to be killed. */
    public static void main(String[] args) throws Exception {
        Connection c = DriverManager.getConnection(args[0], "sa", "");
        c.setAutoCommit(true);
        c.createStatement().execute("CREATE TABLE IF NOT EXISTS audit (i INT)");
        for (int i = 0; i < ROWS; i++) {
            c.createStatement().execute("INSERT INTO audit VALUES (" + i + ")");
        }
        System.out.println("COMMITTED");
        System.out.flush();
        Thread.sleep(60_000);
    }

    @Test
    void committedRowsSurviveSigkillWithTheConfiguredDatabaseOptions(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("db") + configuredH2Options();

        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), DurabilityTest.class.getName(), url)
                .redirectErrorStream(true).start();
        try (var out = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            String line;
            while ((line = out.readLine()) != null && !line.equals("COMMITTED")) {
                // skip child log output
            }
            assertThat(line).as("child committed its rows").isEqualTo("COMMITTED");
        }
        child.destroyForcibly(); // SIGKILL on Unix: no shutdown hooks, no orderly close
        assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             ResultSet r = c.createStatement().executeQuery("SELECT COUNT(*) FROM audit")) {
            r.next();
            assertThat(r.getInt(1)).as("every committed row survives a hard kill").isEqualTo(ROWS);
        }
    }

    @Test
    void configuredUrlDisablesTheWriteDelay() {
        assertThat(configuredH2Options()).contains("WRITE_DELAY=0");
    }

    /** The ";OPTION=…" suffix of the default datasource URL in application.yml. */
    private static String configuredH2Options() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        String raw = props.getProperty("spring.datasource.url");            // ${DB_URL:jdbc:h2:file:...;OPTS}
        String fallback = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        return fallback.contains(";") ? fallback.substring(fallback.indexOf(';')) : "";
    }
}
