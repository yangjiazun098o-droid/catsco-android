// Compatibility entry point: the supplied library is now the source of truth.
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const bundled = path.join(require('node:os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe');
const python = process.env.CATSCO_PYTHON || (fs.existsSync(bundled) ? bundled : 'python');
const result = spawnSync(python, [path.join(__dirname, 'sync-icon-library.py')], {
  stdio: 'inherit', env: { ...process.env, PYTHONIOENCODING: 'utf-8' },
});
if (result.error) throw result.error;
process.exit(result.status ?? 1);
