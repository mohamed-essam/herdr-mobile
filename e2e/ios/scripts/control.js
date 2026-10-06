// Calls the fake companion's control API (companion/cmd/fake-companion).
// Env: ENDPOINT (e.g. /scenario), BODY (optional JSON). CONTROL_URL comes from scripts/e2e-ios.sh.
const res = http.post(CONTROL_URL + ENDPOINT, { body: typeof BODY === 'undefined' ? '' : BODY })
if (!res.ok) throw new Error(ENDPOINT + ' -> ' + res.status + ' ' + res.body)
