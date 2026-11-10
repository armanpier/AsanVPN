import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
print("Connecting to VPS 95.182.87.115...")
ssh.connect('95.182.87.115', username='root', password='Arman4237340', timeout=10)

sftp = ssh.open_sftp()

# Ensure /etc/asanvpn exists
ssh.exec_command('mkdir -p /etc/asanvpn')

# Upload files
print("Uploading server.py...")
sftp.put('backend/vps-server/server.py', '/etc/asanvpn/server.py')

print("Uploading data.json...")
sftp.put('backend/data/backup_kv.json', '/etc/asanvpn/data.json')

print("Uploading asanvpn-api.service...")
sftp.put('backend/vps-server/asanvpn-api.service', '/etc/systemd/system/asanvpn-api.service')

sftp.close()

# Stop any rogue process on port 80 and reload systemd
commands = [
    'chmod +x /etc/asanvpn/server.py',
    'fuser -k 80/tcp 2>/dev/null || true',
    'systemctl daemon-reload',
    'systemctl enable asanvpn-api',
    'systemctl restart asanvpn-api'
]

print("Starting asanvpn-api service on VPS...")
for cmd in commands:
    _, stdout, stderr = ssh.exec_command(cmd)
    stdout.read()

time.sleep(2)

_, stdout, _ = ssh.exec_command('systemctl status asanvpn-api --no-pager')
print("SERVICE STATUS:\n", stdout.read().decode())

_, stdout, _ = ssh.exec_command('curl -s http://127.0.0.1/health')
print("HEALTH CHECK:\n", stdout.read().decode())

_, stdout, _ = ssh.exec_command("curl -s -H 'X-Admin-Token: Arman4237340' http://127.0.0.1/api/v1/admin/users | head -c 300")
print("USERS API CHECK:\n", stdout.read().decode())

ssh.close()
