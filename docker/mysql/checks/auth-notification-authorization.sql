SELECT COUNT(*) = 7 AND SUM(active = 1) = 7
FROM bk_auth.auth_permission
WHERE permission_code IN (
  'notification.message.read.own',
  'notification.message.mark-read.own',
  'notification.message.send.own',
  'notification.message.send.all',
  'notification.email.send.all',
  'notification.robot.send.all',
  'notification.workflow.events.read.all'
);

SELECT COUNT(*) = 0
FROM bk_auth.auth_role_permission
WHERE permission_code IN (
  'notification.message.read.own',
  'notification.message.mark-read.own',
  'notification.message.send.own',
  'notification.message.send.all',
  'notification.email.send.all',
  'notification.robot.send.all',
  'notification.workflow.events.read.all'
);
