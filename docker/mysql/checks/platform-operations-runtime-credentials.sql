SELECT IF(
    (SELECT COUNT(*)=2
            AND SUM(account_locked='N')=2
            AND SUM(CHAR_LENGTH(authentication_string)>0)=2
     FROM mysql.user
     WHERE host='%' AND user IN ('platform_operations_app','platform_operations_source_ro')),
    1, 0
);
