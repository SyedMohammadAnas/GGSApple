import cors from 'cors';
import dotenv from 'dotenv';
import express from 'express';
import path from 'path';
import { fileURLToPath } from 'url';

import sessionsRouter from './routes/sessions.js';
import usersRouter from './routes/users.js';
import modelsRouter from './routes/models.js';
import adminRouter from './routes/admin.js';

dotenv.config({ path: path.resolve(process.cwd(), '../.env') });
dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

app.use('/api/sessions', sessionsRouter);
app.use('/api/users', usersRouter);
app.use('/api/models', modelsRouter);
app.use('/api/admin', adminRouter);

const publicPath = path.join(__dirname, '../public');
// Dashboard moved to asset-dashboard/ (Vercel). Static assets remain on API.

// Health check — Phase 0 gate endpoint
app.get('/health', (_req, res) => {
  res.status(200).json({
    status: 'ok',
    timestamp: new Date().toISOString(),
  });
});

// Static asset serving for Draco-compressed GLB models and thumbnails
const assetsPath = path.join(__dirname, '../assets');
app.use(
  '/assets/models',
  express.static(path.join(assetsPath, 'models'), {
    setHeaders: (res) => {
      res.setHeader('Content-Type', 'model/gltf-binary');
      res.setHeader('Cache-Control', 'public, max-age=86400');
    },
  })
);
app.use(
  '/assets/thumbnails',
  express.static(path.join(assetsPath, 'thumbnails'), {
    setHeaders: (res) => {
      res.setHeader('Content-Type', 'image/png');
      res.setHeader('Cache-Control', 'public, max-age=86400');
    },
  })
);

const server = app.listen(PORT, () => {
  console.log(`Remote AR API listening on port ${PORT}`);
  console.log(`Health: http://localhost:${PORT}/health`);
  console.log(`Assets: http://localhost:${PORT}/assets/models/`);
});

server.on('error', (err: NodeJS.ErrnoException) => {
  if (err.code === 'EADDRINUSE') {
    console.error(
      `Port ${PORT} is already in use. Stop the other process (e.g. a previous "npm run dev" or "docker compose up") or set PORT in .env to a different value.`
    );
    console.error(`Windows: netstat -ano | findstr :${PORT}  then  taskkill /PID <pid> /F`);
    process.exit(1);
  }
  throw err;
});
