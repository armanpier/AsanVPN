import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('95.182.87.115', username='root', password='Arman4237340', timeout=10)

cmd = """python3 -c "
import sys
sys.path.insert(0, '/etc/asanvpn')
from server import db
users = db.get_all_users()
print('get_all_users count:', len(users))
if not users:
    conn = db.conn
    rows = conn.execute(\\\"SELECT key, value FROM kv_store WHERE key NOT LIKE '__%' AND key != 'apk_meta'\\\").fetchall()
    print('Raw matching rows:', len(rows))
    for r in rows[:3]:
        print('KEY:', r[0])
        print('VAL TYPE:', type(r[1]))
        try:
            import json
            parsed = json.loads(r[1])
            print('PARSED TYPE:', type(parsed))
            if isinstance(parsed, str):
                print('Double-encoded JSON! Parsing again...')
                parsed2 = json.loads(parsed)
                print('PARSED2 TYPE:', type(parsed2))
        except Exception as e:
            print('ERR:', e)
"
"""

_, stdout, stderr = ssh.exec_command(cmd)
print(stdout.read().decode())
print(stderr.read().decode())
ssh.close()
