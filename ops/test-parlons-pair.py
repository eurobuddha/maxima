#!/usr/bin/env python3
"""Offline checks with synthetic pairing files. No live code or server required."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('parlons-pair')

class PairingHelperTest(unittest.TestCase):
    def test_invite_and_qr_share_current_address_and_code(self):
        with tempfile.TemporaryDirectory() as folder:
            data = Path(folder)
            address = 'MAX#0xTEST#MxTEST@192.0.2.1:8001'
            code = 'ABCD-EFGH-JKLM'
            (data / 'account.txt').write_text(address+'\n')
            (data / 'pair-code.txt').write_text(code+'\n')
            # Stale invite must never override current account/code files.
            (data / 'invite.txt').write_text('MAX#stale?code=OLD')
            fake = data / 'qrencode'
            fake.write_text('#!/bin/sh\ncat > "$QR_CAPTURE"\n')
            fake.chmod(0o755)
            env = dict(os.environ, PATH=str(data)+os.pathsep+os.environ['PATH'], QR_CAPTURE=str(data/'qr'))
            output = subprocess.check_output([str(SCRIPT), folder], env=env, text=True)
            invite = address+'?code='+code
            self.assertIn(invite, output)
            self.assertEqual(invite, (data/'qr').read_text())
            (data / 'pair-code.txt').unlink()
            output = subprocess.check_output([str(SCRIPT), folder], env=env, text=True)
            self.assertIn('No unused pairing code', output)
            self.assertNotIn(code, output)
            self.assertNotIn('stale', output)

    def test_missing_account_fails_clearly(self):
        with tempfile.TemporaryDirectory() as folder:
            result = subprocess.run([str(SCRIPT), folder], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('not ready or readable', result.stderr)

if __name__ == '__main__':
    unittest.main()
