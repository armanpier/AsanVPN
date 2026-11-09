import os
import hashlib
import json
import subprocess

apk_path = 'AsanVPN.apk'
out_dir = 'apk_chunks'
os.makedirs(out_dir, exist_ok=True)

size = os.path.getsize(apk_path)
chunk_size = 18 * 1024 * 1024

chunks = 0
hasher = hashlib.sha256()

with open(apk_path, 'rb') as f:
    while True:
        data = f.read(chunk_size)
        if not data:
            break
        hasher.update(data)
        chunk_file = os.path.join(out_dir, f'chunk_{chunks}.bin')
        with open(chunk_file, 'wb') as cf:
            cf.write(data)
        chunks += 1

meta = {
    'version': '1.3.4',
    'versionCode': 10,
    'total_size': size,
    'total_chunks': chunks,
    'chunk_size': chunk_size,
    'filename': 'AsanVPN-v1.3.4-universal.apk',
    'sha256': hasher.hexdigest(),
    'changelog': 'رفع مشکل مسدودی ۳۰ دقیقه‌ای ورود، امکان بازگشایی آنی توسط پنل آسان داشبورد، و اتصال پایدار به سرورهای با اولویت بالا'
}

meta_file = os.path.join(out_dir, 'meta.json')
with open(meta_file, 'w', encoding='utf-8') as mf:
    json.dump(meta, mf)

print(f'Chunked {chunks} chunks for v1.3.2, total size: {size}')

ns = 'aaf46b23b63c44a0a846911ea4b2aa19'
worker_dir = os.path.abspath('backend/worker')

for i in range(chunks):
    chunk_path = os.path.abspath(os.path.join(out_dir, f'chunk_{i}.bin'))
    cmd = f'npx wrangler kv key put --remote --namespace-id={ns} apk_chunk_{i} --path="{chunk_path}"'
    print(f'Uploading apk_chunk_{i}...')
    res = subprocess.run(cmd, cwd=worker_dir, shell=True, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if res.returncode != 0:
        print(f'Failed apk_chunk_{i}:', res.stderr)
    else:
        print(f'Uploaded apk_chunk_{i}!')

meta_path = os.path.abspath(meta_file)
cmd_meta = f'npx wrangler kv key put --remote --namespace-id={ns} apk_meta --path="{meta_path}"'
print('Uploading apk_meta...')
res_meta = subprocess.run(cmd_meta, cwd=worker_dir, shell=True, capture_output=True, text=True, encoding='utf-8', errors='replace')
if res_meta.returncode != 0:
    print('Failed apk_meta:', res_meta.stderr)
else:
    print('Uploaded apk_meta successfully!')
