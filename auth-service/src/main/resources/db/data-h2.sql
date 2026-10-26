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

-- Trusted local fixture mapping only; never infer admin from role_code in runtime writes.
UPDATE user_account SET business_admin=1 WHERE username='admin01';
INSERT INTO auth_user_authorization_state(user_id,status) SELECT id,'ACTIVE' FROM user_account;
INSERT INTO auth_user_role(user_id,role_id,active)
SELECT u.id,r.id,1 FROM user_account u JOIN auth_role r ON r.role_code=u.role_code;
INSERT INTO auth_permission(permission_code,domain,operation) VALUES
('agent.chat.use','agent','chat.use'),
('agent.workflow.read','agent','workflow.read'),
('agent.workflow.artifact.read','agent','workflow.artifact.read'),
('auth.users.manage','auth','users.manage'),('auth.roles.manage','auth','roles.manage'),
('auth.business-identity.manage','auth','business-identity.manage'),
('interview.read','interview','read'),('interview.respond','interview','respond'),
('listing.read','listing','read'),('memory.read','memory','read');
INSERT INTO auth_role_permission(role_id,permission_code)
SELECT r.id,p.permission_code FROM auth_role r CROSS JOIN auth_permission p
WHERE r.role_code='BROKER' AND p.permission_code IN
('agent.chat.use','agent.workflow.read','agent.workflow.artifact.read','interview.read','interview.respond','listing.read','memory.read');
