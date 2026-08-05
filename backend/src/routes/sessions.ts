import { Router } from 'express';

import { generateJoinCode, normalizeJoinCode } from '../lib/joinCode.js';
import { createLiveKitToken } from '../lib/livekit.js';
import { isValidPublicId, normalizePublicId } from '../lib/publicId.js';
import { supabaseAdmin } from '../lib/supabase.js';
import { requireAuth, type AuthedRequest } from '../middleware/auth.js';

const router = Router();

async function joinSessionAsTechnician(
  session: {
    id: string;
    join_code: string;
    room_name: string;
    customer_id: string;
    technician_id: string | null;
    status: string;
  },
  userId: string,
  displayName: string,
  email: string,
  res: import('express').Response,
) {
  if (session.customer_id === userId) {
    res.status(400).json({ error: 'You cannot join your own session' });
    return;
  }

  if (session.technician_id && session.technician_id !== userId) {
    res.status(409).json({ error: 'Session already has an expert' });
    return;
  }

  const { data: updated, error: updateError } = await supabaseAdmin
    .from('sessions')
    .update({
      technician_id: userId,
      status: 'active',
    })
    .eq('id', session.id)
    .select('id, join_code, room_name, status')
    .single();

  if (updateError || !updated) {
    res.status(500).json({ error: 'Failed to join session' });
    return;
  }

  const token = await createLiveKitToken({
    roomName: updated.room_name,
    identity: userId,
    name: displayName ?? email,
  });

  res.json({
    sessionId: updated.id,
    roomName: updated.room_name,
    joinCode: updated.join_code,
    status: updated.status,
    token,
  });
}

router.post('/join-by-id', requireAuth, async (req, res) => {
  const { user, profile } = req as AuthedRequest;
  const rawId = String(req.body?.targetPublicId ?? '');

  if (!isValidPublicId(rawId)) {
    res.status(400).json({ error: 'Invalid ID. Enter an 11-digit customer ID.' });
    return;
  }

  const publicId = normalizePublicId(rawId);

  const { data: customerProfile, error: profileError } = await supabaseAdmin
    .from('profiles')
    .select('id')
    .eq('public_id', publicId)
    .maybeSingle();

  if (profileError) {
    res.status(500).json({ error: 'Failed to look up customer' });
    return;
  }

  if (!customerProfile) {
    res.status(404).json({ error: 'Customer not found. Check the ID.' });
    return;
  }

  if (customerProfile.id === user.id) {
    res.status(400).json({ error: 'You cannot join your own session' });
    return;
  }

  const { data: session, error } = await supabaseAdmin
    .from('sessions')
    .select('id, join_code, room_name, customer_id, technician_id, status')
    .eq('customer_id', customerProfile.id)
    .eq('status', 'waiting')
    .order('created_at', { ascending: false })
    .limit(1)
    .maybeSingle();

  if (error) {
    res.status(500).json({ error: 'Failed to look up session' });
    return;
  }

  // New flow (2026-07-23): Expert join-by-ID creates the session if the
  // customer has none waiting. Customer stays on home until Realtime sees
  // status=active, then calls POST /customer-enter for their LiveKit token.
  let sessionToJoin = session;

  if (!sessionToJoin) {
    const { data: activeSession } = await supabaseAdmin
      .from('sessions')
      .select('id, status')
      .eq('customer_id', customerProfile.id)
      .eq('status', 'active')
      .maybeSingle();

    if (activeSession) {
      res.status(409).json({ error: 'Customer is already in an active session' });
      return;
    }

    const joinCode = generateJoinCode();
    const roomName = crypto.randomUUID();

    const { data: created, error: createError } = await supabaseAdmin
      .from('sessions')
      .insert({
        join_code: joinCode,
        room_name: roomName,
        customer_id: customerProfile.id,
        status: 'waiting',
      })
      .select('id, join_code, room_name, customer_id, technician_id, status')
      .single();

    if (createError || !created) {
      res.status(500).json({ error: 'Failed to create session for customer' });
      return;
    }

    sessionToJoin = created;
  }

  await joinSessionAsTechnician(
    sessionToJoin,
    user.id,
    profile.display_name ?? profile.email,
    profile.email,
    res,
  );
});

/** Customer claims LiveKit token for their active session (after expert joins). */
router.post('/customer-enter', requireAuth, async (req, res) => {
  const { user, profile } = req as AuthedRequest;

  const { data: session, error } = await supabaseAdmin
    .from('sessions')
    .select('id, join_code, room_name, customer_id, technician_id, status')
    .eq('customer_id', user.id)
    .eq('status', 'active')
    .order('created_at', { ascending: false })
    .limit(1)
    .maybeSingle();

  if (error) {
    res.status(500).json({ error: 'Failed to look up session' });
    return;
  }

  if (!session) {
    res.status(404).json({ error: 'No active session. Wait for an expert to join your ID.' });
    return;
  }

  const token = await createLiveKitToken({
    roomName: session.room_name,
    identity: user.id,
    name: profile.display_name ?? profile.email,
  });

  res.json({
    sessionId: session.id,
    roomName: session.room_name,
    joinCode: session.join_code,
    status: session.status,
    token,
  });
});

router.post('/', requireAuth, async (req, res) => {
    const { user, profile } = req as AuthedRequest;

    await supabaseAdmin
      .from('sessions')
      .update({
        status: 'ended',
        ended_at: new Date().toISOString(),
      })
      .eq('customer_id', user.id)
      .in('status', ['waiting', 'active']);

    const joinCode = generateJoinCode();
    const roomName = crypto.randomUUID();

    const { data, error } = await supabaseAdmin
      .from('sessions')
      .insert({
        join_code: joinCode,
        room_name: roomName,
        customer_id: user.id,
        status: 'waiting',
      })
      .select('id, join_code, room_name, status')
      .single();

    if (error || !data) {
      res.status(500).json({ error: 'Failed to create session' });
      return;
    }

    const token = await createLiveKitToken({
      roomName: data.room_name,
      identity: user.id,
      name: profile.display_name ?? profile.email,
    });

    res.status(201).json({
      sessionId: data.id,
      roomName: data.room_name,
      joinCode: data.join_code,
      status: data.status,
      token,
    });
});

router.post('/:joinCode/join', requireAuth, async (req, res) => {
    const { user, profile } = req as AuthedRequest;
    const joinCode = normalizeJoinCode(String(req.params.joinCode));

    const { data: session, error } = await supabaseAdmin
      .from('sessions')
      .select('id, join_code, room_name, customer_id, technician_id, status')
      .eq('join_code', joinCode)
      .in('status', ['waiting', 'active'])
      .maybeSingle();

    if (error) {
      res.status(500).json({ error: 'Failed to look up session' });
      return;
    }

    if (!session) {
      const { data: endedSession } = await supabaseAdmin
        .from('sessions')
        .select('id')
        .eq('join_code', joinCode)
        .eq('status', 'ended')
        .maybeSingle();

      if (endedSession) {
        res.status(410).json({
          error: 'This session has ended. Start a new session or ask for a new code.',
        });
        return;
      }

      res.status(404).json({ error: 'Session not found. Check the code.' });
      return;
    }

    if (session.customer_id === user.id) {
      res.status(400).json({ error: 'Customers cannot join their own session' });
      return;
    }

    if (session.technician_id && session.technician_id !== user.id) {
      res.status(409).json({ error: 'Session already has a technician' });
      return;
    }

    await joinSessionAsTechnician(
      session,
      user.id,
      profile.display_name ?? profile.email,
      profile.email,
      res,
    );
});

router.post('/:sessionId/end', requireAuth, async (req, res) => {
  const { user } = req as AuthedRequest;
  const { sessionId } = req.params;

  const { data: session, error } = await supabaseAdmin
    .from('sessions')
    .select('id, customer_id, technician_id, status')
    .eq('id', sessionId)
    .maybeSingle();

  if (error) {
    res.status(500).json({ error: 'Failed to look up session' });
    return;
  }

  if (!session) {
    res.status(404).json({ error: 'Session not found' });
    return;
  }

  const isParticipant =
    session.customer_id === user.id || session.technician_id === user.id;

  if (!isParticipant) {
    res.status(403).json({ error: 'Not a participant in this session' });
    return;
  }

  if (session.status === 'ended') {
    res.json({ sessionId: session.id, status: 'ended' });
    return;
  }

  const { data: updated, error: updateError } = await supabaseAdmin
    .from('sessions')
    .update({
      status: 'ended',
      ended_at: new Date().toISOString(),
    })
    .eq('id', sessionId)
    .select('id, status')
    .single();

  if (updateError || !updated) {
    res.status(500).json({ error: 'Failed to end session' });
    return;
  }

  res.json({ sessionId: updated.id, status: updated.status });
});

router.get('/:sessionId', requireAuth, async (req, res) => {
  const { user } = req as AuthedRequest;
  const { sessionId } = req.params;

  const { data: session, error } = await supabaseAdmin
    .from('sessions')
    .select(
      'id, join_code, room_name, customer_id, technician_id, status, created_at, ended_at'
    )
    .eq('id', sessionId)
    .maybeSingle();

  if (error) {
    res.status(500).json({ error: 'Failed to fetch session' });
    return;
  }

  if (!session) {
    res.status(404).json({ error: 'Session not found' });
    return;
  }

  const isParticipant =
    session.customer_id === user.id || session.technician_id === user.id;

  if (!isParticipant) {
    res.status(403).json({ error: 'Not a participant in this session' });
    return;
  }

  res.json({ session });
});

/**
 * Experiment-only: join as web expert without Supabase login.
 * Guarded by x-dashboard-key (default: dev-dashboard).
 * Uses a dedicated web-expert profile so the phone customer can enter normally.
 */
router.post('/web-expert-join', async (req, res) => {
  const expected = process.env.DASHBOARD_KEY ?? 'dev-dashboard';
  const provided = req.header('x-dashboard-key');
  if (!provided || provided !== expected) {
    res.status(401).json({ error: 'Invalid dashboard key' });
    return;
  }

  const rawId = String(req.body?.targetPublicId ?? '');
  if (!isValidPublicId(rawId)) {
    res.status(400).json({ error: 'Invalid ID. Enter an 11-digit customer ID.' });
    return;
  }

  const publicId = normalizePublicId(rawId);

  try {
    const expert = await ensureWebExpertProfile();

    const { data: customerProfile, error: profileError } = await supabaseAdmin
      .from('profiles')
      .select('id')
      .eq('public_id', publicId)
      .maybeSingle();

    if (profileError) {
      res.status(500).json({ error: 'Failed to look up customer' });
      return;
    }
    if (!customerProfile) {
      res.status(404).json({ error: 'Customer not found. Check the ID.' });
      return;
    }
    if (customerProfile.id === expert.id) {
      res.status(400).json({ error: 'Web expert cannot join itself' });
      return;
    }

    // Prefer a fresh room for the experiment — close stale waiting rows first.
    await supabaseAdmin
      .from('sessions')
      .update({
        status: 'ended',
        ended_at: new Date().toISOString(),
      })
      .eq('customer_id', customerProfile.id)
      .eq('status', 'waiting');

    const { data: activeSession } = await supabaseAdmin
      .from('sessions')
      .select('id, join_code, room_name, customer_id, technician_id, status')
      .eq('customer_id', customerProfile.id)
      .eq('status', 'active')
      .order('created_at', { ascending: false })
      .limit(1)
      .maybeSingle();

    // Rejoin if this web expert already owns the active session.
    if (activeSession && activeSession.technician_id === expert.id) {
      const token = await createLiveKitToken({
        roomName: activeSession.room_name,
        identity: expert.id,
        name: expert.display_name ?? expert.email,
      });
      res.json({
        sessionId: activeSession.id,
        roomName: activeSession.room_name,
        joinCode: activeSession.join_code,
        status: activeSession.status,
        token,
        expertEmail: expert.email,
      });
      return;
    }

    if (activeSession && activeSession.technician_id && activeSession.technician_id !== expert.id) {
      res.status(409).json({ error: 'Customer is already in an active session with another expert' });
      return;
    }

    const joinCode = generateJoinCode();
    const roomName = crypto.randomUUID();

    const { data: created, error: createError } = await supabaseAdmin
      .from('sessions')
      .insert({
        join_code: joinCode,
        room_name: roomName,
        customer_id: customerProfile.id,
        status: 'waiting',
      })
      .select('id, join_code, room_name, customer_id, technician_id, status')
      .single();

    if (createError || !created) {
      console.error('[web-expert-join] create failed', createError);
      res.status(500).json({ error: 'Failed to create session for customer' });
      return;
    }

    await joinSessionAsTechnician(
      created,
      expert.id,
      expert.display_name ?? 'Web Expert',
      expert.email,
      res,
    );
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('[web-expert-join] FAILED', message);
    res.status(500).json({ error: message });
  }
});

async function ensureWebExpertProfile(): Promise<{
  id: string;
  email: string;
  display_name: string | null;
}> {
  const email = 'web-expert@ggsapple.local';
  const displayName = 'Web Expert';

  const { data: existing } = await supabaseAdmin
    .from('profiles')
    .select('id, email, display_name')
    .eq('email', email)
    .maybeSingle();
  if (existing) return existing;

  const { data: createdAuth, error: createError } = await supabaseAdmin.auth.admin.createUser({
    email,
    password: crypto.randomUUID(),
    email_confirm: true,
    user_metadata: { display_name: displayName },
  });

  if (createError || !createdAuth.user) {
    // Race / already exists — look up by email via list.
    const { data: listed, error: listError } = await supabaseAdmin.auth.admin.listUsers({
      page: 1,
      perPage: 200,
    });
    if (listError) {
      throw new Error(createError?.message ?? listError.message);
    }
    const found = listed.users.find((u) => u.email === email);
    if (!found) {
      throw new Error(createError?.message ?? 'Failed to create web expert user');
    }
    const { data: profile } = await supabaseAdmin
      .from('profiles')
      .upsert({
        id: found.id,
        email,
        display_name: displayName,
        role: 'technician',
      })
      .select('id, email, display_name')
      .single();
    if (!profile) throw new Error('Failed to ensure web expert profile');
    return profile;
  }

  const { data: profile, error: profileError } = await supabaseAdmin
    .from('profiles')
    .upsert({
      id: createdAuth.user.id,
      email,
      display_name: displayName,
      role: 'technician',
    })
    .select('id, email, display_name')
    .single();

  if (profileError || !profile) {
    throw new Error(profileError?.message ?? 'Failed to create web expert profile');
  }

  console.log('[web-expert-join] created dedicated expert profile', profile.id);
  return profile;
}

export default router;
