package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "agent.constraint")
public class SystemConstraintBootstrapProperties {

    private boolean bootstrapEnabled = true;

    public boolean isBootstrapEnabled() {
        return bootstrapEnabled;
    }

    public void setBootstrapEnabled(boolean bootstrapEnabled) {
        this.bootstrapEnabled = bootstrapEnabled;
    }

    public static class ConstraintDef {
        private String key;
        private String text;
        private String category;
        private String tags;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getTags() { return tags; }
        public void setTags(String tags) { this.tags = tags; }
    }

    public static class ConstraintsFile {
        private List<ConstraintDef> constraints;

        public List<ConstraintDef> getConstraints() { return constraints; }
        public void setConstraints(List<ConstraintDef> constraints) { this.constraints = constraints; }
    }
}
