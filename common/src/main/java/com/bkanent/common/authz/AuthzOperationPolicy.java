package com.bkanent.common.authz;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared classification of operation identifiers that are available to every authenticated actor.
 * These identifiers may bind a signed request to its domain action, but are never permission grants.
 */
public final class AuthzOperationPolicy {
    private static final Set<String> BASELINE_OPERATIONS = Set.of(
            "agent.chat.use",
            "agent.workflow.read",
            "agent.workflow.artifact.read",
            "listing.read",
            "listing.create",
            "listing.update.own",
            "customer.read",
            "customer.create",
            "customer.update.own",
            "customer.follow.read.own",
            "customer.follow.write.own",
            "customer.favorite.read.own",
            "customer.favorite.write.own",
            "customer.match.own",
            "compare.report.create",
            "compare.report.read.own",
            "media.task.read.own",
            "notification.message.read.own",
            "notification.message.mark-read.own",
            "interview.initiate",
            "interview.read",
            "interview.respond",
            "interview.end",
            "memory.read",
            "memory.write",
            "memory.search");

    private AuthzOperationPolicy() { }

    public static boolean isBaseline(String operation) {
        return operation != null && BASELINE_OPERATIONS.contains(operation);
    }

    /**
     * Returns the signed execution-operation set: restricted grants plus stable baseline action
     * identifiers. The latter are not copied into AuthorizationSnapshot.permissions.
     */
    public static List<String> contextOperations(Collection<String> grantedPermissions) {
        TreeSet<String> operations = new TreeSet<>(BASELINE_OPERATIONS);
        if (grantedPermissions != null) {
            grantedPermissions.stream().filter(value -> value != null && !isBaseline(value))
                    .forEach(operations::add);
        }
        return List.copyOf(operations);
    }

    /** Removes baseline action identifiers before checking role or service permission grants. */
    public static List<String> requiredPermissions(Collection<String> operations) {
        if (operations == null || operations.isEmpty()) return List.of();
        return operations.stream().filter(value -> value != null && !isBaseline(value))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(TreeSet::new), List::copyOf));
    }

    /** Requires the Auth-signed request operation and, for restricted actions, its snapshot grant. */
    public static boolean allows(String operation, Collection<String> contextOperations,
                                 Collection<String> snapshotPermissions) {
        if (operation == null || contextOperations == null || !contextOperations.contains(operation)) return false;
        return isBaseline(operation) || snapshotPermissions != null && snapshotPermissions.contains(operation);
    }

    public static Set<String> baselineOperations() { return BASELINE_OPERATIONS; }
}
