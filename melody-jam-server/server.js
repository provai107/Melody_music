const http = require('http');
const url = require('url');
const { WebSocketServer } = require('ws');
const AuthManager = require('./auth-manager');
const RoomManager = require('./room-manager');
const { Events, ErrorCodes, createMessage, createError } = require('./jam-protocol');

const PORT = parseInt(process.env.PORT || process.env.JAM_PORT || '8080', 10);
const HOST = process.env.HOST || process.env.JAM_HOST || '0.0.0.0';

const roomManager = new RoomManager();

// Simple in-memory rate limiting map for WS sockets
const rateLimits = new WeakMap();

function isRateLimited(ws) {
  const now = Date.now();
  let bucket = rateLimits.get(ws);
  if (!bucket) {
    bucket = { tokens: 30, lastCheck: now };
    rateLimits.set(ws, bucket);
    return false;
  }
  const elapsed = (now - bucket.lastCheck) / 1000;
  bucket.tokens = Math.min(30, bucket.tokens + elapsed * 10); // replenish 10 tokens/sec, max 30
  bucket.lastCheck = now;
  if (bucket.tokens >= 1) {
    bucket.tokens -= 1;
    return false;
  }
  return true;
}

// HTTP Request Handler
const server = http.createServer((req, res) => {
  const reqUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = reqUrl.pathname;

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Health check
  if (req.method === 'GET' && pathname === '/api/v1/jam/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'UP',
      service: 'Melody Jam Real-Time Backend',
      timestamp: Date.now(),
      activeRooms: roomManager.roomsById.size,
      version: '1.0.0'
    }));
    return;
  }

  // Create or renew guest session
  if (req.method === 'POST' && pathname === '/api/v1/jam/guest/session') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        const data = body ? JSON.parse(body) : {};
        const installationId = data.installationId || '';
        const displayName = data.displayName || 'Melody User';

        const session = AuthManager.createSession(installationId, displayName);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          success: true,
          data: session
        }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          success: false,
          error: { code: ErrorCodes.INVALID_PAYLOAD, message: 'Invalid JSON payload' }
        }));
      }
    });
    return;
  }

  // Room Preview Info (before joining)
  if (req.method === 'GET' && pathname.startsWith('/api/v1/jam/rooms/') && pathname.endsWith('/info')) {
    const parts = pathname.split('/');
    const codeOrId = decodeURIComponent(parts[5] || '');
    const room = roomManager.findRoom(codeOrId);

    if (!room) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        success: false,
        error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Room not found' }
      }));
      return;
    }

    const host = room.participants.get(room.hostGuestId);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      success: true,
      data: {
        roomId: room.roomId,
        roomCode: room.roomCode,
        hostDisplayName: host ? host.displayName : 'Host',
        participantCount: room.participants.size,
        currentTrack: room.playbackState.songSummary,
        isPlaying: room.playbackState.isPlaying
      }
    }));
    return;
  }

  // Default 404
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Endpoint not found' }));
});

// WebSocket Server
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (request, socket, head) => {
  const reqUrl = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
  if (reqUrl.pathname === '/ws/jam') {
    const token = reqUrl.searchParams.get('token');
    const session = AuthManager.verifyToken(token);

    if (!session) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    wss.handleUpgrade(request, socket, head, ws => {
      ws.guestSession = session;
      wss.emit('connection', ws, request);
    });
  } else {
    socket.destroy();
  }
});

wss.on('connection', ws => {
  const session = ws.guestSession;

  ws.on('message', rawMessage => {
    if (isRateLimited(ws)) {
      ws.send(createError(ErrorCodes.RATE_LIMITED, 'Message rate limit exceeded'));
      return;
    }

    let parsed;
    try {
      parsed = JSON.parse(rawMessage.toString());
    } catch (_e) {
      ws.send(createError(ErrorCodes.INVALID_PAYLOAD, 'Malformed JSON'));
      return;
    }

    const { event, data = {}, requestId } = parsed;

    switch (event) {
      case Events.HEARTBEAT:
        ws.send(createMessage(Events.HEARTBEAT_ACK, { clientTimestamp: data.clientTimestamp }, requestId));
        break;

      case Events.CREATE_JAM: {
        const room = roomManager.createRoom(session, data.initialTrack, ws);
        ws.send(createMessage(Events.JAM_CREATED, {
          room: roomManager.serializeRoom(room),
          myRole: 'HOST'
        }, requestId));
        break;
      }

      case Events.JOIN_JAM: {
        const result = roomManager.joinRoom(data.roomCode || data.roomId, session, ws);
        if (result.error) {
          ws.send(createError(result.error.code, result.error.message, requestId));
        } else {
          ws.send(createMessage(Events.JAM_JOINED, {
            room: roomManager.serializeRoom(result.room),
            myRole: result.participant.role,
            isReconnecting: result.isReconnecting
          }, requestId));
        }
        break;
      }

      case Events.LEAVE_JAM: {
        roomManager.leaveRoom(ws);
        ws.send(createMessage(Events.JAM_ENDED, { reason: 'USER_LEFT' }, requestId));
        break;
      }

      case Events.PLAY: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.updatePlayback(binding.roomId, session.guestId, 'PLAY', data);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      case Events.PAUSE: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.updatePlayback(binding.roomId, session.guestId, 'PAUSE', data);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      case Events.SEEK: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.updatePlayback(binding.roomId, session.guestId, 'SEEK', data);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      case Events.TRACK_CHANGED: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.updatePlayback(binding.roomId, session.guestId, 'TRACK_CHANGED', data);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      case Events.ADD_TO_QUEUE: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.addToQueue(binding.roomId, session.guestId, data.songSummary);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      case Events.REMOVE_FROM_QUEUE: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.removeFromQueue(binding.roomId, session.guestId, data.queueItemId);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      case Events.REORDER_QUEUE: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.reorderQueue(binding.roomId, session.guestId, data.fromIndex, data.toIndex);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      case Events.SEND_REACTION: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        roomManager.sendReaction(binding.roomId, session.guestId, data.emoji);
        break;
      }

      case Events.REQUEST_SYNC: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const syncResp = roomManager.getSyncResponse(binding.roomId, data.clientTimestamp);
        if (syncResp) {
          ws.send(createMessage(Events.SYNC_RESPONSE, syncResp, requestId));
        }
        break;
      }

      case Events.UPDATE_ROOM_SETTINGS: {
        const binding = roomManager.socketToParticipant.get(ws);
        if (!binding) return ws.send(createError(ErrorCodes.UNAUTHORIZED, 'Not in a room', requestId));
        const res = roomManager.updateRoomSettings(binding.roomId, session.guestId, data.settings);
        if (res.error) ws.send(createError(res.error.code, res.error.message, requestId));
        break;
      }

      default:
        ws.send(createError(ErrorCodes.INVALID_PAYLOAD, `Unrecognized event: ${event}`, requestId));
    }
  });

  ws.on('close', () => {
    roomManager.leaveRoom(ws);
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[Melody Jam Server] Running on http://${HOST}:${PORT}`);
  console.log(`[Melody Jam Server] WebSocket endpoint ws://${HOST}:${PORT}/ws/jam`);
});

function gracefulShutdown(signal) {
  console.log(`[Melody Jam Server] Received ${signal}, closing server...`);
  wss.clients.forEach((client) => {
    try {
      client.close(1001, 'Server shutting down');
    } catch (_) {}
  });
  server.close(() => {
    console.log('[Melody Jam Server] Closed all connections.');
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 5000);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

module.exports = { server, roomManager };
