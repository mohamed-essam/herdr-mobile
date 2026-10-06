// Asserts, through the fake companion's GET /received, that the app caused the call
// CALL with exactly ARGS (a JSON array), e.g. CALL=RenamePane ARGS=["w1:p3","build"].
const res = http.get(CONTROL_URL + '/received')
if (!res.ok) throw new Error('/received -> ' + res.status + ' ' + res.body)
const want = JSON.stringify(JSON.parse(ARGS))
if (!JSON.parse(res.body).some(c => c.call === CALL && JSON.stringify(c.args) === want)) {
  throw new Error('no ' + CALL + ' ' + want + ' in ' + res.body)
}
