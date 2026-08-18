// Backup App server — receives uploads from the Android app and serves the APK.
const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 4425;
const DATA_DIR = path.join(__dirname, 'data');
const PUBLIC_DIR = path.join(__dirname, 'public');

fs.mkdirSync(DATA_DIR, { recursive: true });

const app = express();
app.use(express.static(PUBLIC_DIR));

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
  destination: (req, file, cb) => {
    const device = req.body.device;
    const category = req.body.category;
    if (!safeSegment(device) || !safeSegment(category)) {
      return cb(new Error('bad device/category'));
    }
    const rel = safeRelPath(req.body.relpath || file.originalname || 'file');
    const dir = path.join(DATA_DIR, device, category, path.dirname(rel));
    fs.mkdirSync(dir, { recursive: true });
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

app.post('/api/upload', (req, res) => {
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

app.get('/api/devices', (req, res) => {
  const devices = fs.existsSync(DATA_DIR)
    ? fs.readdirSync(DATA_DIR, { withFileTypes: true }).filter(d => d.isDirectory()).map(d => d.name)
    : [];
  res.json({ devices });
});

app.get('/api/status/:device', (req, res) => {
  const device = req.params.device;
  if (!safeSegment(device)) return res.status(400).json({ ok: false, error: 'bad device' });
  const base = path.join(DATA_DIR, device);
  const categories = ['photos', 'videos', 'contacts', 'sms', 'files'];
  const status = {};
  for (const c of categories) status[c] = dirStats(path.join(base, c));
  res.json({ ok: true, device, status });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`backup-app server listening on :${PORT}`);
});
