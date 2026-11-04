# MELODY JAM — COMPLETE MASTER IMPLEMENTATION PROMPT

## 1. PROJECT OVERVIEW

You are working on an existing Android music streaming application named **Melody**, developed primarily using Kotlin.

Your task is to introduce a new feature called **Melody Jam**, inspired by Spotify Jam.

Melody Jam will allow multiple users on different Android devices to listen to the same song together with near-real-time synchronized playback.

Users should be able to create a Jam room, invite friends, join existing rooms, listen together, manage a collaborative music queue and interact with other participants.

### CRITICAL INSTRUCTIONS

This is an existing application.

- Do NOT create a new Android application.
- Do NOT rebuild Melody from scratch.
- Do NOT replace the existing music player.
- Do NOT remove existing features.
- Do NOT unnecessarily modify unrelated screens.
- Do NOT introduce mandatory login or registration.
- Extend the existing architecture and reuse existing components.
- Preserve the current Melody branding and design system.

Your first responsibility is to understand the existing project before making changes.

---

# 2. INITIAL CODEBASE ANALYSIS

Before implementation, inspect the entire repository.

Understand:

1. Existing Android project structure.
2. Kotlin and Gradle versions.
3. Current architecture and design patterns.
4. Existing music playback engine.
5. How songs are fetched and streamed.
6. How track IDs and metadata are managed.
7. Existing queue and playlist implementation.
8. Existing backend and APIs, if any.
9. Existing database and data models.
10. Existing navigation system.
11. Existing UI components and design system.
12. Existing caching and background playback implementation.
13. Existing authentication, if any.
14. Existing testing infrastructure.

Identify the safest integration points for Melody Jam.

Create:

`docs/jam/PROJECT_ANALYSIS.md`

Include:

- Existing architecture.
- Music playback implementation.
- Track identification system.
- Music source and streaming limitations.
- Existing backend capabilities.
- Required backend changes.
- Existing queue architecture.
- UI integration points.
- Dependencies.
- Risks and compatibility considerations.

Do not assume any technology or architecture before inspecting the repository.

If a backend already exists, extend it rather than introducing an unnecessary second backend.

---

# 3. MELODY JAM — CORE FEATURES

## 3.1 Create Jam

Users should be able to:

- Create a new Jam room.
- Automatically become the host.
- Generate a unique room code.
- Generate a shareable invitation link.
- Set the room visibility to private.
- Invite friends through Android sharing options.
- Start listening immediately.

## 3.2 Join Jam

Users should be able to:

- Join using a room code.
- Join using an invitation link.
- View room information before joining.
- See the current host.
- See the currently playing song.
- Automatically synchronize with the active playback.
- View connected participants.

If the room no longer exists or is full, display an appropriate message.

## 3.3 Synchronized Music Playback

This is the most important feature.

All connected devices should synchronize:

- Play.
- Pause.
- Resume.
- Seek.
- Skip.
- Next track.
- Current track changes.
- Playback position.

The host should be the authoritative playback controller.

When a host changes the song, all connected guests should resolve and play the same track.

When a guest joins an already-running Jam, their player should synchronize with the host's current track and playback position.

Implement:

- Server-authoritative playback state.
- Timestamp-based playback events.
- Network latency compensation.
- Periodic synchronization.
- Playback drift detection.
- Small corrections for minor timing differences.
- Seek correction for significant drift.
- Reconnection and state recovery.

Do not continuously seek every device unnecessarily.

### Audio Playback Architecture

Do not send audio through the Jam server.

Each device must play music through its own existing Melody playback engine and authorized streaming source.

The Jam server should only distribute:

- Track identifiers.
- Playback commands.
- Playback position.
- Timestamps.
- Queue updates.
- Participant information.

Implement track resolution so that each device can access the same song.

If a guest cannot access a particular track, show an appropriate unavailable-track state.

Never silently substitute a different track.

Respect streaming source permissions, DRM and licensing restrictions.

---

# 4. GUEST IDENTITY — NO LOGIN OR REGISTRATION

Melody currently has no login or registration functionality.

Do not force users to create accounts just to use Melody Jam.

Implement a lightweight guest authentication system.

## 4.1 Guest Identity

When Melody is first launched:

- Generate a unique installation identifier.
- Assign a default display name, such as Melody User.
- Allow users to customize their display name.
- Create a guest session with the backend when they first use Jam.

## 4.2 Secure Guest Sessions

The backend should issue a secure guest access token.

Requirements:

- Store credentials using Android Keystore-backed secure storage.
- Never expose tokens to other participants.
- Validate every request on the server.
- Implement token expiration.
- Support secure session renewal.
- Support session invalidation.
- Handle app reinstallations and cleared data.
- Prevent installation IDs from being treated as authentication credentials.

Do not use a client-generated ID as proof of identity.

## 4.3 Guest Permissions

Every guest should have a verified identity associated with their room membership.

Host:
- Full playback control.
- Full queue management.
- Remove participants.
- End Jam.
- Manage room settings.

Guest:
- Join and leave rooms.
- View current playback.
- Add songs to the queue.
- View other participants.
- Send reactions.
- Request playback actions if permitted.

## 4.4 Future Compatibility

Design the identity system so that optional account registration can be added later.

Do not implement account registration now.

Allow future migration from guest identity to a permanent user account without redesigning the Jam system.

---

# 5. COLLABORATIVE QUEUE

Each Jam room should have a shared queue.

Users should be able to:

- Add songs.
- View upcoming songs.
- See who added each song.
- See the current playing song.
- See the queue in real time.
- Remove their own songs when permitted.
- Allow the host to remove any song.
- Handle queue changes without restarting playback.

All queue modifications must be synchronized across participants.

Use the existing Melody track models wherever possible.

Prevent duplicate or invalid queue entries according to existing application behavior.

---

# 6. HOST AND GUEST MANAGEMENT

## Host Features

The host can:

- Start and end Jam.
- Play and pause.
- Seek.
- Skip tracks.
- Change tracks.
- Manage the shared queue.
- Remove participants.
- Configure guest permissions.
- Transfer host privileges if implemented safely.

## Guest Features

Guests can:

- Join and leave.
- Listen to synchronized playback.
- Add songs.
- View the queue.
- View participants.
- Send reactions.
- Request song skips if allowed.

## Host Disconnection

Handle host disconnection gracefully.

Implement a configurable reconnection grace period.

If the host does not return, support host transfer to an eligible participant or terminate the room safely.

Never leave a room in an invalid state.

---

# 7. PARTICIPANTS AND SOCIAL FEATURES

Display:

- Participant avatars.
- Usernames.
- Host indicator.
- Total participant count.
- Joining notifications.
- Leaving notifications.
- Current room status.

Add lightweight live reactions.

Users should be able to send emoji reactions that appear temporarily on other participants' screens.

Keep reactions lightweight and avoid interrupting playback.

Do not implement unnecessary social networking functionality.

---

# 8. BACKEND AND REAL-TIME ARCHITECTURE

Inspect the existing backend before selecting technologies.

If there is no backend, create a minimal backend appropriate for the existing Android application.

Preferred options:

- Node.js.
- TypeScript if compatible with the existing backend.
- Socket.IO or native WebSockets.
- Existing database where possible.
- Redis only if genuinely necessary for scaling or distributed room state.

Do not introduce unnecessary infrastructure.

## 8.1 Backend Responsibilities

The backend should manage:

- Guest authentication.
- Room creation.
- Room joining.
- Room expiration.
- Participant membership.
- Host permissions.
- Playback state.
- Playback event broadcasting.
- Shared queue.
- Reactions.
- Reconnection.
- Room cleanup.

## 8.2 Suggested Data Models

Guest:
- guestId
- displayName
- createdAt
- session information

JamRoom:
- roomId
- roomCode
- hostId
- visibility
- createdAt
- expiresAt
- currentPlaybackState

JamParticipant:
- participantId
- guestId
- roomId
- role
- connectionStatus
- joinedAt

JamPlaybackState:
- trackId
- position
- isPlaying
- serverTimestamp
- stateVersion

JamQueueItem:
- queueItemId
- trackId
- addedBy
- addedAt
- position

Adapt these models to the existing architecture rather than duplicating existing data structures.

## 8.3 WebSocket Events

Implement a clearly defined event protocol.

Suggested client-to-server events:

- CREATE_JAM
- JOIN_JAM
- LEAVE_JAM
- PLAY
- PAUSE
- SEEK
- SKIP
- ADD_TO_QUEUE
- REMOVE_FROM_QUEUE
- SEND_REACTION
- REQUEST_SYNC

Suggested server-to-client events:

- JAM_CREATED
- JAM_JOINED
- JAM_STATE
- PLAYBACK_UPDATED
- TRACK_CHANGED
- QUEUE_UPDATED
- PARTICIPANT_JOINED
- PARTICIPANT_LEFT
- REACTION_RECEIVED
- HOST_CHANGED
- JAM_ENDED
- SYNC_RESPONSE
- ERROR

All events must be validated server-side.

Include event versioning, request IDs and appropriate acknowledgements where necessary.

Reject unauthorized commands.

Never trust a client-supplied host ID or role.

---

# 9. ANDROID IMPLEMENTATION

Use the existing Android implementation and architecture.

Required technologies should be selected after inspecting the codebase.

Potential technologies:

- Kotlin.
- Existing Media3/ExoPlayer playback implementation.
- Existing Android UI framework.
- Existing navigation.
- Coroutines and Flow where appropriate.
- Retrofit or existing networking layer.
- Socket.IO or WebSocket client.
- Android Keystore-backed secure session storage.

## Architecture

Keep responsibilities separated.

Suggested logical modules:

- JamRepository
- JamApiService
- JamSocketManager
- JamSessionManager
- JamPlaybackController
- JamSyncManager
- JamQueueManager
- JamGuestSessionManager

These are suggested responsibilities, not mandatory new packages.

Reuse existing services and repositories whenever possible.

Do not create duplicate playback engines.

## Playback Integration

Connect Jam events to the existing player.

Ensure:

- Existing playback controls continue working outside Jam.
- Jam playback respects host permissions.
- Guests cannot accidentally control the shared session.
- Local playback state does not conflict with remote playback state.
- Background playback continues working.
- Android lifecycle changes do not destroy the active Jam.
- Notifications and media controls remain consistent.
- Leaving a Jam restores normal standalone playback behavior.

---

# 10. UI / UX INTEGRATION

Integrate Jam into Melody's existing UI.

Maintain the existing dark red and black visual identity.

Do not redesign the entire application.

Use existing components, typography, spacing, icons and navigation conventions.

Create these interfaces:

1. Jam entry point.
2. Create Jam screen.
3. Join Jam screen.
4. Jam room screen.
5. Participant list.
6. Shared queue.
7. Invitation/share interface.
8. Connection status.
9. Room settings.
10. Leave/End Jam confirmation.

## Jam Entry Point

Provide two clear actions:

- Start a Jam.
- Join a Jam.

## Jam Room Screen

Display:

- Current album artwork.
- Song title.
- Artist name.
- Playback progress.
- Host information.
- Participants.
- Shared queue.
- Invitation action.
- Connection status.

## Visual Design

The interface should be:

- Minimal.
- Modern.
- Premium.
- Consistent with Melody.
- Easy to understand.
- Suitable for dark environments.

Avoid excessive animations, bright colors and unnecessary visual clutter.

Use subtle transitions and feedback.

---

# 11. NETWORK RELIABILITY AND SECURITY

Implement:

- WebSocket reconnection.
- Network availability monitoring.
- Heartbeats.
- Room-state recovery.
- Duplicate event protection.
- Event ordering.
- Invalid event rejection.
- Rate limiting.
- Room expiration.
- Session expiration.
- Secure guest authentication.
- Server-side permission verification.
- Safe handling of stale playback events.

When a participant disconnects temporarily:

1. Preserve their room membership during the grace period.
2. Attempt reconnection.
3. Reauthenticate their session.
4. Request the latest room state.
5. Synchronize playback again.

Do not assume that a disconnected client is permanently gone.

---

# 12. PERFORMANCE REQUIREMENTS

Melody Jam must not negatively affect normal music playback.

Optimize:

- Battery consumption.
- Network usage.
- WebSocket event frequency.
- Memory usage.
- Background execution.
- Playback synchronization.
- Queue updates.

Do not continuously transmit playback position at very high frequency.

Use periodic synchronization and event-driven updates.

The application must remain responsive even when the Jam connection is unstable.

---

# 13. DEVELOPMENT PHASES

Create these 10 separate phase documents.

All phases must be saved under:

`docs/jam/phases/`

## PHASE 01 — Foundation and Codebase Analysis
File: `PHASE_01_FOUNDATION.md`

## PHASE 02 — Backend and Real-Time Infrastructure
File: `PHASE_02_BACKEND.md`

## PHASE 03 — Jam Room Management
File: `PHASE_03_ROOM_MANAGEMENT.md`

## PHASE 04 — Real-Time Playback Events
File: `PHASE_04_REALTIME_PLAYBACK.md`

## PHASE 05 — Playback Synchronization Engine
File: `PHASE_05_SYNCHRONIZATION.md`

## PHASE 06 — Collaborative Queue
File: `PHASE_06_SHARED_QUEUE.md`

## PHASE 07 — Participants and Permissions
File: `PHASE_07_PARTICIPANTS_PERMISSIONS.md`

## PHASE 08 — Android UI Integration
File: `PHASE_08_UI_INTEGRATION.md`

## PHASE 09 — Reliability and Security
File: `PHASE_09_RELIABILITY_SECURITY.md`

## PHASE 10 — Testing and Release
File: `PHASE_10_TESTING_RELEASE.md`

---

# 14. TESTING REQUIREMENTS

Test using at least two physical Android devices.

Important scenarios:

1. Host creates a Jam.
2. Guest joins using room code.
3. Guest joins through invitation link.
4. Host plays a song.
5. Guest starts at the correct playback position.
6. Host pauses playback.
7. Guest pauses and resumes correctly according to host state.
8. Host seeks forward and backward.
9. Host skips a song.
10. Guest adds a song to the queue.
11. All participants receive the queue update.
12. Guest disconnects and reconnects.
13. Host disconnects temporarily.
14. Host ends the room.
15. A guest attempts unauthorized playback control.
16. A guest attempts to access an invalid room.
17. A track is unavailable on a guest device.
18. Network latency increases.
19. Application goes into the background.
20. Normal music playback works after leaving Jam.

Test existing Melody features to ensure no regressions.

Do not claim synchronization works based only on successful WebSocket messages. Verify actual playback on multiple devices.

---

# 15. DOCUMENTATION AND PROGRESS TRACKING

Create:

`docs/jam/MASTER_JAM_PROMPT.md`
`docs/jam/PROJECT_ANALYSIS.md`
`docs/jam/IMPLEMENTATION_PROGRESS.md`

And the 10 phase documents specified above.

The progress document must contain:

- Current phase.
- Completed tasks.
- Files changed.
- Dependencies added.
- Backend changes.
- Android changes.
- Tests executed.
- Known bugs.
- Pending tasks.
- Next phase.

Update documentation after every phase.

---

# 16. EXECUTION RULES FOR ANTIGRAVITY

Follow this workflow strictly:

1. Inspect the project first.
2. Complete Phase 01.
3. Generate all documentation.
4. Present the architecture and important integration decisions.
5. Implement phases sequentially.
6. Do not skip phases.
7. Build and test after every major integration.
8. Fix errors before proceeding.
9. Maintain compatibility with existing Melody features.
10. Update IMPLEMENTATION_PROGRESS.md continuously.
