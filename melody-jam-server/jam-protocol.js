/**
 * Melody Jam Protocol Definitions
 * Client-Server Real-Time Event Contracts
 */

const Events = {
  // Client to Server
  CREATE_JAM: 'CREATE_JAM',
  JOIN_JAM: 'JOIN_JAM',
  LEAVE_JAM: 'LEAVE_JAM',
  PLAY: 'PLAY',
  PAUSE: 'PAUSE',
  SEEK: 'SEEK',
  SKIP: 'SKIP',
  TRACK_CHANGED: 'TRACK_CHANGED',
  ADD_TO_QUEUE: 'ADD_TO_QUEUE',
  REMOVE_FROM_QUEUE: 'REMOVE_FROM_QUEUE',
  REORDER_QUEUE: 'REORDER_QUEUE',
  SEND_REACTION: 'SEND_REACTION',
  REQUEST_SYNC: 'REQUEST_SYNC',
  HEARTBEAT: 'HEARTBEAT',
  UPDATE_ROOM_SETTINGS: 'UPDATE_ROOM_SETTINGS',
  KICK_PARTICIPANT: 'KICK_PARTICIPANT',
  TRANSFER_HOST: 'TRANSFER_HOST',

  // Server to Client
  JAM_CREATED: 'JAM_CREATED',
  JAM_JOINED: 'JAM_JOINED',
  JAM_STATE: 'JAM_STATE',
  PLAYBACK_UPDATED: 'PLAYBACK_UPDATED',
  TRACK_CHANGED_BROADCAST: 'TRACK_CHANGED_BROADCAST',
  QUEUE_UPDATED: 'QUEUE_UPDATED',
  PARTICIPANT_JOINED: 'PARTICIPANT_JOINED',
  PARTICIPANT_LEFT: 'PARTICIPANT_LEFT',
  REACTION_RECEIVED: 'REACTION_RECEIVED',
  HOST_CHANGED: 'HOST_CHANGED',
  JAM_ENDED: 'JAM_ENDED',
  SYNC_RESPONSE: 'SYNC_RESPONSE',
  ERROR: 'ERROR',
  HEARTBEAT_ACK: 'HEARTBEAT_ACK',
  ROOM_SETTINGS_UPDATED: 'ROOM_SETTINGS_UPDATED'
};

const ErrorCodes = {
  UNAUTHORIZED: 'UNAUTHORIZED',
  ROOM_NOT_FOUND: 'ROOM_NOT_FOUND',
  ROOM_FULL: 'ROOM_FULL',
  INVALID_PAYLOAD: 'INVALID_PAYLOAD',
  HOST_PERMISSION_REQUIRED: 'HOST_PERMISSION_REQUIRED',
  GUEST_NOT_ALLOWED: 'GUEST_NOT_ALLOWED',
  RATE_LIMITED: 'RATE_LIMITED',
  INTERNAL_ERROR: 'INTERNAL_ERROR'
};

function createMessage(event, data = {}, requestId = null) {
  return JSON.stringify({
    event,
    data,
    requestId,
    serverTimestamp: Date.now(),
    version: '1.0'
  });
}

function createError(code, message, requestId = null) {
  return JSON.stringify({
    event: Events.ERROR,
    data: {
      code,
      message
    },
    requestId,
    serverTimestamp: Date.now(),
    version: '1.0'
  });
}

module.exports = {
  Events,
  ErrorCodes,
  createMessage,
  createError
};
