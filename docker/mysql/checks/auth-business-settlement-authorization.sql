SELECT (SELECT COUNT(*) FROM bk_auth.auth_permission
        WHERE permission_code IN (
            'business.kpi.read.all',
            'business.workload.read.all',
            'business.assessment.calculate.all',
            'business.ranking.read.all',
            'business.ranking.rebuild.all',
            'business.turnover.read.all',
            'business.store-dashboard.read.all',
            'business.dashboard.read.all',
            'settlement.rule.read.all',
            'settlement.rule.write.all',
            'settlement.calculate.all',
            'settlement.record.create.all',
            'settlement.split.calculate.all',
            'settlement.generate.all',
            'settlement.record.read.all',
            'settlement.split.read.all',
            'settlement.payout-status.update.all',
            'settlement.summary.read.all',
            'settlement.summary.rebuild.all',
            'settlement.batch.create.all',
            'settlement.commission.read.all'
        ) AND active=1 AND permission_code=CONCAT(domain, '.', operation))=21
   AND (SELECT COUNT(*) FROM bk_auth.auth_authorization_migration
        WHERE migration_id='20261026_business_settlement_authorization')=1;
