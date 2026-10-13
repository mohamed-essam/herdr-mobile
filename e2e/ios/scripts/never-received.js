// Asserts the app causes no CALL (any args) for WAIT_MS (default 3000), polling GET /received.
const until = Date.now() + Number(typeof WAIT_MS === 'undefined' ? 3000 : WAIT_MS)
do {
  const res = http.get(CONTROL_URL + '/received')
  if (!res.ok) throw new Error('/received -> ' + res.status + ' ' + res.body)
  const hits = JSON.parse(res.body).filter(c => c.call === CALL)
  if (hits.length > 0) throw new Error('unexpected ' + JSON.stringify(hits))
} while (Date.now() < until)
