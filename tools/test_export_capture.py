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


if __name__ == "__main__":
    unittest.main()
