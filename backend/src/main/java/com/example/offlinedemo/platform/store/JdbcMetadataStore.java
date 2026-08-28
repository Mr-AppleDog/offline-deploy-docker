package com.example.offlinedemo.platform.store;

import com.example.offlinedemo.platform.domain.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 真实 MySQL 后端：每个聚合（项目/配置/制品/构建）一行，body 保存完整 JSON。
 * 表结构由 {@link #ensureSchema()} 幂等创建，无 Flyway/Liquibase 依赖。
 */
public class JdbcMetadataStore implements MetadataStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMetadataStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS kunlun_record (
                    kind   VARCHAR(32)  NOT NULL,
                    id     VARCHAR(64)  NOT NULL,
                    name   VARCHAR(255),
                    body   MEDIUMTEXT   NOT NULL,
                    PRIMARY KEY (kind, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    public Models.PlatformState load() throws Exception {
        Models.PlatformState state = new Models.PlatformState();
        List<Row> rows = jdbc.query("SELECT kind, id, body FROM kunlun_record",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)));
        for (Row row : rows) {
            switch (row.kind()) {
                case "project" -> state.projects.put(row.id(), objectMapper.readValue(row.body(), Models.Project.class));
                case "profile" -> state.profiles.put(row.id(), objectMapper.readValue(row.body(), Models.DeploymentProfile.class));
                case "artifact" -> state.artifacts.put(row.id(), objectMapper.readValue(row.body(), Models.Artifact.class));
                case "sql-script" -> state.sqlScripts.put(row.id(), objectMapper.readValue(row.body(), Models.SqlScript.class));
                case "build" -> state.builds.put(row.id(), objectMapper.readValue(row.body(), Models.BuildTask.class));
                case "image-export" -> state.imageExportTasks.put(row.id(), objectMapper.readValue(row.body(), Models.ImageExportTask.class));
                default -> { /* 忽略未知类型 */ }
            }
        }
        return state;
    }

    @Override
    public void save(Models.PlatformState state) throws Exception {
        write("project", state.projects);
        write("profile", state.profiles);
        write("artifact", state.artifacts);
        write("sql-script", state.sqlScripts);
        write("build", state.builds);
        write("image-export", state.imageExportTasks);
    }

    private void write(String kind, Map<String, ?> map) throws Exception {
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String body = objectMapper.writeValueAsString(entry.getValue());
            jdbc.update("""
                    INSERT INTO kunlun_record (kind, id, name, body) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), body = VALUES(body)
                    """, kind, entry.getKey(), nameOf(entry.getValue()), body);
        }
        if (map.isEmpty()) {
            jdbc.update("DELETE FROM kunlun_record WHERE kind = ?", kind);
        } else {
            String placeholders = map.keySet().stream().map(id -> "?").collect(Collectors.joining(", "));
            jdbc.update("DELETE FROM kunlun_record WHERE kind = ? AND id NOT IN (" + placeholders + ")",
                    prepend(kind, map.keySet()));
        }
    }

    private Object[] prepend(String kind, java.util.Set<String> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = kind;
        int i = 1;
        for (String id : ids) args[i++] = id;
        return args;
    }

    private String nameOf(Object value) {
        if (value instanceof Models.Project p) return p.name;
        if (value instanceof Models.DeploymentProfile p) return p.name;
        if (value instanceof Models.Artifact a) return a.fileName;
        if (value instanceof Models.SqlScript s) return s.name;
        if (value instanceof Models.BuildTask b) return b.projectName;
        if (value instanceof Models.ImageExportTask t) return t.imageReference;
        return null;
    }

    @Override
    public boolean remote() { return true; }

    private record Row(String kind, String id, String body) {}
}
