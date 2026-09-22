"""mod_audio_fork 候选探针（纯标准库）。

该脚本只自动确认可观测事实：上行 PCM、WebSocket 写入、ESL stop 响应和
连接关闭。向 WebSocket 写入 PCM 不等于软电话已经播放；ESL stop 关闭整个
fork，也不等于插话时清空下行播放队列。后两项必须由目标模块的真实通话验收
和播放确认事件证明，脚本不会把它们误报为通过。
"""
import asyncio
import base64
import hashlib
import math
import os
import struct
import time

WS_PORT = int(os.environ.get("WS_PORT", "8082"))
ESL_HOST = os.environ.get("ESL_HOST", "127.0.0.1")
ESL_PORT = int(os.environ.get("ESL_PORT", "8021"))
ESL_PASSWORD = os.environ.get("ESL_PASSWORD", "ClueCon")
SOFTPHONE_EXT = os.environ.get("SOFTPHONE_EXT", "1000")
DOMAIN = os.environ.get("DOMAIN", "127.0.0.1")
# FreeSWITCH 容器通过 host.docker.internal 访问宿主机上的探针
WS_HOST_FOR_FS = os.environ.get("WS_HOST_FOR_FS", "host.docker.internal")

SAMPLE_RATE = 16000
FRAME_DURATION_MS = 20
FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_DURATION_MS // 1000

received_bytes = 0
received_frames = 0
sent_frames = 0
ws_connected = False
events = []


def generate_beep_pcm(duration_sec=1.0, freq=440, sample_rate=16000):
    samples = []
    for i in range(int(sample_rate * duration_sec)):
        sample = int(32767 * 0.3 * math.sin(2 * math.pi * freq * i / sample_rate))
        samples.append(struct.pack('<h', sample))
    return b''.join(samples)


async def read_esl_message(reader):
    """Read one complete ESL frame without assuming a single socket read is enough."""
    headers = {}
    while True:
        line = await reader.readline()
        if not line:
            raise ConnectionError("ESL connection ended while reading headers")
        text = line.decode(errors="replace").rstrip("\r\n")
        if not text:
            break
        if ":" in text:
            key, value = text.split(":", 1)
            headers[key.strip()] = value.strip()
    length = int(headers.get("Content-Length", "0"))
    body = await reader.readexactly(length) if length else b""
    return headers, body.decode(errors="replace")


async def esl_connect():
    """连接并认证 ESL，等待 compose 中的 FreeSWITCH 就绪。"""
    last_error = None
    for _ in range(30):
        try:
            reader, writer = await asyncio.open_connection(ESL_HOST, ESL_PORT)
            break
        except OSError as exc:
            last_error = exc
            await asyncio.sleep(1)
    else:
        raise ConnectionError(f"ESL {ESL_HOST}:{ESL_PORT} 未就绪: {last_error}")

    greeting, _ = await read_esl_message(reader)
    if greeting.get("Content-Type") != "auth/request":
        raise ConnectionError(f"ESL 未请求认证: {greeting}")
    writer.write(f"auth {ESL_PASSWORD}\n\n".encode())
    await writer.drain()
    auth_headers, auth_body = await read_esl_message(reader)
    auth_result = auth_headers.get("Reply-Text", auth_body)
    if "+OK" not in auth_result:
        writer.close()
        await writer.wait_closed()
        raise PermissionError(f"ESL 认证失败: {auth_result}")
    print("[ESL] 已连接并认证")
    return reader, writer


async def esl_api(reader, writer, command):
    """执行 ESL api 命令"""
    writer.write(f"api {command}\n\n".encode())
    await writer.drain()
    headers, body = await read_esl_message(reader)
    return body or headers.get("Reply-Text", "")


async def handle_ws_client(reader, writer):
    """处理 WebSocket 连接"""
    global received_bytes, received_frames, sent_frames, ws_connected

    # HTTP 升级握手
    request_data = await reader.read(4096)
    request_text = request_data.decode(errors='replace')

    if "Upgrade" not in request_text and "upgrade" not in request_text.lower():
        writer.close()
        await writer.wait_closed()
        return

    # 提取 Sec-WebSocket-Key
    key = None
    for line in request_text.split('\r\n'):
        if line.lower().startswith('sec-websocket-key:'):
            key = line.split(':', 1)[1].strip()
            break

    if not key:
        writer.close()
        await writer.wait_closed()
        return

    # 计算 Sec-WebSocket-Accept
    accept = base64.b64encode(
        hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    ).decode()

    offered_protocols = []
    for line in request_text.split('\r\n'):
        if line.lower().startswith('sec-websocket-protocol:'):
            offered_protocols = [value.strip() for value in line.split(':', 1)[1].split(',')]
            break
    selected_protocol = next(
        (value for value in ("audio.drachtio.org", "audiostream.drachtio.org")
         if value in offered_protocols),
        None,
    )

    protocol_header = (
        f"Sec-WebSocket-Protocol: {selected_protocol}\r\n" if selected_protocol else ""
    )
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n"
        f"{protocol_header}\r\n"
    )
    writer.write(response.encode())
    await writer.drain()

    ws_connected = True
    print("[WebSocket] FreeSWITCH 已连接")
    events.append({"time": time.time(), "type": "ws_connected"})

    beep_pcm = generate_beep_pcm(1.0)
    beep_sent = False
    start_time = time.time()

    try:
        while True:
            header = await reader.readexactly(2)
            b1, b2 = header[0], header[1]
            opcode = b1 & 0x0F
            masked = (b2 & 0x80) != 0
            payload_len = b2 & 0x7F

            if payload_len == 126:
                ext = await reader.readexactly(2)
                payload_len = struct.unpack('>H', ext)[0]
            elif payload_len == 127:
                ext = await reader.readexactly(8)
                payload_len = struct.unpack('>Q', ext)[0]

            if masked:
                mask = await reader.readexactly(4)

            payload = await reader.readexactly(payload_len) if payload_len > 0 else b''

            if masked and payload:
                payload = bytes(payload[i] ^ mask[i % 4] for i in range(len(payload)))

            if opcode == 0x8:  # Close
                print("[验证4] 收到 WebSocket Close 帧")
                events.append({"time": time.time(), "type": "ws_close_frame"})
                # 发回 close 帧
                writer.write(struct.pack('>BB', 0x88, 0))
                await writer.drain()
                break

            elif opcode == 0x1:  # Text
                text = payload.decode(errors='replace')
                print(f"[验证4] 收到文本帧: {text[:200]}")
                events.append({"time": time.time(), "type": "text_frame", "data": text[:200]})

            elif opcode == 0x2 or opcode == 0x0:  # Binary/Continuation
                received_bytes += len(payload)
                received_frames += 1

                if received_frames == 1:
                    print(f"[验证1] 收到第一帧音频: {len(payload)} bytes")
                    events.append({"time": time.time(), "type": "first_audio", "bytes": len(payload)})

                if received_frames % 50 == 0:
                    elapsed = time.time() - start_time
                    print(f"[验证1] 已收 {received_frames} 帧, {received_bytes} bytes, {elapsed:.1f}s")

                # 验证项 2: 发送 beep
                if not beep_sent and received_frames >= 10:
                    print("[验证2] 发送 1 秒 440Hz beep...")
                    for i in range(0, len(beep_pcm), FRAME_BYTES):
                        chunk = beep_pcm[i:i+FRAME_BYTES]
                        frame = _make_ws_frame(chunk, binary=True)
                        writer.write(frame)
                        await writer.drain()
                        sent_frames += 1
                        await asyncio.sleep(FRAME_DURATION_MS / 1000)
                    beep_sent = True
                    events.append({"time": time.time(), "type": "beep_sent", "frames": sent_frames})
                    print(f"[验证2] beep 已发送 ({sent_frames} 帧)")

    except asyncio.IncompleteReadError:
        print("[WebSocket] 连接断开")
        events.append({"time": time.time(), "type": "ws_disconnected"})
    except Exception as e:
        print(f"[WebSocket] 异常: {e}")
        events.append({"time": time.time(), "type": "ws_error", "error": str(e)})
    finally:
        ws_connected = False
        elapsed = time.time() - start_time
        print(f"[WebSocket] 结束: 收 {received_frames} 帧 {received_bytes}B, 发 {sent_frames} 帧, {elapsed:.1f}s")
        writer.close()
        try:
            await writer.wait_closed()
        except Exception as exc:
            print(f"[WebSocket] 等待连接关闭失败: {exc}")


def _make_ws_frame(payload, binary=False):
    """构造 WebSocket 帧（不掩码，服务端发送）"""
    opcode = 0x02 if binary else 0x01
    length = len(payload)
    if length < 126:
        header = struct.pack('>BB', 0x80 | opcode, length)
    elif length < 65536:
        header = struct.pack('>BBH', 0x80 | opcode, 126, length)
    else:
        header = struct.pack('>BBQ', 0x80 | opcode, 127, length)
    return header + payload


async def main():
    # 启动 WebSocket 服务端
    server = await asyncio.start_server(handle_ws_client, '0.0.0.0', WS_PORT)
    print(f"[探针] WebSocket 服务端: 0.0.0.0:{WS_PORT}")
    print(f"[探针] ESL: {ESL_HOST}:{ESL_PORT}")
    print(f"[探针] 软电话: {SOFTPHONE_EXT}@{DOMAIN}\n")

    _server_task = asyncio.create_task(server.serve_forever())
    await asyncio.sleep(0.5)

    # 连接 ESL
    print("[探针] 连接 FreeSWITCH ESL...")
    reader, writer = await esl_connect()

    # originate 拨叫：先拨号，接听后通过 ESL 事件触发 audio_fork
    ws_url = f"ws://{WS_HOST_FOR_FS}:{WS_PORT}"
    # 先用简单 originate 拨号，不带 channel variable（避免大括号被 ESL 解析）
    cmd = f"originate user/{SOFTPHONE_EXT}@{DOMAIN} &park"

    print(f"\n[探针] 拨叫软电话 {SOFTPHONE_EXT}@{DOMAIN}...")
    print("[探针] 请在 Linphone 上接听电话！\n")
    result = await esl_api(reader, writer, cmd)
    result_text = result.strip()
    print(f"[ESL] originate 结果: {result_text[:200]}")

    # 从 originate 返回结果提取 UUID（+OK 后面的就是 channel UUID）
    call_uuid = None
    for line in result_text.split('\n'):
        line = line.strip()
        if line.startswith('+OK '):
            call_uuid = line[4:].strip()
            break

    if not call_uuid:
        print("[探针] originate 未返回 UUID，从 show channels 提取")
        await asyncio.sleep(2)
        channels_result = await esl_api(reader, writer, "show channels as json")
        print(f"[ESL] channels: {channels_result[:300]}")
        # JSON 格式里找包含 softphone_ext 的 channel
        import json as json_mod
        try:
            data = json_mod.loads(channels_result.split('\n', 1)[-1] if 'Content-Length' in channels_result else channels_result)
            for ch in data.get('rows', []):
                if SOFTPHONE_EXT in ch.get('name', ''):
                    call_uuid = ch.get('uuid')
                    break
        except Exception as exc:
            print(f"[探针] show channels 响应无法解析: {exc}")

    if not call_uuid:
        print("[探针] 未找到通话 UUID，退出")
        return

    print(f"[探针] 通话 UUID: {call_uuid}")

    # 等待接听
    await asyncio.sleep(3)

    # 启动 audio_fork
    fork_cmd = f"uuid_audio_fork {call_uuid} start {ws_url} mono 16k {{}}"
    print(f"[ESL] 启动 audio_fork: {fork_cmd}")
    fork_result = await esl_api(reader, writer, fork_cmd)
    print(f"[ESL] audio_fork 结果: {fork_result.strip()[:200]}")

    # 等待 WebSocket 连接
    print("[探针] 等待 mod_audio_fork WebSocket 连接...")
    for i in range(30):
        if ws_connected:
            print(f"[探针] WebSocket 已连接 ({i+1}s)")
            break
        await asyncio.sleep(1)
    else:
        print("[探针] 超时：WebSocket 未连接")
        writer.write(f"api uuid_kill {call_uuid}\n\n".encode())
        await writer.drain()
        return

    # 媒体流运行
    print("[探针] 媒体流运行中，请说话并听 beep...")
    await asyncio.sleep(12)

    # 验证项 3: 停止
    print("\n[验证3] 通过 ESL 停止 audio_fork...")
    result = await esl_api(reader, writer, f"uuid_audio_fork {call_uuid} stop")
    print(f"[验证3] stop 结果: {result.strip()[:200]}")
    stop_accepted = "+OK" in result and "-ERR" not in result
    events.append({"time": time.time(), "type": "stop_response", "accepted": stop_accepted})
    await asyncio.sleep(2)

    # 挂断
    print("[探针] 挂断电话...")
    await esl_api(reader, writer, f"uuid_kill {call_uuid}")
    await asyncio.sleep(2)

    # 汇总
    print("\n" + "=" * 60)
    print(" Step 1 媒体验证结果")
    print("=" * 60)

    observations = [
        ("客户音频到 Python", "PASS" if received_frames > 0 else "FAIL",
         f"收到 {received_frames} 帧, {received_bytes} bytes"),
        ("Python 已写出 PCM", "OBSERVED" if sent_frames > 0 else "FAIL",
         f"写出 {sent_frames} 帧；不代表软电话已播放"),
        ("ESL 已接受 stop", "PASS" if stop_accepted else "FAIL",
         result.strip()[:120]),
        ("连接结束可观测", "PASS" if any(
            e["type"] in ("ws_close_frame", "ws_disconnected", "text_frame") for e in events
        ) else "FAIL", "WebSocket close/disconnect/text event"),
        ("软电话真实播放", "UNVERIFIED", "需要人工听音和模块播放确认"),
        ("插话清空播放队列", "UNVERIFIED", "uuid_audio_fork stop 不能替代该项"),
    ]

    for name, status, detail in observations:
        print(f"  {name}: {status} ({detail})")

    print("=" * 60)
    print(" 结论: 此探针不能证明四项媒体能力，不可据此锁定制品或启用外呼")
    print("=" * 60)

    print("\n事件记录:")
    for e in events:
        t = time.strftime("%H:%M:%S", time.localtime(e["time"]))
        detail = e.get("data", e.get("bytes", e.get("error", e.get("accepted", ""))))
        print(f"  [{t}] {e['type']}: {detail}")

    server.close()
    writer.close()
    try:
        await writer.wait_closed()
    except Exception as exc:
        print(f"[探针] 等待 ESL 连接关闭失败: {exc}")


if __name__ == "__main__":
    asyncio.run(main())
