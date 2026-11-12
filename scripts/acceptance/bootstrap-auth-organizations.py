#!/usr/bin/env python3
"""显式初始化本地验收组织和账号；不输出凭据，不覆盖已有对象。"""
import argparse
import json
import os
import secrets
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
ENV_PATH = ROOT / '.env'
COMPANY = ('ACCEPTANCE_COMPANY', '验收公司', 'COMPANY')
STORE = ('ACCEPTANCE_STORE', '验收门店', 'STORE')
DEPARTMENT = ('ACCEPTANCE_OPERATIONS', '验收运营部门', 'DEPARTMENT')
SAMPLES = (
    ('BROKER', 'org_sample_broker', '验收门店经纪人', 'ACCEPTANCE_BROKER', '验收经纪人'),
    ('OPERATIONS', 'org_sample_operations', '验收运营职能人员', 'ACCEPTANCE_OPERATIONS', '验收运营职能'),
)


def read_env():
    content = ENV_PATH.read_text(encoding='utf-8-sig')
    result = {}
    for line in content.splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip().strip('"').strip("'")
    return content, result


def request(base, path, token=None, payload=None, method='GET'):
    headers = {'Accept': 'application/json'}
    data = None
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if payload is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    try:
        with urlopen(Request(base + path, data=data, headers=headers, method=method), timeout=20) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f'{method} {path.split("?")[0]} returned HTTP {error.code}') from None
    except (URLError, TimeoutError, ValueError) as error:
        raise RuntimeError(f'{method} {path.split("?")[0]} failed: {type(error).__name__}') from None


def login(base, username, password):
    response = request(base, '/auth/login', payload={'username': username, 'password': password}, method='POST')
    data = response.get('data') or {}
    if not response.get('success') or not data.get('accessToken'):
        raise RuntimeError('Login rejected; verify local acceptance credentials and account state')
    return data['accessToken']


def expect_rejected(base, path, token, status, payload=None, method='GET'):
    headers = {'Authorization': 'Bearer ' + token}
    data = None
    if payload is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(payload).encode('utf-8')
    try:
        with urlopen(Request(base + path, data=data, headers=headers, method=method), timeout=20):
            raise RuntimeError(f'{method} {path} unexpectedly succeeded')
    except HTTPError as error:
        if error.code != status:
            raise RuntimeError(f'{method} {path} returned HTTP {error.code}, expected {status}') from None


def users_named(base, token, username):
    found = []
    page = 1
    while True:
        query = urlencode({'keyword': username, 'page': page, 'pageSize': 100})
        response = request(base, '/auth/users?' + query, token)
        if not response.get('success'):
            raise RuntimeError('User inventory was rejected')
        data = response['data']
        found.extend(row for row in data['records'] if row.get('username') == username)
        if page * 100 >= data['total']:
            return found
        page += 1


def check_role(role, expected_name):
    return (role.get('name') == expected_name and role.get('active') is True
            and role.get('assignable') is True
            and role.get('permissionCodes') == []
            and role.get('roleType') in ('JOB', 'UNCLASSIFIED'))


def find_org(orgs, spec, parent):
    matches = [org for org in orgs if org.get('code') == spec[0]]
    if len(matches) > 1:
        raise RuntimeError('Duplicate acceptance organization code')
    if matches:
        org = matches[0]
        if (org.get('name'), org.get('type'), org.get('parentId'), org.get('status')) != (spec[1], spec[2], parent, 'ACTIVE'):
            raise RuntimeError('Existing acceptance organization conflicts with expected hierarchy')
        return org
    return None


def main():
    parser = argparse.ArgumentParser(description='初始化本地 Auth 组织验收数据')
    parser.add_argument('--apply-development', action='store_true', help='明确执行本地开发环境初始化')
    args = parser.parse_args()
    if not args.apply_development:
        raise RuntimeError('Explicit --apply-development is required')
    original, config = read_env()
    host = config.get('HOST_BIND_ADDRESS', '127.0.0.1') or '127.0.0.1'
    base = f'http://{host}:{config.get("GATEWAY_EXPOSED_PORT", "5010") or "5010"}'
    if urlsplit(base).hostname not in ('127.0.0.1', 'localhost', '::1'):
        raise RuntimeError('Only a local development Gateway is allowed')
    if not config.get('AUTH_ADMIN_USERNAME') or not config.get('AUTH_ADMIN_PASSWORD'):
        raise RuntimeError('Local AUTH_ADMIN_USERNAME and AUTH_ADMIN_PASSWORD are required')
    admin = login(base, config['AUTH_ADMIN_USERNAME'], config['AUTH_ADMIN_PASSWORD'])
    roles = request(base, '/auth/authorization/roles', admin)
    orgs = request(base, '/auth/authorization/organizations', admin)
    company = find_org(orgs, COMPANY, None)
    if company:
        find_org(orgs, STORE, company['id'])
        find_org(orgs, DEPARTMENT, company['id'])
    elif any(org.get('code') in (STORE[0], DEPARTMENT[0]) for org in orgs):
        raise RuntimeError('Acceptance children exist without the expected company')

    accounts = {}
    additions = {}
    for suffix, default_user, display, role_code, role_name in SAMPLES:
        existing_roles = [role for role in roles if role.get('code') == role_code]
        if len(existing_roles) > 1 or existing_roles and not check_role(existing_roles[0], role_name):
            raise RuntimeError('Acceptance role conflicts with an existing role or has restricted permissions')
        user_key, password_key = f'ORG_ACCEPTANCE_{suffix}_USERNAME', f'ORG_ACCEPTANCE_{suffix}_PASSWORD'
        username = config.get(user_key) or default_user
        existing = users_named(base, admin, username)
        if len(existing) > 1 or existing and not config.get(password_key):
            raise RuntimeError('Existing acceptance account requires its previously saved local credential')
        for key, value in ((user_key, username), (password_key, config.get(password_key) or secrets.token_urlsafe(24))):
            if key in config and not config[key]:
                raise RuntimeError('An acceptance credential key exists but is blank; fill or remove the blank entry')
            if key not in config:
                additions[key] = value
                config[key] = value
        accounts[suffix] = (existing[0] if existing else None, username, display, role_code, role_name, password_key)
        if existing:
            account = existing[0]
            if (account.get('displayName') != display or account.get('businessAdmin')
                    or account.get('tenantCode') != COMPANY[0] or account.get('accountStatus') != 'ACTIVE'
                    or sorted(account.get('roles', [])) != [role_code]):
                raise RuntimeError('Existing acceptance account has unexpected profile, state or roles')
            login(base, username, config[password_key])

    if additions:
        if ENV_PATH.read_text(encoding='utf-8-sig') != original:
            raise RuntimeError('Local .env changed during preflight; retry without overwriting it')
        with ENV_PATH.open('a', encoding='utf-8', newline='\n') as file:
            if original and not original.endswith(('\n', '\r')):
                file.write('\n')
            file.write('\n# Local organization acceptance accounts; ignored by Git.\n')
            for key, value in additions.items():
                file.write(f'{key}={value}\n')
            file.flush()
            os.fsync(file.fileno())

    for _, _, _, role_code, role_name in SAMPLES:
        if not any(role.get('code') == role_code for role in roles):
            request(base, '/auth/authorization/roles', admin,
                    {'code': role_code, 'name': role_name, 'description': '本地验收用，不授予受限权限',
                     'active': True, 'assignable': True, 'permissionCodes': [],
                     'roleType': 'JOB'}, 'POST')
        else:
            role = next(role for role in roles if role.get('code') == role_code)
            if role.get('roleType') == 'UNCLASSIFIED':
                request(base, f'/auth/authorization/roles/{role["id"]}/type', admin,
                        {'roleType': 'JOB'}, 'PUT')
    if not company:
        company = request(base, '/auth/authorization/organizations', admin,
                          {'code': COMPANY[0], 'name': COMPANY[1], 'type': COMPANY[2]}, 'POST')
        orgs.append(company)
    children = {}
    for spec in (STORE, DEPARTMENT):
        org = find_org(orgs, spec, company['id'])
        if not org:
            org = request(base, '/auth/authorization/organizations', admin,
                          {'code': spec[0], 'name': spec[1], 'type': spec[2], 'parentId': company['id']}, 'POST')
        children[spec[2]] = org

    for suffix, (existing, username, display, role_code, _, password_key) in accounts.items():
        if existing:
            user_id = existing['userId']
        else:
            created = request(base, '/auth/users', admin,
                              {'username': username, 'password': config[password_key], 'displayName': display,
                               'roleCode': role_code, 'tenantCode': COMPANY[0]}, 'POST')
            if not created.get('success') or not created.get('data'):
                raise RuntimeError('Acceptance user creation failed')
            user_id = created['data']['userId']
        target = children['STORE' if suffix == 'BROKER' else 'DEPARTMENT']
        user_token = login(base, username, config[password_key])
        me = request(base, '/auth/me', user_token)
        if not me.get('success') or me['data'].get('businessAdmin') or sorted(me['data'].get('roles', [])) != [role_code]:
            raise RuntimeError('Acceptance user authorization does not match expected role')
        memberships = me['data'].get('organizations', [])
        if memberships and (len(memberships) != 1 or memberships[0].get('id') != target['id']):
            raise RuntimeError('Acceptance user has a conflicting organization membership')
        if not memberships:
            request(base, f'/auth/authorization/organizations/{target["id"]}/members', admin,
                    {'userId': user_id}, 'POST')
            me = request(base, '/auth/me', user_token)
        if not me.get('success') or len(me['data'].get('organizations', [])) != 1 or me['data']['organizations'][0].get('id') != target['id']:
            raise RuntimeError('Acceptance organization membership verification failed')
        expect_rejected(base, '/auth/authorization/organizations', user_token, 403)
        expect_rejected(base, '/auth/me/profile', user_token, 400,
                        {'displayName': display, 'userId': 1}, 'PATCH')
        print(f'{suffix}: userId={user_id}, organizationId={target["id"]}, role={role_code}')
    print('Organization acceptance setup complete. Credentials remain in the ignored local .env.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(f'Acceptance setup failed: {error}', file=sys.stderr)
        sys.exit(1)
