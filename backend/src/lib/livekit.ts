import { AccessToken } from 'livekit-server-sdk';

const apiKey = process.env.LIVEKIT_API_KEY ?? 'devkey';
const apiSecret = process.env.LIVEKIT_API_SECRET ?? 'secret';

export async function createLiveKitToken(params: {
  roomName: string;
  identity: string;
  name: string;
}): Promise<string> {
  const token = new AccessToken(apiKey, apiSecret, {
    identity: params.identity,
    name: params.name,
    ttl: '1h',
  });

  token.addGrant({
    roomJoin: true,
    room: params.roomName,
    canPublish: true,
    canSubscribe: true,
  });

  return token.toJwt();
}
