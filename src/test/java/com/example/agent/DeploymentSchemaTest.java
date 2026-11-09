package com.example.agent;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class DeploymentSchemaTest {
    String normalize(String sql) {return sql.replaceAll("--[^\\n]*","").replaceAll("\\s+"," ").strip();}
    @Test void productionUsesMysqlCompatibleSchemaAndMatchesApplicationStructure() throws Exception {
        String compose=Files.readString(Path.of("deploy/docker-compose.prod.yml"));
        String local=Files.readString(Path.of("deploy/local/01-schema.sql"));
        String application=Files.readString(Path.of("src/main/resources/schema.sql"));
        assertThat(compose).contains("./local/01-schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro")
                .doesNotContain("../src/main/resources/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql");
        assertThat(local).doesNotContain("CREATE INDEX IF NOT EXISTS");
        assertThat(normalize(local)).isEqualTo(normalize(application.replace("CREATE INDEX IF NOT EXISTS","CREATE INDEX")));
        // This validates the complete initialization structure; live MySQL remains a separate check.
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:deployment_schema;MODE=MySQL","sa","");var statement=connection.createStatement()) {
            for(String sql:local.replaceAll("--[^\\n]*","").split(";")) if(!sql.isBlank()) statement.execute(sql);
            assertThat(statement.executeQuery("SELECT COUNT(*) FROM document_vector_cleanup").next()).isTrue();
        }
    }
}
