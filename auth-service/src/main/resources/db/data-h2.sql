INSERT INTO user_account (username, password_hash, display_name, role_code, tenant_code, account_status, deleted)
VALUES ('broker01', '$2a$10$ZmOp25bIZbbJLus780rqTuVZff7jjdcmJ4VqhXgio/t073Q3b5AXC', 'Broker Demo', 'BROKER', 'local-demo', 'ACTIVE', 0);

INSERT INTO auth_role (role_code, role_name, description, active, assignable, deleted)
VALUES ('ADMIN', '系统管理员', '维护用户、角色和系统账户', 1, 1, 0);
INSERT INTO auth_role (role_code, role_name, description, active, assignable, deleted)
VALUES ('MANAGER', '门店经理', '查看门店经营数据并管理业务协作', 1, 1, 0);
INSERT INTO auth_role (role_code, role_name, description, active, assignable, deleted)
VALUES ('BROKER', '经纪人', '处理房源、客户和智能助手任务', 1, 1, 0);

INSERT INTO user_account (username, password_hash, display_name, role_code, tenant_code, account_status, deleted)
VALUES ('admin01', '$2a$10$ZmOp25bIZbbJLus780rqTuVZff7jjdcmJ4VqhXgio/t073Q3b5AXC', '系统管理员', 'ADMIN', 'local-demo', 'ACTIVE', 0);
