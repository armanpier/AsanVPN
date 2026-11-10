import os
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('95.182.87.115', username='root', password='Arman4237340', timeout=10)

key = '12bf58a5-6acd-5f8b-a8af-20ec8f714f6c'
bundle_path = os.path.abspath('backend/arvan-edge/bundle.js')

sftp = ssh.open_sftp()
sftp.put(bundle_path, '/tmp/arvan_bundle.js')
sftp.close()

cmd = f"ARVAN_API_KEY='{key}' arvan ec deploy -f /tmp/arvan_bundle.js asanvpn-dashboard"
_, stdout, stderr = ssh.exec_command(cmd)
print("DEPLOY STDOUT:\n", stdout.read().decode())
print("DEPLOY STDERR:\n", stderr.read().decode())

ssh.close()
