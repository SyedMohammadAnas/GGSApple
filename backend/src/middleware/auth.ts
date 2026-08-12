import type { NextFunction, Request, Response } from 'express';
import type { User } from '@supabase/supabase-js';

import { supabaseAdmin, type ProfileRow } from '../lib/supabase.js';

export interface AuthedRequest extends Request {
  user: User;
  profile: ProfileRow;
}

export async function requireAuth(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  const header = req.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Missing authorization token' });
    return;
  }

  const token = header.slice('Bearer '.length);
  const { data, error } = await supabaseAdmin.auth.getUser(token);

  if (error || !data.user) {
    res.status(401).json({ error: 'Invalid or expired token' });
    return;
  }

  const { data: profile, error: profileError } = await supabaseAdmin
    .from('profiles')
    .select('id, email, display_name, role, avatar_url')
    .eq('id', data.user.id)
    .single();

  if (profileError || !profile) {
    res.status(403).json({ error: 'Profile not found' });
    return;
  }

  (req as AuthedRequest).user = data.user;
  (req as AuthedRequest).profile = profile as ProfileRow;
  next();
}

export function requireRole(...roles: Array<'customer' | 'technician' | 'expert' | 'admin'>) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const profile = (req as AuthedRequest).profile;
    if (!profile.role || !roles.includes(profile.role)) {
      res.status(403).json({ error: 'Insufficient role for this action' });
      return;
    }
    next();
  };
}
