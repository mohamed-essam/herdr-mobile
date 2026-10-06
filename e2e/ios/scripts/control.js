// Calls the fake companion's control API (companion/cmd/fake-companion).
// Env: ENDPOINT (e.g. /scenario), BODY (optional JSON), or NAME for /scenario's {"name": NAME}
// (Maestro can't interpolate a variable inside a quoted JSON body).
// CONTROL_URL comes from scripts/e2e-ios.sh.
const body = typeof NAME !== 'undefined' ? JSON.stringify({ name: NAME }) : typeof BODY === 'undefined' ? '' : BODY
const res = http.post(CONTROL_URL + ENDPOINT, { body: body })
if (!res.ok) throw new Error(ENDPOINT + ' -> ' + res.status + ' ' + res.body)
