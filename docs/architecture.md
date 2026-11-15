# Architecture

The application separates transport, game rules, domain state, automated players, and JavaFX presentation.

## Backend

- `backend.network` owns the authoritative WebSocket server and room/session lifecycle.
- `backend.network.protocol` defines the JSON messages exchanged by clients and the server.
- `backend.model` represents players, markets, positions, scoring tiles, territories, and round state.
- `backend.logic` validates actions and calculates income and scoring.
- `backend.ai` contains automated client strategies and their read-only game-state views.

## Frontend

- `frontend.client` manages the WebSocket connection and incoming/outgoing messages.
- `frontend.ui.controllers` coordinates FXML screens with protocol and game state.
- `frontend.ui.components` contains reusable tile-placement and scoring-board controls.
- `frontend.ui.audio` encapsulates optional music and sound effects used by the private academic build.

## Runtime flow

1. A JavaFX client connects to the server and joins or creates a room.
2. The server validates room and player state and broadcasts JSON protocol messages.
3. Client handlers update controller state on the JavaFX application thread.
4. The authoritative server advances phases and validates pricing, buying, placement, income, and scoring actions.
5. Automated players use the same client protocol as human players.

## Historical design artifact

The repository also includes [`architecture.puml`](architecture.puml), an early PlantUML design artifact from the first project milestone. It documents the initial domain model and should be read as design history rather than an exact representation of every final class.
