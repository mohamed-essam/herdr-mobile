// Sends chat w1:p1 (scenario "chat") an assistant message at SEQ whose Markdown holds a
// remote image inline and as a block, both pointing at the fake companion's GET /fetch/*
// recorder: the app must render neither and fetch neither.
const frame = {
  t: 'chat_event', paneId: 'w1:p1', epoch: 1, seq: Number(SEQ),
  event: {
    type: 'assistant_text', uuid: 'probe', ts: 1791279240000,
    text: 'Probe ![inline](' + CONTROL_URL + '/fetch/inline.png) sent.\n\n![block](' + CONTROL_URL + '/fetch/block.png)',
  },
}
const res = http.post(CONTROL_URL + '/emit', { body: JSON.stringify(frame) })
if (!res.ok) throw new Error('/emit -> ' + res.status + ' ' + res.body)
