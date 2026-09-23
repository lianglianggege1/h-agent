"""Headless SIP/RTP regression probe; calls only its own test SIP endpoint.

Run against an isolated FreeSWITCH. PROBE_HOST_FOR_FS must be an IPv4 address
reachable from FreeSWITCH. When using Docker, publish its RTP range and set
PROBE_FS_RTP_HOST to the Docker host address. No ASR/TTS credentials are needed.
Use --legacy-binary to reproduce the original silence (expected exit status 1).
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import math
import os
import re
import struct
import uuid

from aiohttp import web
from dotenv import load_dotenv

from realtime_voice.freeswitch_io import FreeswitchIO
from realtime_voice.freeswitch_playback import AudioForkPlayback, PlaybackEsl


def pcm_tone(seconds: float, frequency: int = 440) -> bytes:
    return b"".join(struct.pack("<h", int(9000 * math.sin(2 * math.pi * frequency * i / 16000)))
                    for i in range(int(16000 * seconds)))


def decode_ulaw(value: int) -> int:
    value = ~value & 0xFF
    sample = (((value & 15) << 3) + 132) << ((value >> 4) & 7)
    return 132 - sample if value & 128 else sample - 132


class RtpPeer(asyncio.DatagramProtocol):
    def __init__(self):
        self.samples: list[int] = []
        self.audible_packets = 0

    def datagram_received(self, data, _address):
        if len(data) < 12 or data[1] & 127 != 0:
            return
        offset = 12 + 4 * (data[0] & 15)
        if data[0] & 16:
            if len(data) < offset + 4:
                return
            offset += 4 + 4 * int.from_bytes(data[offset + 2:offset + 4], "big")
        payload = data[offset:]
        if data[0] & 32 and payload:
            payload = payload[:-payload[-1]]
        samples = [decode_ulaw(value) for value in payload]
        self.samples.extend(samples)
        if any(abs(value) > 500 for value in samples):
            self.audible_packets += 1


class SipPeer(asyncio.DatagramProtocol):
    def __init__(self, host: str, port: int, rtp_port: int):
        self.host, self.port, self.local_rtp_port = host, port, rtp_port
        self.remote_rtp_port = None
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        text = data.decode(errors="replace")
        if not text.startswith(("INVITE ", "BYE ", "OPTIONS ")):
            return
        lines = text.split("\r\n")
        headers = []
        for name in ("Via", "From", "To", "Call-ID", "CSeq"):
            line = next((line for line in lines if line.lower().startswith(name.lower() + ":")), None)
            if line is None:
                return
            if name == "To" and ";tag=" not in line:
                line += ";tag=h-agent-probe"
            headers.append(line)
        body = ""
        if text.startswith("INVITE "):
            match = re.search(r"m=audio (\d+)", text)
            if match is None:
                return
            self.remote_rtp_port = int(match[1])
            body = (f"v=0\r\no=probe 1 1 IN IP4 {self.host}\r\ns=probe\r\n"
                    f"c=IN IP4 {self.host}\r\nt=0 0\r\nm=audio {self.local_rtp_port} RTP/AVP 0\r\n"
                    "a=rtpmap:0 PCMU/8000\r\na=sendrecv\r\n")
        response = ("SIP/2.0 200 OK\r\n" + "\r\n".join(headers)
                    + f"\r\nContact: <sip:probe@{self.host}:{self.port}>\r\n"
                    + f"Content-Type: application/sdp\r\nContent-Length: {len(body)}\r\n\r\n" + body)
        self.transport.sendto(response.encode(), address)


async def verify(legacy_binary: bool):
    host = os.environ["PROBE_HOST_FOR_FS"]
    sip_port = int(os.getenv("PROBE_SIP_PORT", "15060"))
    rtp_port = int(os.getenv("PROBE_RTP_PORT", "19000"))
    ws_port = int(os.getenv("PROBE_WS_PORT", "18082"))
    profile = os.getenv("PROBE_SIP_PROFILE", "internal")
    fs_host = os.getenv("FS_ESL_HOST", "127.0.0.1")
    loop = asyncio.get_running_loop()
    rtp_transport, rtp = await loop.create_datagram_endpoint(RtpPeer, local_addr=("0.0.0.0", rtp_port))
    sip_transport, sip = await loop.create_datagram_endpoint(
        lambda: SipPeer(host, sip_port, rtp_port), local_addr=("0.0.0.0", sip_port))
    call_id = str(uuid.uuid4())
    esl = PlaybackEsl(fs_host, int(os.getenv("FS_ESL_PORT", "8021")), os.getenv("FS_ESL_PASSWORD", "ClueCon"))
    playback = AudioForkPlayback(call_id, esl)
    media = FreeswitchIO(call_id, playback=playback)
    app = web.Application()
    # Random route prevents unrelated clients from connecting during the probe.
    app.router.add_get(f"/media/{call_id}", media.serve)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", ws_port).start()

    async def send_silence():
        sequence = 0
        while True:
            if sip.remote_rtp_port:
                header = struct.pack("!BBHII", 0x80, 0, sequence % 65536, sequence * 160, 123)
                rtp_transport.sendto(header + b"\xff" * 160,
                                     (os.getenv("PROBE_FS_RTP_HOST", fs_host), sip.remote_rtp_port))
                sequence += 1
            await asyncio.sleep(0.02)

    silence_task = asyncio.create_task(send_silence())
    try:
        await playback.start()
        await esl.api(f"originate {{origination_uuid={call_id},absolute_codec_string=PCMU}}"
                      f"sofia/{profile}/probe@{host}:{sip_port} &park()")
        await esl.api(f"uuid_audio_fork {call_id} start ws://{host}:{ws_port}/media/{call_id} mono 16k {{}}")
        await media.wait_connected(5)
        tone = pcm_tone(1)
        if legacy_binary:
            await media._socket.send_bytes(tone)
            await asyncio.sleep(1.5)
        else:
            await media.send_audio(tone)
            result = await media.finish_playback("probe-turn", 1)
            assert result.status == "COMPLETED", result
        await asyncio.sleep(0.15)
        rms = math.sqrt(sum(value * value for value in rtp.samples) / max(1, len(rtp.samples)))
        print(f"RTP audible packets={rtp.audible_packets}, RMS={rms:.0f}, uplink frames={media.frame_count}")
        assert rtp.audible_packets >= 40 and rms > 1000, "Phone RTP endpoint received silence"
        assert media.frame_count > 0, "No upstream PCM"
        print("PASS: worker PCM reached the SIP endpoint through real RTP")

        before = rtp.audible_packets
        old_turn = asyncio.create_task(media.send_audio(pcm_tone(3)))
        async with asyncio.timeout(5):
            while rtp.audible_packets == before:
                await asyncio.sleep(0.01)
        await media.stop_playback()
        await asyncio.gather(old_turn, return_exceptions=True)
        assert old_turn.cancelled()
        await asyncio.sleep(0.2)  # Allow already-transmitted UDP packets to arrive.
        after_break = rtp.audible_packets
        await asyncio.sleep(0.6)
        assert rtp.audible_packets == after_break, "Old audio resumed after interruption"
        await media.send_audio(pcm_tone(0.4, 660))
        assert (await media.finish_playback("new-turn", 1)).status == "COMPLETED"
        await asyncio.sleep(0.1)
        assert rtp.audible_packets > after_break
        print("PASS: interruption stopped old RTP audio; next turn played successfully")
    finally:
        await media.close()
        with contextlib.suppress(Exception):
            await esl.api(f"uuid_kill {call_id}")
        # Give the local SIP endpoint time to acknowledge FreeSWITCH's BYE.
        await asyncio.sleep(0.1)
        silence_task.cancel()
        await asyncio.gather(silence_task, return_exceptions=True)
        sip_transport.close()
        rtp_transport.close()
        await runner.cleanup()


if __name__ == "__main__":
    load_dotenv()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-binary", action="store_true")
    asyncio.run(verify(parser.parse_args().legacy_binary))
