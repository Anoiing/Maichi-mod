import gzip
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import export_capture


class ExportPermissionsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name) / "monitor.tsv.gz"
        self.household = export_capture.Household(
            export_capture.DEMO_INDOOR_IP, export_capture.DEMO_PEER_IP,
            export_capture.DEMO_INDOOR_ID, export_capture.DEMO_PEER_ID,
        )
        self.payload = b"\x02" + export_capture.DEMO_PEER_ID + bytes(22)
        packet = export_capture.Datagram(
            export_capture.Decimal("1"), self.household.peer_ip, self.household.indoor_ip,
            10008, 10008, self.payload,
        )
        mock_packets = patch.object(export_capture, "datagrams", return_value=[packet])
        mock_packets.start()
        self.addCleanup(mock_packets.stop)

    def export(self):
        return export_capture.export("tshark", Path("synthetic.pcapng"), self.output, self.household, True)

    @unittest.skipUnless(os.name == "posix", "POSIX file permissions")
    def test_new_output_is_private_with_a_shared_default_umask(self):
        previous = os.umask(0o022)
        self.addCleanup(os.umask, previous)
        self.assertEqual(1, self.export())
        self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))
        self.assertEqual(
            f"0\trx\t10008\t192.0.2.20\t10008\t{self.payload.hex()}\n",
            gzip.decompress(self.output.read_bytes()).decode("ascii"),
        )

    @unittest.skipUnless(os.name == "posix", "POSIX file permissions")
    def test_replaces_previously_readable_output_with_private_output(self):
        self.output.write_bytes(b"old output")
        self.output.chmod(0o644)
        self.export()
        self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_failed_write_preserves_previous_output_and_removes_temporary_file(self):
        self.output.write_bytes(b"previous complete output")
        with patch.object(export_capture.gzip, "GzipFile", side_effect=OSError("simulated write failure")):
            with self.assertRaises(OSError):
                self.export()
        self.assertEqual(b"previous complete output", self.output.read_bytes())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    @unittest.skipUnless(os.name == "posix", "POSIX symbolic links")
    def test_replacing_a_symlink_keeps_its_target_unchanged(self):
        target = self.output.parent / "other-user-file"
        target.write_bytes(b"keep this file")
        target.chmod(0o644)
        self.output.symlink_to(target)
        self.export()
        self.assertFalse(self.output.is_symlink())
        self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))
        self.assertEqual(b"keep this file", target.read_bytes())
        self.assertEqual(0o644, stat.S_IMODE(target.stat().st_mode))


class GarageCaptureTest(unittest.TestCase):
    def setUp(self):
        self.household = export_capture.Household(
            export_capture.DEMO_INDOOR_IP, export_capture.DEMO_PEER_IP,
            export_capture.DEMO_INDOOR_ID, export_capture.DEMO_PEER_ID,
        )

    def packets(self, last_digit, peer_address):
        peer = export_capture.Household(
            self.household.indoor_ip, export_capture.IPv4Address(peer_address),
            self.household.indoor_id, f"M0000000000{last_digit}".encode("ascii"),
        )
        discovery = export_capture.Datagram(
            export_capture.Decimal("1"), peer.peer_ip, peer.indoor_ip, 10008, 10008,
            b"\x02" + peer.peer_id + bytes(22),
        )
        payload = (b"PENGUIN0\xb8\x00\x02\x00\x00\x00\xac\x00" + bytes(16)
                   + peer.indoor_id.ljust(20, b"\0") + peer.indoor_ip.packed
                   + peer.peer_id.ljust(20, b"\0") + peer.peer_ip.packed)
        busy = export_capture.Datagram(
            export_capture.Decimal("1.1"), peer.peer_ip, peer.indoor_ip, 10000, 10000, payload,
        )
        return peer, discovery, busy

    def test_extracts_each_discovered_station_once_in_capture_order(self):
        first, first_query, first_busy = self.packets(2, "192.0.2.21")
        second, second_query, second_busy = self.packets(3, "192.0.2.22")
        packets = [first_query, first_busy, first_query, second_query, second_busy]
        with patch.object(export_capture, "datagrams", return_value=packets):
            self.assertEqual([first, second], export_capture.garage_households("tshark", Path("garage.pcapng"), self.household))

    def test_an_incomplete_capture_cannot_assign_both_garage_sources(self):
        _, query, busy = self.packets(2, "192.0.2.21")
        with patch.object(export_capture, "datagrams", return_value=[query, busy]):
            with self.assertRaises(ValueError):
                export_capture.garage_households("tshark", Path("garage.pcapng"), self.household)

    def test_normalizes_station_identity_and_preserves_the_observed_busy_length(self):
        peer, _, busy = self.packets(2, "192.0.2.21")
        normalized = export_capture.normalise(busy, peer, True)
        self.assertEqual(80, len(normalized))
        self.assertEqual(b"\xac\x00", normalized[14:16])
        self.assertEqual(export_capture.DEMO_PEER_ID, normalized[56:68])
        self.assertEqual(export_capture.DEMO_PEER_IP.packed, normalized[76:80])


class CallCaptureTest(unittest.TestCase):
    def setUp(self):
        self.household = export_capture.Household(
            export_capture.IPv4Address("192.0.2.11"), export_capture.IPv4Address("192.0.2.21"),
            b"S00000000002", b"M00000000002",
        )

    def packet(self, port, payload, incoming):
        h = self.household
        return export_capture.Datagram(export_capture.Decimal("1"),
            h.peer_ip if incoming else h.indoor_ip, h.indoor_ip if incoming else h.peer_ip,
            port, port, payload)

    def test_call_headers_normalize_the_caller_before_the_indoor_identity(self):
        h = self.household
        raw = (b"PENGUIN0\xb7\x00\x05\x00\x00\x00\x50\x00" + bytes(16)
               + h.peer_id.ljust(20, b"\0") + h.peer_ip.packed
               + h.indoor_id.ljust(20, b"\0") + h.indoor_ip.packed)
        normalized = export_capture.normalise(self.packet(10000, raw, False), h, False)
        self.assertIsNotNone(normalized)
        self.assertEqual(export_capture.DEMO_PEER_ID, normalized[32:44])
        self.assertEqual(export_capture.DEMO_INDOOR_ID, normalized[56:68])
        wrong = raw[:56] + b"S00000000003" + raw[68:]
        self.assertIsNone(export_capture.normalise(self.packet(10000, wrong, False), h, False))

    def test_indoor_search_and_roster_are_normalized(self):
        h = self.household
        query = b"\x01" + h.indoor_id + bytes(22)
        result = export_capture.normalise(self.packet(10008, query, True), h, True)
        self.assertEqual(b"\x01" + export_capture.DEMO_INDOOR_ID + bytes(22), result)
        roster = bytearray(898)
        roster[:16] = b"PENGUIN0\x98\x00\x02\x00\x00\x00\x82\x03"
        roster[32] = 1
        roster[34:54] = h.indoor_id.ljust(20, b"\0")
        roster[54:58] = h.indoor_ip.packed
        result = export_capture.normalise(self.packet(10000, bytes(roster), False), h, False)
        self.assertIsNotNone(result)
        self.assertEqual(export_capture.DEMO_INDOOR_ID, result[34:46])
        self.assertEqual(export_capture.DEMO_INDOOR_IP.packed, result[54:58])


if __name__ == "__main__":
    unittest.main()
