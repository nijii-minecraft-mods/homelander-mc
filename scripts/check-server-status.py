#!/usr/bin/env python3
"""Query Minecraft Java server status over its loopback-published TCP port."""
import json
import socket
import struct
import sys


def varint(value):
    result = bytearray()
    while True:
        byte = value & 0x7f
        value >>= 7
        result.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(result)


def read_exact(sock, length):
    data = bytearray()
    while len(data) < length:
        part = sock.recv(length - len(data))
        if not part:
            raise RuntimeError("Minecraft closed the status connection")
        data.extend(part)
    return bytes(data)


def read_varint(sock):
    result = 0
    for shift in range(0, 35, 7):
        byte = read_exact(sock, 1)[0]
        result |= (byte & 0x7f) << shift
        if not byte & 0x80:
            return result
    raise RuntimeError("Invalid Minecraft VarInt")


def main():
    port = int(sys.argv[1])
    host = b"localhost"
    handshake = b"\x00" + varint(767) + varint(len(host)) + host + struct.pack(">H", port) + b"\x01"
    with socket.create_connection(("127.0.0.1", port), timeout=15) as sock:
        sock.sendall(varint(len(handshake)) + handshake + b"\x01\x00")
        packet_length = read_varint(sock)
        if packet_length > 1024 * 1024:
            raise RuntimeError("Unexpectedly large status packet")
        if read_varint(sock) != 0:
            raise RuntimeError("Unexpected status packet ID")
        status = json.loads(read_exact(sock, read_varint(sock)))
    if status["version"]["protocol"] != 767 or "1.21.1" not in status["version"]["name"]:
        raise RuntimeError(f"Wrong server version: {status['version']}")
    print(json.dumps(status, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
