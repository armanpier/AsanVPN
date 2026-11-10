import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('95.182.87.115', username='root', password='Arman4237340', timeout=10)

cmd = """
fuser -k 8088/tcp 2>/dev/null
python3 -c "
import socket
s = socket.socket()
s.bind(('0.0.0.0', 80))
s.listen(1)
print('BOUND PORT 80 SUCCESS')
s.close()
"
"""

_, stdout, stderr = ssh.exec_command(cmd)
print("STDOUT:", stdout.read().decode())
print("STDERR:", stderr.read().decode())
ssh.close()
