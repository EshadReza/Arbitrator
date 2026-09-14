# arbitrator-web

This directory is a standalone frontend toolchain experiment. It proves that the selected React stack compiles together, but it is **not** the Arbitrator instructor console or participant client.

The running product currently uses:

- JavaFX in `../arbitrator-client` for participants
- The static browser console in `../arbitrator-server/src/main/resources/static/admin` for instructors

`arbitrator-web` is not included in the root Maven reactor, is not served by Spring Boot, has no API/authentication integration, and contains no production dashboard routes or state model. `src/App.tsx` is deliberately a component/toolchain smoke test.

## Stack

- React 19
- TypeScript 6
- Vite 8
- Tailwind CSS 4
- Base UI and shadcn-style components
- Motion
- Oxlint

## Run

```bash
npm install
npm run dev
```

## Verify

```bash
npm run build
npm run lint
```

## Before product development

Decide whether this project will replace the existing static instructor console or serve a different purpose. A real integration will need, at minimum:

- A documented routing and page model
- REST and STOMP clients based on the contracts in `arbitrator-common`
- Admin authentication and loopback behavior compatible with the Spring Boot security model
- Contest, problem, monitoring, standings, announcement, material, and clarification screens
- Error/loading/reconnect states
- Production build integration and deployment ownership
- Browser and security tests

Until that decision and integration work happen, changes here do not alter the Arbitrator application's behavior.
