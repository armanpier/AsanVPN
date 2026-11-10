import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('95.182.87.115', username='root', password='Arman4237340', timeout=10)

cmd = """python3 -c "
import sqlite3, json
conn = sqlite3.connect('/etc/asanvpn/asanvpn.db')
rows = conn.execute('SELECT key, SUBSTR(value, 1, 50) FROM kv_store').fetchall()
print('Total rows in DB:', len(rows))
for r in rows:
    print(r[0], '-->', r[1])
"
"""

_, stdout, stderr = ssh.exec_command(cmd)
print(stdout.read().decode())
print(stderr.read().decode())
ssh.close()
