"""Authenticated PocketPad datagram framing shared with the Android app."""

from __future__ import annotations

import hashlib
import hmac
import struct
import time
from dataclasses import dataclass

MAGIC = b"PPD1"
VERSION = 1
BODY = struct.Struct("<4sB16sIQIhhhhBB")
PACKET_SIZE = BODY.size + hashlib.sha256().digest_size


@dataclass(frozen=True)
class ControllerState:
    buttons: int
    left_x: int
    left_y: int
    right_x: int
    right_y: int
    left_trigger: int
    right_trigger: int

    @classmethod
    def neutral(cls) -> "ControllerState":
        return cls(0, 0, 0, 0, 0, 0, 0)


@dataclass(frozen=True)
class Frame:
    session_id: bytes
    sequence: int
    timestamp_ms: int
    state: ControllerState


def session_id(key: bytes) -> bytes:
    if len(key) != 32:
        raise ValueError("Pairing key must be 32 bytes.")
    return hashlib.sha256(key).digest()[:16]


def encode(state: ControllerState, sequence: int, timestamp_ms: int, key: bytes) -> bytes:
    body = BODY.pack(
        MAGIC,
        VERSION,
        session_id(key),
        sequence & 0xFFFFFFFF,
        timestamp_ms,
        state.buttons & 0xFFFFFFFF,
        state.left_x,
        state.left_y,
        state.right_x,
        state.right_y,
        state.left_trigger,
        state.right_trigger,
    )
    return body + hmac.new(key, body, hashlib.sha256).digest()


def decode(packet: bytes, key: bytes, now_ms: int | None = None) -> Frame:
    if len(key) != 32:
        raise ValueError("Pairing key must be 32 bytes.")
    if len(packet) != PACKET_SIZE:
        raise ValueError("Invalid packet size.")
    body, signature = packet[: BODY.size], packet[BODY.size :]
    if not hmac.compare_digest(signature, hmac.new(key, body, hashlib.sha256).digest()):
        raise ValueError("Invalid packet signature.")
    magic, version, sid, sequence, timestamp, buttons, lx, ly, rx, ry, lt, rt = BODY.unpack(body)
    if magic != MAGIC or version != VERSION:
        raise ValueError("Unsupported protocol header or version.")
    if not hmac.compare_digest(sid, session_id(key)):
        raise ValueError("Session does not match.")
    current_time = int(time.time() * 1000) if now_ms is None else now_ms
    if abs(current_time - timestamp) > 5000:
        raise ValueError("Stale controller packet.")
    return Frame(sid, sequence, timestamp, ControllerState(buttons, lx, ly, rx, ry, lt, rt))
