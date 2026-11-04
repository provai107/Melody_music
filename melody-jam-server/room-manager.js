const crypto = require('crypto');
const { Events, ErrorCodes, createMessage, createError } = require('./jam-protocol');

const MAX_PARTICIPANTS_PER_ROOM = 32;
const ROOM_INACTIVITY_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours
const HOST_DISCONNECT_GRACE_PERIOD_MS = 45 * 1000; // 45 seconds

class RoomManager {
  constructor() {
    this.roomsById = new Map(); // roomId -> JamRoom
    this.roomsByCode = new Map(); // roomCode -> JamRoom
    this.socketToParticipant = new Map(); // ws -> { roomId, guestId }

    // Start periodic cleanup timer
    setInterval(() => this.cleanupInactiveRooms(), 60 * 1000);
  }

  generateRoomCode() {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // Avoid confusing 0/O, 1/I
    let code = '';
    do {
      code = 'JAM-';
      for (let i = 0; i < 4; i++) {
        code += chars.charAt(Math.floor(Math.random() * chars.length));
      }
    } while (this.roomsByCode.has(code));
    return code;
  }

  createRoom(hostGuest, initialTrack = null, socket = null) {
    const roomId = 'room_' + crypto.randomUUID().replace(/-/g, '');
    const roomCode = this.generateRoomCode();
    const now = Date.now();

    const hostParticipant = {
      participantId: 'p_' + crypto.randomBytes(6).toString('hex'),
      guestId: hostGuest.guestId,
      displayName: hostGuest.displayName || 'Host',
      avatarIndex: Math.floor(Math.random() * 6),
      role: 'HOST',
      connectionStatus: 'CONNECTED',
      joinedAt: now,
      socket: socket
    };

    const initialPlaybackState = {
      trackId: initialTrack ? initialTrack.id : null,
      songSummary: initialTrack || null,
      isPlaying: false,
      positionMs: 0,
      serverTimestamp: now,
      stateVersion: 1
    };

    const room = {
      roomId,
      roomCode,
      hostGuestId: hostGuest.guestId,
      visibility: 'PRIVATE',
      createdAt: now,
      updatedAt: now,
      expiresAt: now + ROOM_INACTIVITY_TTL_MS,
      settings: {
        allowGuestControl: false,
        allowGuestQueueAdd: true,
        allowGuestQueueRemoveOwn: true
      },
      playbackState: initialPlaybackState,
      queue: [],
      participants: new Map([[hostGuest.guestId, hostParticipant]]),
      hostDisconnectTimer: null
    };

    this.roomsById.set(roomId, room);
    this.roomsByCode.set(roomCode, room);

    if (socket) {
      this.socketToParticipant.set(socket, { roomId, guestId: hostGuest.guestId });
    }

    return room;
  }

  findRoom(codeOrId) {
    if (!codeOrId) return null;
    const clean = codeOrId.trim().toUpperCase();
    return this.roomsByCode.get(clean) ||
           this.roomsByCode.get('JAM-' + clean) ||
           (clean.startsWith('JAM-') ? this.roomsByCode.get(clean.substring(4)) : null) ||
           this.roomsById.get(codeOrId);
  }

  joinRoom(codeOrId, guest, socket = null) {
    const room = this.findRoom(codeOrId);
    if (!room) {
      return { error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Jam room not found or expired' } };
    }

    if (room.participants.size >= MAX_PARTICIPANTS_PER_ROOM && !room.participants.has(guest.guestId)) {
      return { error: { code: ErrorCodes.ROOM_FULL, message: 'Jam room is full' } };
    }

    const now = Date.now();
    room.updatedAt = now;
    room.expiresAt = now + ROOM_INACTIVITY_TTL_MS;

    let participant = room.participants.get(guest.guestId);
    const isReconnecting = !!participant;

    if (participant) {
      // Reconnection
      participant.connectionStatus = 'CONNECTED';
      participant.socket = socket;
      if (guest.displayName) participant.displayName = guest.displayName;

      if (room.hostGuestId === guest.guestId && room.hostDisconnectTimer) {
        clearTimeout(room.hostDisconnectTimer);
        room.hostDisconnectTimer = null;
      }
    } else {
      // New Joiner
      participant = {
        participantId: 'p_' + crypto.randomBytes(6).toString('hex'),
        guestId: guest.guestId,
        displayName: guest.displayName || 'Guest',
        avatarIndex: Math.floor(Math.random() * 6),
        role: (room.hostGuestId === guest.guestId) ? 'HOST' : 'GUEST',
        connectionStatus: 'CONNECTED',
        joinedAt: now,
        socket: socket
      };
      room.participants.set(guest.guestId, participant);
    }

    if (socket) {
      this.socketToParticipant.set(socket, { roomId: room.roomId, guestId: guest.guestId });
    }

    // Broadcast participant joined/reconnected to others
    this.broadcast(room, Events.PARTICIPANT_JOINED, {
      participant: this.serializeParticipant(participant),
      participantCount: room.participants.size
    }, socket);

    return { room, participant, isReconnecting };
  }

  leaveRoom(socket) {
    const binding = this.socketToParticipant.get(socket);
    if (!binding) return null;

    const { roomId, guestId } = binding;
    this.socketToParticipant.delete(socket);

    const room = this.roomsById.get(roomId);
    if (!room) return null;

    const participant = room.participants.get(guestId);
    if (!participant) return null;

    const isHost = (room.hostGuestId === guestId);

    if (isHost) {
      participant.connectionStatus = 'DISCONNECTED';
      participant.socket = null;

      // Start grace period for host return
      if (!room.hostDisconnectTimer) {
        room.hostDisconnectTimer = setTimeout(() => {
          this.handleHostGracePeriodExpired(room);
        }, HOST_DISCONNECT_GRACE_PERIOD_MS);
      }

      this.broadcast(room, Events.PARTICIPANT_LEFT, {
        guestId,
        displayName: participant.displayName,
        isHost: true,
        isTemporary: true,
        participantCount: Array.from(room.participants.values()).filter(p => p.connectionStatus === 'CONNECTED').length
      });
    } else {
      room.participants.delete(guestId);
      this.broadcast(room, Events.PARTICIPANT_LEFT, {
        guestId,
        displayName: participant.displayName,
        isHost: false,
        isTemporary: false,
        participantCount: room.participants.size
      });
    }

    return { room, guestId, isHost };
  }

  handleHostGracePeriodExpired(room) {
    room.hostDisconnectTimer = null;
    const connectedGuests = Array.from(room.participants.values()).filter(
      p => p.connectionStatus === 'CONNECTED' && p.guestId !== room.hostGuestId
    );

    if (connectedGuests.length > 0) {
      // Transfer host to the earliest joined connected guest
      connectedGuests.sort((a, b) => a.joinedAt - b.joinedAt);
      const newHost = connectedGuests[0];
      newHost.role = 'HOST';
      room.hostGuestId = newHost.guestId;

      this.broadcast(room, Events.HOST_CHANGED, {
        newHostGuestId: newHost.guestId,
        newHostDisplayName: newHost.displayName,
        reason: 'HOST_DISCONNECTED_MIGRATION'
      });
    } else {
      // End room safely
      this.broadcast(room, Events.JAM_ENDED, {
        reason: 'HOST_DISCONNECTED'
      });
      this.deleteRoom(room);
    }
  }

  deleteRoom(room) {
    if (room.hostDisconnectTimer) {
      clearTimeout(room.hostDisconnectTimer);
      room.hostDisconnectTimer = null;
    }
    this.roomsById.delete(room.roomId);
    this.roomsByCode.delete(room.roomCode);
  }

  updatePlayback(roomId, guestId, action, params = {}) {
    const room = this.roomsById.get(roomId);
    if (!room) return { error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Room not found' } };

    const isHost = (room.hostGuestId === guestId);
    if (!isHost && !room.settings.allowGuestControl) {
      return { error: { code: ErrorCodes.HOST_PERMISSION_REQUIRED, message: 'Only the Jam host can control playback' } };
    }

    const now = Date.now();
    const curr = room.playbackState;
    let newPosition = curr.positionMs;

    if (curr.isPlaying) {
      const elapsed = Math.max(0, now - curr.serverTimestamp);
      newPosition = curr.positionMs + elapsed;
    }

    curr.stateVersion += 1;
    curr.serverTimestamp = now;
    room.updatedAt = now;
    room.expiresAt = now + ROOM_INACTIVITY_TTL_MS;

    switch (action) {
      case 'PLAY':
        curr.isPlaying = true;
        if (typeof params.positionMs === 'number' && params.positionMs >= 0) {
          curr.positionMs = params.positionMs;
        } else {
          curr.positionMs = newPosition;
        }
        break;

      case 'PAUSE':
        curr.isPlaying = false;
        if (typeof params.positionMs === 'number' && params.positionMs >= 0) {
          curr.positionMs = params.positionMs;
        } else {
          curr.positionMs = newPosition;
        }
        break;

      case 'SEEK':
        if (typeof params.positionMs === 'number' && params.positionMs >= 0) {
          curr.positionMs = params.positionMs;
        }
        break;

      case 'TRACK_CHANGED':
        if (params.trackId) {
          curr.trackId = params.trackId;
          curr.songSummary = params.songSummary || null;
          curr.positionMs = typeof params.positionMs === 'number' ? params.positionMs : 0;
          curr.isPlaying = params.isPlaying !== undefined ? !!params.isPlaying : true;
        }
        break;

      default:
        return { error: { code: ErrorCodes.INVALID_PAYLOAD, message: 'Unknown playback action' } };
    }

    this.broadcast(room, Events.PLAYBACK_UPDATED, {
      playbackState: this.serializePlaybackState(curr),
      action,
      triggeredByGuestId: guestId
    });

    return { success: true, playbackState: curr };
  }

  addToQueue(roomId, guestId, songSummary) {
    const room = this.roomsById.get(roomId);
    if (!room) return { error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Room not found' } };

    const participant = room.participants.get(guestId);
    if (!participant) return { error: { code: ErrorCodes.UNAUTHORIZED, message: 'Not a member of this room' } };

    if (!room.settings.allowGuestQueueAdd && room.hostGuestId !== guestId) {
      return { error: { code: ErrorCodes.GUEST_NOT_ALLOWED, message: 'Adding to queue is disabled by host' } };
    }

    if (!songSummary || !songSummary.id || !songSummary.title) {
      return { error: { code: ErrorCodes.INVALID_PAYLOAD, message: 'Invalid song metadata' } };
    }

    const queueItem = {
      queueItemId: 'qi_' + crypto.randomBytes(6).toString('hex'),
      trackId: songSummary.id,
      songSummary,
      addedByGuestId: guestId,
      addedByName: participant.displayName,
      addedAt: Date.now()
    };

    room.queue.push(queueItem);
    room.updatedAt = Date.now();
    room.expiresAt = Date.now() + ROOM_INACTIVITY_TTL_MS;

    this.broadcast(room, Events.QUEUE_UPDATED, {
      queue: room.queue,
      addedItem: queueItem
    });

    return { success: true, queueItem, queue: room.queue };
  }

  removeFromQueue(roomId, guestId, queueItemId) {
    const room = this.roomsById.get(roomId);
    if (!room) return { error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Room not found' } };

    const itemIdx = room.queue.findIndex(q => q.queueItemId === queueItemId);
    if (itemIdx < 0) {
      return { error: { code: ErrorCodes.INVALID_PAYLOAD, message: 'Queue item not found' } };
    }

    const item = room.queue[itemIdx];
    const isHost = (room.hostGuestId === guestId);
    const isCreator = (item.addedByGuestId === guestId);

    if (!isHost && (!isCreator || !room.settings.allowGuestQueueRemoveOwn)) {
      return { error: { code: ErrorCodes.HOST_PERMISSION_REQUIRED, message: 'Cannot remove songs added by other participants' } };
    }

    const removed = room.queue.splice(itemIdx, 1)[0];
    room.updatedAt = Date.now();

    this.broadcast(room, Events.QUEUE_UPDATED, {
      queue: room.queue,
      removedItem: removed
    });

    return { success: true, removedItem: removed, queue: room.queue };
  }

  reorderQueue(roomId, guestId, fromIdx, toIdx) {
    const room = this.roomsById.get(roomId);
    if (!room) return { error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Room not found' } };

    const isHost = (room.hostGuestId === guestId);
    if (!isHost) {
      return { error: { code: ErrorCodes.HOST_PERMISSION_REQUIRED, message: 'Only host can reorder queue' } };
    }

    if (fromIdx < 0 || fromIdx >= room.queue.length || toIdx < 0 || toIdx >= room.queue.length) {
      return { error: { code: ErrorCodes.INVALID_PAYLOAD, message: 'Indices out of bounds' } };
    }

    const item = room.queue.splice(fromIdx, 1)[0];
    room.queue.splice(toIdx, 0, item);
    room.updatedAt = Date.now();

    this.broadcast(room, Events.QUEUE_UPDATED, {
      queue: room.queue
    });

    return { success: true, queue: room.queue };
  }

  sendReaction(roomId, guestId, emoji) {
    const room = this.roomsById.get(roomId);
    if (!room) return { error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Room not found' } };

    const participant = room.participants.get(guestId);
    if (!participant) return { error: { code: ErrorCodes.UNAUTHORIZED, message: 'Participant not in room' } };

    const reactionPayload = {
      guestId,
      displayName: participant.displayName,
      emoji: (emoji || '❤️').substring(0, 4),
      timestamp: Date.now()
    };

    this.broadcast(room, Events.REACTION_RECEIVED, reactionPayload);
    return { success: true };
  }

  updateRoomSettings(roomId, guestId, newSettings = {}) {
    const room = this.roomsById.get(roomId);
    if (!room) return { error: { code: ErrorCodes.ROOM_NOT_FOUND, message: 'Room not found' } };

    if (room.hostGuestId !== guestId) {
      return { error: { code: ErrorCodes.HOST_PERMISSION_REQUIRED, message: 'Only host can update room settings' } };
    }

    if (typeof newSettings.allowGuestControl === 'boolean') {
      room.settings.allowGuestControl = newSettings.allowGuestControl;
    }
    if (typeof newSettings.allowGuestQueueAdd === 'boolean') {
      room.settings.allowGuestQueueAdd = newSettings.allowGuestQueueAdd;
    }
    if (typeof newSettings.allowGuestQueueRemoveOwn === 'boolean') {
      room.settings.allowGuestQueueRemoveOwn = newSettings.allowGuestQueueRemoveOwn;
    }

    room.updatedAt = Date.now();
    this.broadcast(room, Events.ROOM_SETTINGS_UPDATED, {
      settings: room.settings
    });

    return { success: true, settings: room.settings };
  }

  getSyncResponse(roomId, clientTimestamp) {
    const room = this.roomsById.get(roomId);
    if (!room) return null;

    const now = Date.now();
    const curr = room.playbackState;
    let effectivePos = curr.positionMs;
    if (curr.isPlaying) {
      effectivePos += Math.max(0, now - curr.serverTimestamp);
    }

    return {
      clientTimestamp,
      serverTimestamp: now,
      playbackState: {
        ...this.serializePlaybackState(curr),
        calculatedPositionMs: effectivePos
      },
      stateVersion: curr.stateVersion
    };
  }

  serializeParticipant(p) {
    return {
      participantId: p.participantId,
      guestId: p.guestId,
      displayName: p.displayName,
      avatarIndex: p.avatarIndex,
      role: p.role,
      connectionStatus: p.connectionStatus,
      joinedAt: p.joinedAt
    };
  }

  serializePlaybackState(state) {
    return {
      trackId: state.trackId,
      songSummary: state.songSummary,
      isPlaying: state.isPlaying,
      positionMs: state.positionMs,
      serverTimestamp: state.serverTimestamp,
      stateVersion: state.stateVersion
    };
  }

  serializeRoom(room) {
    return {
      roomId: room.roomId,
      roomCode: room.roomCode,
      hostGuestId: room.hostGuestId,
      visibility: room.visibility,
      createdAt: room.createdAt,
      settings: room.settings,
      playbackState: this.serializePlaybackState(room.playbackState),
      queue: room.queue,
      participants: Array.from(room.participants.values()).map(p => this.serializeParticipant(p)),
      serverTimestamp: Date.now()
    };
  }

  broadcast(room, event, data = {}, excludeSocket = null) {
    const message = createMessage(event, data);
    for (const participant of room.participants.values()) {
      if (participant.socket && participant.socket !== excludeSocket && participant.socket.readyState === 1) { // 1 = OPEN
        try {
          participant.socket.send(message);
        } catch (_err) {
          // Socket write error handled on close
        }
      }
    }
  }

  cleanupInactiveRooms() {
    const now = Date.now();
    for (const [roomId, room] of this.roomsById.entries()) {
      if (now > room.expiresAt) {
        this.broadcast(room, Events.JAM_ENDED, { reason: 'ROOM_EXPIRED_INACTIVITY' });
        this.deleteRoom(room);
      }
    }
  }
}

module.exports = RoomManager;
