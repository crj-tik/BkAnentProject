SELECT COUNT(*)=2
   AND COALESCE(SUM(active=1),0)=2
   AND COALESCE(SUM(
       (permission_code='compare.report.create' AND domain='listing'
           AND operation='comparison.report.create')
       OR
       (permission_code='compare.report.read.own' AND domain='listing'
           AND operation='comparison.report.read.own')
   ),0)=2
FROM bk_auth.auth_permission
WHERE permission_code IN ('compare.report.create','compare.report.read.own');
