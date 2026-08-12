import { createClient } from '@supabase/supabase-js';

const supabaseUrl = process.env.SUPABASE_URL;
const serviceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY;

if (!supabaseUrl || !serviceRoleKey) {
  throw new Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required');
}

export const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey, {
  auth: { autoRefreshToken: false, persistSession: false },
});

/** DB roles used by Instant + Assist AR (lab also had technician). */
export type UserRole = 'customer' | 'technician' | 'expert' | 'admin';

export interface ProfileRow {
  id: string;
  email: string;
  display_name: string | null;
  role: UserRole | null;
  avatar_url: string | null;
  public_id: string | null;
}

/** Who may join as the expert / technician side of a session. */
export function isExpertCapableRole(role: string | null | undefined): boolean {
  const r = (role ?? '').toLowerCase();
  return r === 'expert' || r === 'admin' || r === 'technician';
}

export interface SessionRow {
  id: string;
  join_code: string;
  room_name: string;
  customer_id: string;
  technician_id: string | null;
  status: string;
  created_at: string;
  ended_at: string | null;
}
