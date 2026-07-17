import { Router } from 'express';

import { supabaseAdmin } from '../lib/supabase.js';
import { requireAuth, type AuthedRequest } from '../middleware/auth.js';

const router = Router();

router.get('/me', requireAuth, async (req, res) => {
  const { user, profile } = req as AuthedRequest;

  const { data, error } = await supabaseAdmin
    .from('profiles')
    .select('id, email, display_name, role, avatar_url, public_id')
    .eq('id', user.id)
    .single();

  if (error || !data) {
    res.status(500).json({ error: 'Failed to fetch profile' });
    return;
  }

  res.json({
    id: data.id,
    email: data.email,
    displayName: data.display_name,
    role: data.role,
    avatarUrl: data.avatar_url,
    publicId: data.public_id,
  });
});

export default router;
