# Contribution summary — Duru Sert

This document summarizes work attributable to Duru Sert in the original private GitLab history. It is not intended to imply sole authorship of the overall project.

## Frontend foundation

- Created the initial JavaFX GUI structure and navigation between the main menu, lobby, and game views.
- Added and maintained UML artifacts during the early architecture phase.
- Integrated client-side protocol messages with the JavaFX controller layer.

## Multiplayer state and protocol integration

- Implemented a frontend message-handler and dispatch structure.
- Synchronized Build, Discard, Reveal, Buy, Scoring, and Game Over state with the visible UI.
- Fixed turn mismatches, invalid actions after disconnects, ghost-client behaviour, and phase-transition inconsistencies.
- Added and stabilized five-player protocol and board behaviour.

## Interaction and communication features

- Added private chat through `/w`, reply through `/r`, and supporting help commands.
- Added unread-message notifications and an active-player indicator.
- Improved market labels, sidebar behaviour, and player-status feedback.
- Added tile-placement preview, Confirm/Undo behaviour, and rotation interactions.

## Reliability and finalization

- Fixed Game Over race conditions and missing standings.
- Corrected scoring-token animation and round-marker placement.
- Ensured the correct number of tiles is drawn and validated continuation after players leave.
- Participated in final integration, testing, UI polish, and defect resolution.

## Collaboration note

The game architecture, backend, AI, user interface, documentation, and final integration were collaborative efforts across the project team. The original private GitLab repository preserves the complete multi-author history.
