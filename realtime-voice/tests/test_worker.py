from realtime_voice.worker import played_prefix


def test_played_prefix_counts_unicode_codepoints():
    assert played_prefix("你好😀世界", "你好😀") == 3


def test_played_prefix_preserves_model_leading_whitespace():
    assert played_prefix("  你好", "你好") == 4


def test_played_prefix_fails_closed_when_forwarded_text_differs():
    assert played_prefix("你好世界", "你好，世界") == 0
