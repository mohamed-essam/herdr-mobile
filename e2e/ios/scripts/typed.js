// Waits up to WAIT_MS (default 5000) until what the app typed into terminal TARGET (every
// TermInput the fake's attach program reported, joined) ends with WANT, a JSON string
// such as "\u001b[A". With EXACT, it must equal WANT.
const want = JSON.parse(WANT)
const exact = typeof EXACT !== 'undefined'
const until = Date.now() + Number(typeof WAIT_MS === 'undefined' ? 5000 : WAIT_MS)
let typed = ''
let done = false
while (!done && Date.now() < until) {
  const res = http.get(CONTROL_URL + '/received')
  if (!res.ok) throw new Error('/received -> ' + res.status + ' ' + res.body)
  typed = JSON.parse(res.body).filter(c => c.call === 'TermInput' && c.args[0] === TARGET).map(c => c.args[1]).join('')
  done = exact ? typed === want : typed.endsWith(want)
}
if (!done) throw new Error(TARGET + ' typed ' + JSON.stringify(typed) + ', want ' + (exact ? '' : '…') + WANT)
