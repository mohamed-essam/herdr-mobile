from herdr_hermes.history import MAX_EVENTS, from_messages

NOW = 1_700_000_000_000


def test_roles_and_tool_pair():
    msgs = [
        {"role": "system", "content": "sys"},
        {"role": "user", "content": "hi"},
        {
            "role": "assistant",
            "content": "running",
            "tool_calls": [
                {"id": "c1", "type": "function",
                 "function": {"name": "terminal", "arguments": '{"command": "ls"}'}},
                {"id": "c2", "type": "function",
                 "function": {"name": "read", "arguments": {"path": "/x"}}},
            ],
        },
        {"role": "tool", "tool_call_id": "c1", "content": "file.txt"},
        {"role": "assistant", "content": "done"},
    ]
    evs = from_messages(msgs, NOW)
    assert [e["type"] for e in evs] == [
        "user_text", "assistant_text", "tool_use", "tool_use", "tool_result", "assistant_text",
    ]
    assert evs[0]["uuid"] == "h-1" and evs[0]["text"] == "hi"
    assert evs[1]["uuid"] == "h-2" and evs[1]["text"] == "running"
    assert evs[2]["toolUseId"] == "c1" and evs[2]["summary"] == "ls"
    assert evs[3]["toolUseId"] == "c2" and evs[3]["summary"] == "/x"
    assert evs[4]["toolUseId"] == "c1" and evs[4]["preview"] == "file.txt"
    assert evs[4]["isError"] is False
    assert evs[5]["uuid"] == "h-4"
    assert all(e["ts"] == NOW for e in evs)


def test_ids_stable_across_calls():
    msgs = [{"role": "user", "content": "a"}, {"role": "assistant", "content": "b"}]
    assert from_messages(msgs, NOW) == from_messages(msgs, NOW)


def test_multimodal_content_joined_images_ignored():
    msgs = [{"role": "user", "content": [
        {"type": "text", "text": "look"},
        {"type": "image_url", "image_url": {"url": "data:..."}},
        {"type": "text", "text": "here"},
    ]}]
    evs = from_messages(msgs, NOW)
    assert len(evs) == 1 and evs[0]["text"] == "look\nhere"


def test_image_only_user_skipped():
    msgs = [{"role": "user", "content": [{"type": "image_url", "image_url": {}}]}]
    assert from_messages(msgs, NOW) == []


def test_arguments_malformed_missing_and_non_object():
    calls = [
        {"id": "a", "function": {"name": "t", "arguments": "{not json"}},
        {"id": "b", "function": {"name": "t"}},
        {"id": "c", "function": {"name": "t", "arguments": "[1]"}},
    ]
    evs = from_messages([{"role": "assistant", "content": None, "tool_calls": calls}], NOW)
    assert [e["toolUseId"] for e in evs] == ["a", "b", "c"]
    assert all(e["summary"] == "" for e in evs)


def test_content_none_emits_no_text():
    evs = from_messages([{"role": "assistant", "content": None}], NOW)
    assert evs == []


def test_tool_call_without_id_gets_stable_fallback():
    msgs = [{"role": "assistant", "content": "", "tool_calls": [{"function": {"name": "t"}}]}]
    evs = from_messages(msgs, NOW)
    assert evs[0]["toolUseId"] == "h-0-0"


def test_orphan_tool_result_still_emitted():
    evs = from_messages([{"role": "tool", "tool_call_id": "zz", "content": "x"}], NOW)
    assert evs[0]["type"] == "tool_result" and evs[0]["toolUseId"] == "zz"


def test_tool_error_envelope_marks_error():
    msgs = [
        {"role": "tool", "tool_call_id": "e", "content": '{"error": "boom"}'},
        {"role": "tool", "tool_call_id": "ok", "content": '{"output": "fine"}'},
        {"role": "tool", "tool_call_id": "n", "content": "error: not json"},
    ]
    evs = from_messages(msgs, NOW)
    assert [e["isError"] for e in evs] == [True, False, False]


def test_message_timestamp_seconds_used():
    msgs = [{"role": "user", "content": "hi", "timestamp": 1700000123.5}]
    assert from_messages(msgs, NOW)[0]["ts"] == 1700000123500


def test_odd_input_never_raises():
    msgs = [
        None, 5, "str", [], {"role": 3}, {"content": "no role"},
        {"role": "assistant", "content": 7, "tool_calls": "bad"},
        {"role": "assistant", "tool_calls": [None, 3, {"function": None, "id": "q"}]},
        {"role": "tool", "content": None},
        {"role": "user", "content": [None, "s", {"type": "text", "text": None}]},
        {"role": "user", "content": "ok"},
    ]
    evs = from_messages(msgs, NOW)
    assert any(e["type"] == "user_text" and e["text"] == "ok" for e in evs)
    assert from_messages(None, NOW) == []


def test_newest_5000_kept():
    msgs = [{"role": "user", "content": f"m{i}"} for i in range(6000)]
    evs = from_messages(msgs, NOW)
    assert MAX_EVENTS == 5000 and len(evs) == 5000
    assert evs[0]["text"] == "m1000" and evs[-1]["text"] == "m5999"
