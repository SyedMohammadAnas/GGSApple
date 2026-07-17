import { createClient } from '@supabase/supabase-js';

const supabaseUrl = process.env.SUPABASE_URL;
const serviceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY;

if (!supabaseUrl || !serviceRoleKey) {
  throw new Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required');
}

export const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey, {
  auth: { autoRefreshToken: false, persistSession: false },
});

export type UserRole = 'customer' | 'technician';

export interface ProfileRow {
  id: string;
  email: string;
  display_name: string | null;
  role: UserRole | null;
  avatar_url: string | null;
  public_id: string | null;
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
