package com.bkanent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge.datasource")
public class KnowledgeDataSourceProperties {
    private final Database primary = new Database();
    private final Database source = new Database();

    public Database getPrimary() { return primary; }
    public Database getSource() { return source; }

    public static class Database {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "com.mysql.cj.jdbc.Driver";

        public String getUrl() { return url; }
        public void setUrl(String value) { url = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String value) { driverClassName = value; }
    }
}
