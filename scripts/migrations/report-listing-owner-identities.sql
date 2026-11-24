-- 只读对账：房源负责人、经纪人业务编号、业主档案是三个独立的标识。
-- 编号相同或当前映射不一致只提示复核。此查询不推断应绑定哪个业主。
SELECT
    l.id AS listing_id,
    l.status AS listing_status,
    l.broker_id AS broker_business_id,
    l.owner_user_id AS responsible_user_id,
    mapping.user_id AS current_broker_auth_user_id,
    l.owner_customer_id AS seller_profile_id,
    c.profile_type AS linked_profile_type,
    CASE
        WHEN l.owner_user_id IS NULL THEN 'RESPONSIBLE_USER_UNSET'
        WHEN u.id IS NULL THEN 'RESPONSIBLE_AUTH_USER_MISSING'
        WHEN mapping.user_id IS NULL THEN 'BROKER_MAPPING_MISSING_REVIEW'
        WHEN l.owner_user_id <> mapping.user_id THEN 'CURRENT_BROKER_MAPPING_DIFFERS_REVIEW'
        ELSE 'CURRENT_BROKER_MAPPING_MATCHES'
    END AS responsible_user_check,
    CASE
        WHEN l.owner_customer_id IS NULL THEN 'SELLER_LINK_UNSET'
        WHEN c.id IS NULL THEN 'SELLER_PROFILE_MISSING_OR_DELETED'
        WHEN UPPER(c.profile_type) <> 'OWNER' OR c.profile_type IS NULL THEN 'SELLER_PROFILE_WRONG_TYPE'
        WHEN l.owner_customer_id = l.broker_id OR l.owner_customer_id = l.owner_user_id
            THEN 'SELLER_ID_MATCHES_INTERNAL_ID_REVIEW'
        ELSE 'OWNER_PROFILE_EXISTS_RELATION_UNVERIFIED'
    END AS seller_link_check
FROM bk_listing.listing_info l
LEFT JOIN bk_auth.user_account u ON u.id = l.owner_user_id AND u.deleted = 0
LEFT JOIN bk_auth.auth_user_business_identity mapping
    ON mapping.identity_type = 'BROKER' AND mapping.identity_id = l.broker_id
LEFT JOIN bk_customer.customer_profile c ON c.id = l.owner_customer_id AND c.deleted = 0
WHERE l.deleted = 0
ORDER BY l.id;
