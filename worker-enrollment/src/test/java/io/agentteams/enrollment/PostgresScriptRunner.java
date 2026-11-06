package io.agentteams.enrollment;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

final class PostgresScriptRunner {
    private PostgresScriptRunner() {}

    static void run(JdbcTemplate jdbc, String classpathResource) {
        try {
            String script = new String(
                    new ClassPathResource(classpathResource).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder statement = new StringBuilder();
            boolean inDollarQuote = false;
            boolean inLineComment = false;
            for (int i = 0; i < script.length(); i++) {
                if (inLineComment) {
                    if (script.charAt(i) == '\n') {
                        inLineComment = false;
                        statement.append('\n');
                    }
                } else if (!inDollarQuote && script.startsWith("--", i)) {
                    inLineComment = true;
                    i++;
                } else if (script.startsWith("$$", i)) {
                    inDollarQuote = !inDollarQuote;
                    statement.append("$$");
                    i++;
                } else if (script.charAt(i) == ';' && !inDollarQuote) {
                    execute(jdbc, statement);
                    statement.setLength(0);
                } else {
                    statement.append(script.charAt(i));
                }
            }
            execute(jdbc, statement);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Could not read PostgreSQL script " + classpathResource, error);
        }
    }

    private static void execute(JdbcTemplate jdbc, StringBuilder statement) {
        String sql = statement.toString().trim();
        if (!sql.isEmpty()) {
            jdbc.execute(sql);
        }
    }
}
