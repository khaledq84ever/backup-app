// Backup App server — receives uploads from the Android app and serves the APK.
const express = require('express');
const multer = require('multer');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 4425;
const DATA_DIR = path.join(__dirname, 'data');
const PUBLIC_DIR = path.join(__dirname, 'public');
const VERSION_FILE = path.join(__dirname, 'version.json');
const BACKUP_KEY = process.env.BACKUP_APP_KEY;

// Every endpoint here reads or writes personal data (SMS, contacts, photos,
// installed APKs) with no other access control, so a missing key is treated
// as a misconfiguration rather than "run open" — refuse to start instead of
// silently serving everyone's backups to anyone who finds the IP:port.
if (!BACKUP_KEY) {
  console.error('BACKUP_APP_KEY is not set. Refusing to start with no auth on personal-data endpoints.');
  console.error('Set it, e.g.: BACKUP_APP_KEY=$(openssl rand -hex 32) node server.js');
  process.exit(1);
}

fs.mkdirSync(DATA_DIR, { recursive: true });

// One request crashing the whole process (as the destination() bug above
// did) takes down backups for every device, not just the bad request -
// log and keep serving instead of dying on an exception we didn't
// anticipate in testing.
process.on('uncaughtException', (err) => {
  console.error('uncaughtException (server stays up):', err);
});
process.on('unhandledRejection', (err) => {
  console.error('unhandledRejection (server stays up):', err);
});

const app = express();
app.use(express.static(PUBLIC_DIR));

function requireKey(req, res, next) {
  const supplied = Buffer.from(String(req.get('x-backup-key') || ''));
  const expected = Buffer.from(BACKUP_KEY);
  const ok = supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected);
  if (!ok) return res.status(401).json({ ok: false, error: 'unauthorized' });
  next();
}

// Only allow simple id-like device/category segments; relpath may have
// subfolders but never "..", an absolute path, or a device/category
// segment that would escape DATA_DIR.
function safeSegment(s) {
  return typeof s === 'string' && s.length > 0 && s.length < 200 && /^[a-zA-Z0-9._-]+$/.test(s);
}
function safeRelPath(rel) {
  if (typeof rel !== 'string' || rel.length === 0 || rel.length > 1000) return null;
  const norm = path.normalize(rel).replace(/^([/\\])+/, '');
  if (norm.split(/[/\\]/).includes('..')) return null;
  return norm;
}

const storage = multer.diskStorage({
  // Every branch here must call cb(err) instead of throwing: multer invokes
  // this from inside a busboy stream event, so a synchronous throw (as
  // happened here before with path.dirname(null) on a rejected relpath)
  // becomes an uncaught exception that kills the whole process, not just
  // this request - confirmed by actually sending a traversal relpath and
  // watching the server die.
  destination: (req, file, cb) => {
    const device = req.body.device;
    const category = req.body.category;
    if (!safeSegment(device) || !safeSegment(category)) {
      return cb(new Error('bad device/category'));
    }
    const rel = safeRelPath(req.body.relpath || file.originalname || 'file');
    if (!rel) {
      return cb(new Error('bad relpath'));
    }
    const dir = path.join(DATA_DIR, device, category, path.dirname(rel));
    try {
      fs.mkdirSync(dir, { recursive: true });
    } catch (e) {
      return cb(e);
    }
    req._destDir = dir;
    req._destName = path.basename(rel);
    cb(null, dir);
  },
  filename: (req, file, cb) => cb(null, req._destName),
});

const upload = multer({
  storage,
  limits: { fileSize: 2 * 1024 * 1024 * 1024 }, // 2GB per file
});

app.post('/api/upload', requireKey, (req, res) => {
  upload.single('file')(req, res, (err) => {
    if (err) {
      console.error('upload error:', err.message);
      return res.status(400).json({ ok: false, error: err.message });
    }
    if (!req.file) return res.status(400).json({ ok: false, error: 'no file' });
    res.json({ ok: true, path: `${req.body.device}/${req.body.category}/${req.body.relpath}` });
  });
});

function dirStats(dir) {
  let files = 0, bytes = 0, latest = 0;
  if (!fs.existsSync(dir)) return { files, bytes, latest };
  const walk = (d) => {
    for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, entry.name);
      if (entry.isDirectory()) walk(p);
      else {
        const st = fs.statSync(p);
        files++;
        bytes += st.size;
        latest = Math.max(latest, st.mtimeMs);
      }
    }
  };
  walk(dir);
  return { files, bytes, latest };
}

app.get('/api/devices', requireKey, (req, res) => {
  const devices = fs.existsSync(DATA_DIR)
    ? fs.readdirSync(DATA_DIR, { withFileTypes: true }).filter(d => d.isDirectory()).map(d => d.name)
    : [];
  res.json({ devices });
});

app.get('/api/status/:device', requireKey, (req, res) => {
  const device = req.params.device;
  if (!safeSegment(device)) return res.status(400).json({ ok: false, error: 'bad device' });
  const base = path.join(DATA_DIR, device);
  const categories = ['photos', 'videos', 'contacts', 'sms', 'files', 'apps'];
  const status = {};
  for (const c of categories) status[c] = dirStats(path.join(base, c));
  res.json({ ok: true, device, status });
});

// Auto-update: the Android app polls this on launch, unauthenticated (same
// trust level as the public APK download) so an old build can always find
// out about a new one even before it has a working key. Bump version.json
// (and drop the new APK at server/public/backupapp.apk) to ship an update —
// no server restart needed, it's read fresh on every request.
app.get('/api/version', (req, res) => {
  try {
    const v = JSON.parse(fs.readFileSync(VERSION_FILE, 'utf8'));
    res.json(v);
  } catch (e) {
    res.status(500).json({ error: 'version.json missing or invalid' });
  }
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`backup-app server listening on :${PORT}`);
});
