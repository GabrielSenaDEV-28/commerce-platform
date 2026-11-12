const { randomBytes } = require('node:crypto');
const { mkdirSync, writeFileSync } = require('node:fs');

mkdirSync('/run/dev-secrets', { recursive: true, mode: 0o700 });
for (const name of ['database-password', 'jwt-secret']) {
  try {
    writeFileSync(`/run/dev-secrets/${name}`, randomBytes(48).toString('base64'), { flag: 'wx', mode: 0o600 });
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
  }
}
