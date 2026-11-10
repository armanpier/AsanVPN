import paramiko
import time
import urllib.request

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('95.182.87.115', username='root', password='Arman4237340', timeout=10)

cmd = """
nohup python3 -m http.server 80 > /tmp/test_http80.log 2>&1 &
echo $!
"""
_, stdout, _ = ssh.exec_command(cmd)
pid = stdout.read().decode().strip()
print("Started HTTP server on port 80 with PID:", pid)

time.sleep(1)
try:
    res = urllib.request.urlopen("http://95.182.87.115/", timeout=5)
    print("Direct connection to port 80 STATUS:", res.status)
except Exception as e:
    print("Direct connection ERROR:", e)

# Clean up
ssh.exec_command(f"kill {pid}")
ssh.close()
