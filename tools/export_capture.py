#!/usr/bin/env python3
"""Extract the household's UDP exchanges for the app's local debug assets."""

import argparse
from dataclasses import dataclass
from decimal import Decimal
import gzip
import io
from ipaddress import IPv4Address
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


DEMO_INDOOR_IP = IPv4Address("192.0.2.10")
DEMO_PEER_IP = IPv4Address("192.0.2.20")
DEMO_BROADCAST = IPv4Address("192.0.2.255")
DEMO_INDOOR_ID = b"S00000000001"
DEMO_PEER_ID = b"M00000000001"


@dataclass(frozen=True)
class Datagram:
    time: Decimal
    source: IPv4Address
    destination: IPv4Address
    source_port: int
    destination_port: int
    payload: bytes


@dataclass(frozen=True)
class Household:
    indoor_ip: IPv4Address
    peer_ip: IPv4Address
    indoor_id: bytes
    peer_id: bytes


def datagrams(tshark: str, capture: Path, display_filter: str) -> list[Datagram]:
    fields = ("frame.time_relative", "ip.src", "ip.dst", "udp.srcport", "udp.dstport", "udp.payload")
    command = [tshark, "-n", "-r", str(capture), "-Y", display_filter, "-T", "fields"]
    command.extend(value for field in fields for value in ("-e", field))
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    packets = []
    for row in result.stdout.splitlines():
        time, source, destination, source_port, destination_port, payload = row.split("\t")
        if source and destination and payload:
            packets.append(Datagram(Decimal(time), IPv4Address(source), IPv4Address(destination),
                                    int(source_port), int(destination_port), bytes.fromhex(payload)))
    return packets


def household_from_elevator(tshark: str, capture: Path) -> Household:
    packets = datagrams(tshark, capture, "ip && (udp.port == 10000 || udp.port == 10008)")
    requests = [packet for packet in packets if packet.source_port == packet.destination_port == 10000
                and len(packet.payload) == 74 and packet.payload.startswith(b"PENGUIN0\x06\x01\x01\x00")]
    if len(requests) != 1:
        raise ValueError("rj3-ev.pcapng must contain one 74-byte elevator request")
    request = requests[0]
    queries = [packet for packet in packets if packet.source == request.source
               and packet.source_port == packet.destination_port == 10008 and len(packet.payload) == 35
               and packet.payload[0] == 1 and packet.time <= request.time]
    if not queries:
        raise ValueError("Device discovery was not found before the elevator request")
    indoor_id = request.payload[32:44]
    peer_id = queries[-1].payload[1:13]
    if not re.fullmatch(rb"S[0-9]{11}", indoor_id) or not re.fullmatch(rb"M[0-9]{11}", peer_id):
        raise ValueError("The captured identifiers do not match the observed terminal format")
    return Household(request.source, request.destination, indoor_id, peer_id)


def normalise(packet: Datagram, household: Household, incoming: bool) -> bytes | None:
    raw = packet.payload
    payload = bytearray(raw)
    if packet.source_port == packet.destination_port == 10008:
        if len(raw) != 35 or raw[0] != (2 if incoming else 1) or raw[1:13] != household.peer_id:
            return None
        payload[1:13] = DEMO_PEER_ID
    elif packet.source_port == packet.destination_port == 10000 and raw.startswith(b"PENGUIN0"):
        if raw[8:10] == b"\xb8\x00" and len(raw) >= 80:
            if (raw[32:44] != household.indoor_id or raw[52:56] != household.indoor_ip.packed
                    or raw[56:68] != household.peer_id or raw[76:80] != household.peer_ip.packed):
                return None
            payload[32:52] = DEMO_INDOOR_ID.ljust(20, b"\0")
            payload[52:56] = DEMO_INDOOR_IP.packed
            payload[56:76] = DEMO_PEER_ID.ljust(20, b"\0")
            payload[76:80] = DEMO_PEER_IP.packed
        elif raw[8:10] == b"\x06\x01":
            if incoming and len(raw) == 32 and raw[10:12] == b"\x02\x00":
                pass
            elif not incoming and len(raw) == 74 and raw[32:44] == household.indoor_id:
                payload[32:44] = DEMO_INDOOR_ID
            else:
                return None
        else:
            return None
    else:
        return None
    return bytes(payload)


def export(tshark: str, capture: Path, output: Path, household: Household, incoming: bool) -> int:
    display_filter = (f"ip.src == {household.peer_ip} && ip.dst == {household.indoor_ip}"
                      if incoming else
                      f"ip.src == {household.indoor_ip} && (ip.dst == {household.peer_ip} || udp.dstport == 10008)")
    display_filter += " && (udp.port == 10000 || udp.port == 10008)"
    rows = []
    for packet in datagrams(tshark, capture, display_filter):
        payload = normalise(packet, household, incoming)
        if payload is None:
            continue
        port = packet.destination_port if incoming else packet.source_port
        remote = DEMO_BROADCAST if not incoming and port == 10008 else DEMO_PEER_IP
        rows.append((packet.time, "rx" if incoming else "tx", port, remote, port, payload.hex()))
    if not rows:
        raise ValueError(f"No matching datagrams in {capture.name}")
    first_time = rows[0][0]
    temporary = tempfile.NamedTemporaryFile(dir=output.parent, prefix=f".{output.name}.", delete=False)
    temporary_path = Path(temporary.name)
    try:
        with temporary, io.TextIOWrapper(
            gzip.GzipFile(filename="", fileobj=temporary, mode="wb"), encoding="ascii", newline="\n",
        ) as stream:
            for time, *fields in rows:
                milliseconds = round((time - first_time) * 1000)
                print(milliseconds, *fields, sep="\t", file=stream)
        temporary_path.replace(output)
    finally:
        temporary_path.unlink(missing_ok=True)
    return len(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--tshark", default=shutil.which("tshark") or "/Applications/Wireshark.app/Contents/MacOS/tshark")
    options = parser.parse_args()
    household = household_from_elevator(options.tshark, options.input_dir / "rj3-ev.pcapng")
    options.output_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    captures = (
        ("video.pcapng", "monitor-rx.tsv.gz", True),
        ("rj4.pcapng", "monitor-rj4-rx.tsv.gz", True),
        ("rj3.pcapng", "monitor-tx.tsv.gz", False),
        ("rj3-ev.pcapng", "elevator-tx.tsv.gz", False),
        ("1111111.pcapng", "elevator-rx.tsv.gz", True),
    )
    for capture, output, incoming in captures:
        count = export(options.tshark, options.input_dir / capture, options.output_dir / output, household, incoming)
        print(f"{output}: {count} datagrams")


if __name__ == "__main__":
    main()
