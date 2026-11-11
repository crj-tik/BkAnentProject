#!/usr/bin/env python3
"""只读对账旧管理员权限与现有有效角色；不输出凭据或 token。"""
import importlib.util
import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlencode, urlsplit

sys.dont_write_bytecode = True
root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('auth_org_bootstrap', root / 'scripts/acceptance/bootstrap-auth-organizations.py')
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


def all_users(base, token):
    rows = []
    page = 1
    while True:
        query = urlencode({'page': page, 'pageSize': 100})
        result = bootstrap.request(base, '/auth/users?' + query, token)
        if not result.get('success'):
            raise RuntimeError('User inventory was rejected')
        data = result['data']
        rows.extend(data['records'])
        if page * 100 >= data['total']:
            return rows
        page += 1


def main():
    parser = argparse.ArgumentParser(description='只读核对旧管理员与角色权限')
    parser.add_argument('--output', type=Path, help='可选：写入本地审计结果')
    args = parser.parse_args()
    _, config = bootstrap.read_env()
    host = config.get('HOST_BIND_ADDRESS', '127.0.0.1') or '127.0.0.1'
    base = f'http://{host}:{config.get("GATEWAY_EXPOSED_PORT", "5010") or "5010"}'
    if urlsplit(base).hostname not in ('127.0.0.1', 'localhost', '::1'):
        raise RuntimeError('Only a local development Gateway is allowed')
    if not config.get('AUTH_ADMIN_USERNAME') or not config.get('AUTH_ADMIN_PASSWORD'):
        raise RuntimeError('Local AUTH_ADMIN_USERNAME and AUTH_ADMIN_PASSWORD are required')
    token = bootstrap.login(base, config['AUTH_ADMIN_USERNAME'], config['AUTH_ADMIN_PASSWORD'])
    catalog = bootstrap.request(base, '/auth/authorization/catalog', token)
    roles = bootstrap.request(base, '/auth/authorization/roles', token)
    if len(roles) >= 1000:
        raise RuntimeError('Role inventory may be truncated; audit through a complete source')
    users = all_users(base, token)
    # Refuse a report assembled across an observable authorization change.
    again_users = all_users(base, token)
    if (users != again_users or catalog != bootstrap.request(base, '/auth/authorization/catalog', token)
            or roles != bootstrap.request(base, '/auth/authorization/roles', token)):
        raise RuntimeError('Authorization data changed during audit; retry')
    source = (root / 'common/src/main/java/com/bkanent/common/authz/AuthzOperationPolicy.java').read_text(encoding='utf-8')
    match = re.search(r'BASELINE_OPERATIONS\s*=\s*Set\.of\((.*?)\);', source, re.S)
    if not match:
        raise RuntimeError('Cannot identify baseline operation policy')
    baseline = set(re.findall(r'"([a-z0-9._-]+)"', match.group(1)))
    active = {entry['code'] for entry in catalog if entry.get('active') and entry['code'] not in baseline}
    role_map = {role['code']: set(role.get('permissionCodes', [])) & active
                for role in roles if role.get('active')}
    prepared = next((role for role in roles if role.get('code') == 'PLATFORM_ADMIN'), None)
    if prepared and (not prepared.get('active') or prepared.get('assignable')):
        raise RuntimeError('Prepared administrator role must remain active and unassignable')
    staged_grants = role_map.get('PLATFORM_ADMIN', set())
    administrators = []
    for user in users:
        if not user.get('businessAdmin') or user.get('accountStatus') != 'ACTIVE':
            continue
        grants = set().union(*(role_map.get(code, set()) for code in user.get('roles', [])))
        administrators.append({'userId': user['userId'], 'permissionRevision': user['permissionRevision'],
                               'effectiveRoles': sorted(user.get('roles', [])),
                               'currentRestrictedPermissions': sorted(active),
                               'roleGrantedRestrictedPermissions': sorted(grants),
                               'missingFromRoles': sorted(active - grants),
                               'missingIfPreparedRoleAssigned': sorted(active - grants - staged_grants) if prepared else None})
    result = {'activeRestrictedCatalogCount': len(active), 'preparedRolePresent': bool(prepared),
              'administrators': administrators}
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + '\n', encoding='utf-8')
        print(f'Audited {len(administrators)} administrators; active restricted permissions: {len(active)}')
        for item in administrators:
            print(f'User {item["userId"]}: role grants {len(item["roleGrantedRestrictedPermissions"])}, '
                  f'missing {len(item["missingFromRoles"])}, '
                  f'prepared-role gap {len(item["missingIfPreparedRoleAssigned"]) if prepared else "not prepared"}')
    else:
        print(rendered)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, KeyError, TypeError) as error:
        print(f'Authorization audit failed: {error}', file=sys.stderr)
        sys.exit(1)
