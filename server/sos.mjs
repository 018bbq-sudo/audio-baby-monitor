import { Relay, createRelayHandler } from './sos-relay.js';
import { parseProfile } from './sos-protocol.js';

export function sosHandler(encoded = process.env.SOS_PROFILES_JSON) {
  // Missing/invalid configuration must not prevent the audio relay from starting.
  if (!encoded) return unavailable;
  try {
    const entries = JSON.parse(encoded);
    if (!Array.isArray(entries) || entries.length !== 4) throw new Error('profiles');
    const profiles = entries.map(p => parseProfile(`SOS1:${JSON.stringify(p)}`));
    const roles = profiles.map(p => p.role === 'child' ? p.childId : p.name);
    if (!['katya', 'sasha', 'Папа', 'Мама'].every(role => roles.includes(role)) ||
        new Set(profiles.map(p => p.id)).size !== 4 || new Set(profiles.map(p => p.key)).size !== 4 ||
        profiles.some(p => p.server !== 'https://audio-baby-relay.onrender.com/sos')) throw new Error('profiles');
    const handler = createRelayHandler(new Relay(profiles), '/sos', false);
    const expiresAt = Math.min(...profiles.map(p => p.expiresAt));
    return (req, res) => Date.now() >= expiresAt ? unavailable(req, res) : handler(req, res);
  } catch {
    console.error('SOS disabled: invalid or expired configuration. Audio relay remains available.');
    return unavailable;
  }
}

function unavailable(req, res) {
  res.writeHead(503, { 'content-type': 'text/plain', 'cache-control': 'no-store' });
  res.end('SOS is not configured or pairing has expired.');
}

