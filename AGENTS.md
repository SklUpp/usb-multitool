# Decision escalation

Before implementing a change that commits the project to a direction, ask the user:

- choosing a new dependency, library, or native component instead of a custom implementation;
- replacing or removing an existing subsystem;
- changing architecture, public API, protocol, or on-disk format;
- creating signing keys/secrets, tagging, publishing, or any irreversible action;
- breaking backward compatibility or migrating user data.

Present 2-3 concrete options with tradeoffs, cost, risk, and a recommendation. Wait for the
choice before coding.

Do not implement first and justify afterwards. Exceptions: a confirmed critical defect fix
restoring intended behavior; the user already made the decision; a trivial local edit with
no interface, dependency, or compatibility impact.
