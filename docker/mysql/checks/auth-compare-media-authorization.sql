SELECT COUNT(*)=8 AND SUM(active=1)=8
FROM bk_auth.auth_permission
WHERE permission_code IN (
  'compare.report.create','compare.report.read.own','compare.report.share.public',
  'media.task.submit.own','media.task.submit.all','media.task.read.own',
  'media.task.read.all','media.generate.background'
);

SELECT COUNT(*)=0
FROM bk_auth.auth_role_permission
WHERE permission_code IN (
  'compare.report.create','compare.report.read.own','compare.report.share.public',
  'media.task.submit.own','media.task.submit.all','media.task.read.own',
  'media.task.read.all','media.generate.background'
);
