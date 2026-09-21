import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { seal, unseal } from './sos-protocol.js';
import { sosHandler } from './sos.mjs';
import { createServer } from 'node:http';

test('missing or invalid SOS configuration fails closed', async () => {
  for (const config of ['', '{bad json', '[]']) {
    const server = createServer(sosHandler(config));
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    try {
      const response = await fetch(`http://127.0.0.1:${server.address().port}/sos/exchange`, {method:'POST',body:'{}'});
      assert.equal(response.status,503);
    } finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
  }
});

test('audio payload still relays while SOS exchanges encrypted location data', { timeout: 15000 }, async () => {
  const profiles = [['parent','Папа'], ['parent','Мама'], ['child','Катя','katya'], ['child','Саша','sasha']].map(([role,name,childId]) => ({role,name,childId,id:randomBytes(16).toString('hex'),key:randomBytes(32).toString('hex'),expiresAt:Date.now()+60000,server:'https://audio-baby-relay.onrender.com/sos'}));
  const child = spawn(process.execPath, ['server.mjs'], {cwd:new URL('.',import.meta.url),env:{...process.env,PORT:'0',SOS_PROFILES_JSON:JSON.stringify(profiles)},stdio:['ignore','pipe','pipe']});
  const sockets=[];
  try {
    const port = await new Promise((resolve,reject) => {
      let text='';child.stdout.on('data',data=>{text+=data;const m=text.match(/listening on port (\d+)/);if(m)resolve(m[1]);});child.on('error',reject);child.on('exit',()=>reject(new Error('Relay exited before ready')));
    });
    const base=`http://127.0.0.1:${port}`;
    assert.equal((await fetch(base+'/health')).status,200);
    assert.equal((await fetch(base+'/sos/health')).status,200);
    const room=randomBytes(16).toString('hex');
    const parent=new WebSocket(`ws://127.0.0.1:${port}/audio?role=parent&room=${room}`);sockets.push(parent);await once(parent,'open');
    const baby=new WebSocket(`ws://127.0.0.1:${port}/audio?role=baby&room=${room}`);sockets.push(baby);await once(baby,'open');
    const payload=randomBytes(64);
    const received=new Promise(resolve=>parent.addEventListener('message',async event=>{if(typeof event.data!=='string')resolve(Buffer.from(await event.data.arrayBuffer()));}));
    baby.send(payload);
    const p=profiles[3],id=randomBytes(16).toString('hex');
    const envelope=seal(p,id,'request',{sentAt:Date.now(),command:{op:'publish',sampleId:randomBytes(16).toString('hex'),reading:{point:{latitude:1,longitude:2,accuracy:10,measuredAt:Date.now()},battery:null}}},randomBytes);
    const response=await fetch(base+'/sos/exchange',{method:'POST',body:JSON.stringify(envelope)});
    assert.equal(response.status,200);
    assert.equal(unseal(p,await response.json(),'response',id).snapshot.children[0].point.latitude,1);
    assert.deepEqual(await received,payload);
    assert.equal((await fetch(base+'/sos/exchange',{method:'POST',body:'{}'})).status,400);
  } finally { for(const socket of sockets)socket.close();child.kill();await once(child,'exit'); }
});

