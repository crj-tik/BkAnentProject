package com.bkanent.businessreview.security;
import java.util.Set;
public interface TrustedStoreScopePort {
    StoreScope requireStore(Long requestedStoreId, String operation);
    record StoreScope(long actorId, long storeId, Set<String> operations, long permissionRevision) {
        public StoreScope { operations = Set.copyOf(operations); }
    }
}
