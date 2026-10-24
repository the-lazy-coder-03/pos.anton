# Project configuration

Project JSON files and browser-test configuration live in [`SupportConfigFiles`](../SupportConfigFiles/):

- `package.json` and `package-lock.json`: pinned browser-test dependencies.
- `playwright.config.cjs`: browser-test settings, with paths resolved from the repository root.
- `rls-contract.json`: reviewed PostgreSQL security definitions. Maven packages only this JSON as `config/rls-contract.json` in the application jar. Do not replace it with definitions from a deployed database.

Older deployment helpers still accept `staff-config.json`, but the application
does not import staff files. Staff PIN login is retired; create named admin
accounts through the super-admin interface.

Run browser tooling from the repository root:

```sh
npm --prefix SupportConfigFiles ci
npm --prefix SupportConfigFiles exec -- playwright install chromium
npm --prefix SupportConfigFiles run test:browser
```

Browser tests require the packaged application and disposable PostgreSQL credentials described in [DATABASE_SETUP.md](DATABASE_SETUP.md).

Doppler is the canonical store for local and production runtime secrets. Use
`scripts/doppler-render-env.sh` to generate the ignored `SupportConfigFiles/.env`
file before Docker Compose commands; treat that file as a local cache, not as
the source of truth. Maven and the Docker build explicitly select the RLS
contract rather than copying the entire configuration directory into the
application.

Live address search and delivery confirmation maps use `GOOGLE_MAPS_API_KEY`
from configuration on registration, profile, checkout, and delivery confirmation
pages. Address search displays South African predictions after two typed
characters through the Maps JavaScript Places autocomplete data API. Use a
browser key restricted by HTTP referrer and limited to Maps JavaScript API,
Places API (New), and Maps Static API. Leave it blank in automated tests;
browser coverage supplies a mock Places library without transmitting a key or
calling Google.
