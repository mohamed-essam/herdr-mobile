// Asserts, through the fake companion's GET /received, that the app caused the call
// CALL with exactly ARGS (a JSON array), e.g. CALL=RenamePane ARGS=["w1:p3","build"].
// With COUNT, it must have caused it exactly that many times.
const res = http.get(CONTROL_URL + '/received')
if (!res.ok) throw new Error('/received -> ' + res.status + ' ' + res.body)
const want = JSON.stringify(JSON.parse(ARGS))
const n = JSON.parse(res.body).filter(c => c.call === CALL && JSON.stringify(c.args) === want).length
if (typeof COUNT !== 'undefined' ? n !== Number(COUNT) : n === 0) {
  throw new Error(n + ' x ' + CALL + ' ' + want + ' in ' + res.body)
}
