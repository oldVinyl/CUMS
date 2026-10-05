import hashlib
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import receipt_archive as archive

class ReceiptArchiveTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"APP_RECEIPT_S3_BUCKET": "synthetic-private-bucket", "APP_RECEIPT_S3_PREFIX": "receipts"})
        self.env.start()
        self.addCleanup(self.env.stop)
    def test_invalid_config_and_metadata_are_rejected(self):
        with patch.dict(os.environ, {"APP_RECEIPT_S3_PREFIX": "../outside"}):
            with self.assertRaises(ValueError): archive.config()
        with patch.object(archive, "run", return_value=b"../outside|bad|99999999"):
            with self.assertRaises(ValueError): archive.references()
    def test_complete_inventory_is_verified_before_upload(self):
        key = "11111111-1111-1111-1111-111111111111"
        digest = hashlib.sha256(b"synthetic").hexdigest()
        with tempfile.TemporaryDirectory() as folder:
            directory = pathlib.Path(folder)
            (directory / key).write_bytes(b"wrong")
            with patch.object(archive, "empty"), patch.object(archive, "references", return_value=[(key,digest,9)]), patch.object(archive,"run") as run:
                with self.assertRaises(ValueError): archive.restore(directory)
                run.assert_not_called()
                (directory / key).write_bytes(b"synthetic")
                archive.restore(directory)
                args=run.call_args.args[0]
                self.assertIn("--if-none-match",args)
                self.assertIn("AES256",args)
    def test_existing_objects_block_restore(self):
        with patch.object(archive, "run", return_value=b'{"Contents":[{"Key":"receipts/synthetic"}]}'):
            with self.assertRaises(ValueError): archive.empty()
    def test_endpoint_is_scoped_and_subprocess_errors_are_not_reflected(self):
        from subprocess import CompletedProcess
        with patch.dict(os.environ, {"AWS_ENDPOINT_URL":"https://backup.invalid","APP_RECEIPT_S3_ENDPOINT":"https://receipts.invalid"}), patch.object(archive.subprocess,"run",return_value=CompletedProcess([],1,b"",b"PRIVATE_SECRET")) as run:
            with self.assertRaisesRegex(RuntimeError,"^Private receipt snapshot operation failed.$"):archive.run(["aws","s3api"])
            env=run.call_args.kwargs["env"]
            self.assertNotIn("AWS_ENDPOINT_URL",env)
            self.assertEqual(env["AWS_ENDPOINT_URL_S3"],"https://receipts.invalid")
