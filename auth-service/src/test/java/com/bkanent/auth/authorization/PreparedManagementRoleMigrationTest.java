package com.bkanent.auth.authorization;

import com.bkanent.common.authz.AuthzOperationPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedManagementRoleMigrationTest {
    @Test
    void preparedRoleExcludesExactlyTheSharedBaselineAndAssignsNoUser() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.exists(root.resolve("sql/migrations"))) root = root.getParent();
        String migration = Files.readString(root.resolve("sql/migrations/20261111_prepare_management_role.sql"));
        String check = Files.readString(root.resolve("docker/mysql/checks/auth-prepared-management-role.sql"));
        assertEquals(AuthzOperationPolicy.baselineOperations(), excludedOperations(migration));
        assertEquals(AuthzOperationPolicy.baselineOperations(), excludedOperations(check));
        assertFalse(Pattern.compile("(?i)INSERT\\s+INTO\\s+auth_user_role").matcher(migration).find());
        assertTrue(migration.contains("1, 0, 'MANAGEMENT'"));
        assertTrue(check.contains("assignable=0"));
    }

    private static Set<String> excludedOperations(String sql) {
        var clause = Pattern.compile("permission_code\\s+NOT\\s+IN\\s*\\((.*?)\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        assertTrue(clause.find(), "baseline exclusion missing");
        var values = Pattern.compile("'([a-z0-9._-]+)'").matcher(clause.group(1));
        Set<String> result = new TreeSet<>();
        while (values.find()) assertTrue(result.add(values.group(1)), "duplicate baseline operation");
        return result;
    }
}
