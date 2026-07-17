import { AccessToken } from 'livekit-server-sdk';

async function mint(identity, name, publish, subscribe) {
  const token = new AccessToken('devkey', 'secret', {
    identity,
    name,
    ttl: '2h',
  });
  token.addGrant({
    roomJoin: true,
    room: 'native-spike-test',
    canPublish: publish,
    canSubscribe: subscribe,
  });
  return token.toJwt();
}

const host = await mint('spike-host', 'Spike Host', true, true);
const tech = await mint('spike-tech', 'Spike Technician', false, true);
console.log('ROOM=native-spike-test');
console.log('HOST_TOKEN=' + host);
console.log('TECH_TOKEN=' + tech);
