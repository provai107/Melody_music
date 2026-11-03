const assert = require('assert');
const { WebSocket } = require('ws');
const AuthManager = require('./auth-manager');
const { server, roomManager } = require('./server');
const { Events, ErrorCodes } = require('./jam-protocol');

const TEST_PORT = 8089;

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function runTests() {
  console.log('--- STARTING MELODY JAM SERVER INTEGRATION TESTS ---');

  // Start test server
  await new Promise(resolve => server.listen(TEST_PORT, '127.0.0.1', resolve));
  console.log(`Test server running on port ${TEST_PORT}`);

  try {
    // 1. Auth tests
    console.log('Testing Guest Authentication & Token Verification...');
    const sessionHost = AuthManager.createSession('install_host_123', 'Host User');
    assert(sessionHost.token, 'Token should be minted');
    assert(sessionHost.guestId.startsWith('gst_'), 'Guest ID should have prefix');

    const verified = AuthManager.verifyToken(sessionHost.token);
    assert.strictEqual(verified.guestId, sessionHost.guestId);
    assert.strictEqual(verified.displayName, 'Host User');

    // Tampered token test
    const tampered = sessionHost.token.slice(0, -4) + 'abcd';
    assert.strictEqual(AuthManager.verifyToken(tampered), null, 'Tampered token must be rejected');

    console.log('✅ Guest Auth & Token tests passed');

    // 2. WebSocket Connection & Handshake Test
    console.log('Testing WebSocket Handshake with Host...');
    const hostWs = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/ws/jam?token=${sessionHost.token}`);
    await new Promise((resolve, reject) => {
      hostWs.on('open', resolve);
      hostWs.on('error', reject);
    });
    console.log('✅ Host WebSocket connected');

    // 3. Create Jam Room Test
    console.log('Testing CREATE_JAM event...');
    let roomCreatedData = null;
    hostWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.JAM_CREATED) {
        roomCreatedData = msg.data;
      }
    });

    hostWs.send(JSON.stringify({
      event: Events.CREATE_JAM,
      data: {
        initialTrack: {
          id: 'song_atif_01',
          title: 'Woh Lamhe',
          artists: 'Atif Aslam',
          durationSec: 320
        }
      },
      requestId: 'req_create_1'
    }));

    await sleep(300);
    assert(roomCreatedData, 'Must receive JAM_CREATED event');
    assert(roomCreatedData.room.roomCode.startsWith('JAM-'), 'Room code must start with JAM-');
    assert.strictEqual(roomCreatedData.myRole, 'HOST');
    const roomCode = roomCreatedData.room.roomCode;
    const roomId = roomCreatedData.room.roomId;
    console.log(`✅ Room created successfully: ${roomCode} (${roomId})`);

    // 4. Guest Join Test
    console.log('Testing Guest JOIN_JAM event...');
    const sessionGuest = AuthManager.createSession('install_guest_456', 'Guest Listener');
    const guestWs = new WebSocket(`ws://127.0.0.1:${TEST_PORT}/ws/jam?token=${sessionGuest.token}`);
    await new Promise((resolve, reject) => {
      guestWs.on('open', resolve);
      guestWs.on('error', reject);
    });

    let guestJoinedData = null;
    let hostNotifiedParticipantJoined = null;

    hostWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.PARTICIPANT_JOINED) {
        hostNotifiedParticipantJoined = msg.data;
      }
    });

    guestWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.JAM_JOINED) {
        guestJoinedData = msg.data;
      }
    });

    guestWs.send(JSON.stringify({
      event: Events.JOIN_JAM,
      data: { roomCode },
      requestId: 'req_join_1'
    }));

    await sleep(400);
    assert(guestJoinedData, 'Guest must receive JAM_JOINED event');
    assert.strictEqual(guestJoinedData.myRole, 'GUEST');
    assert(hostNotifiedParticipantJoined, 'Host must be notified of participant joining');
    assert.strictEqual(hostNotifiedParticipantJoined.participant.displayName, 'Guest Listener');
    console.log('✅ Guest joined and participant broadcast received');

    // 5. Synchronized Playback Events Test (Host Plays & Pauses)
    console.log('Testing Host PLAY & PAUSE broadcast to Guest...');
    let guestReceivedPlayback = null;
    guestWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.PLAYBACK_UPDATED) {
        guestReceivedPlayback = msg.data;
      }
    });

    hostWs.send(JSON.stringify({
      event: Events.PLAY,
      data: { positionMs: 15000 },
      requestId: 'req_play_1'
    }));

    await sleep(300);
    assert(guestReceivedPlayback, 'Guest must receive PLAYBACK_UPDATED event');
    assert.strictEqual(guestReceivedPlayback.playbackState.isPlaying, true);
    assert.strictEqual(guestReceivedPlayback.playbackState.positionMs, 15000);
    console.log('✅ Host PLAY event synchronized to Guest');

    // 6. Security Test: Guest Attempts Unauthorized Playback Action
    console.log('Testing Unauthorized Playback Rejection for Guest...');
    let guestReceivedError = null;
    guestWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.ERROR) {
        guestReceivedError = msg.data;
      }
    });

    guestWs.send(JSON.stringify({
      event: Events.PAUSE,
      data: { positionMs: 5000 },
      requestId: 'req_bad_pause'
    }));

    await sleep(300);
    assert(guestReceivedError, 'Server must reject unauthorized guest playback control');
    assert.strictEqual(guestReceivedError.code, ErrorCodes.HOST_PERMISSION_REQUIRED);
    console.log('✅ Unauthorized guest command rejected by server');

    // 7. Collaborative Queue Test
    console.log('Testing Collaborative Queue: Guest Adds Song...');
    let queueUpdatedBroadcast = null;
    hostWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.QUEUE_UPDATED) {
        queueUpdatedBroadcast = msg.data;
      }
    });

    guestWs.send(JSON.stringify({
      event: Events.ADD_TO_QUEUE,
      data: {
        songSummary: {
          id: 'song_arijit_02',
          title: 'Tum Hi Ho',
          artists: 'Arijit Singh',
          durationSec: 262
        }
      },
      requestId: 'req_queue_add'
    }));

    await sleep(300);
    assert(queueUpdatedBroadcast, 'Host must receive QUEUE_UPDATED broadcast');
    assert.strictEqual(queueUpdatedBroadcast.queue.length, 1);
    assert.strictEqual(queueUpdatedBroadcast.queue[0].songSummary.title, 'Tum Hi Ho');
    assert.strictEqual(queueUpdatedBroadcast.queue[0].addedByName, 'Guest Listener');
    console.log('✅ Collaborative queue add synchronized across room');

    // 8. Live Reactions Test
    console.log('Testing Live Emoji Reactions...');
    let reactionReceived = null;
    hostWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.REACTION_RECEIVED) {
        reactionReceived = msg.data;
      }
    });

    guestWs.send(JSON.stringify({
      event: Events.SEND_REACTION,
      data: { emoji: '🔥' },
      requestId: 'req_reaction_1'
    }));

    await sleep(300);
    assert(reactionReceived, 'Reaction must be received by other participants');
    assert.strictEqual(reactionReceived.emoji, '🔥');
    assert.strictEqual(reactionReceived.displayName, 'Guest Listener');
    console.log('✅ Live reaction delivered');

    // 9. Sync Response Test
    console.log('Testing REQUEST_SYNC & Latency Estimation...');
    let syncResp = null;
    guestWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.event === Events.SYNC_RESPONSE) {
        syncResp = msg.data;
      }
    });

    const clientSentTime = Date.now();
    guestWs.send(JSON.stringify({
      event: Events.REQUEST_SYNC,
      data: { clientTimestamp: clientSentTime },
      requestId: 'req_sync_1'
    }));

    await sleep(300);
    assert(syncResp, 'Must receive SYNC_RESPONSE');
    assert(syncResp.serverTimestamp >= clientSentTime, 'Server timestamp valid');
    assert(typeof syncResp.playbackState.calculatedPositionMs === 'number', 'Position estimated');
    console.log('✅ Clock synchronization & position calculation verified');

    // Cleanup
    hostWs.close();
    guestWs.close();
    await new Promise(resolve => server.close(resolve));
    console.log('--- ALL MELODY JAM SERVER INTEGRATION TESTS PASSED! 🎉 ---');
    process.exit(0);
  } catch (err) {
    console.error('❌ Test failed:', err);
    process.exit(1);
  }
}

runTests();
