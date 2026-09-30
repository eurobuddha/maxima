#!/usr/bin/env python3
"""Offline checks with synthetic pairing files. No live code or server required."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('parlons-pair')

class PairingHelperTest(unittest.TestCase):
    def test_empty_password_selects_guest_and_wrong_password_never_falls_back(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            tenants = root / 'tenants'; tenants.mkdir()
            guest = tenants / 'alice'; guest.mkdir()
            (guest / 'account.txt').write_text('MAX#GUEST#MxTEST@192.0.2.1:8001')
            (guest / 'pair-code.txt').write_text('ABCD-EFGH-JKLM')
            java = root / 'java'
            java.write_text('''#!/bin/sh
if [ "$1" = "-cp" ]; then exit "$PASSWORD_RESULT"; fi
printf '%s\\n' "$*" > "$JAVA_CAPTURE"
'''); java.chmod(0o755)
            # The helper is portable to CI running as root: JVM behavior is mocked here.
            ident = root / 'id'; ident.write_text('#!/bin/sh\necho 1000\n'); ident.chmod(0o755)
            env = dict(os.environ, PATH=folder+os.pathsep+os.environ['PATH'],
                       PASSWORD_RESULT='2', JAVA_CAPTURE=str(root/'java-call'))
            cmd = [str(SCRIPT), '--data', folder, '--tenants-dir', str(tenants)]
            result = subprocess.run(cmd, input='alice\n', capture_output=True, text=True, env=env)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('MAX#GUEST', result.stdout)
            self.assertIn('--tenant-new', (root/'java-call').read_text())
            (root/'java-call').unlink()
            env['PASSWORD_RESULT'] = '1'
            result = subprocess.run(cmd, input='alice\n', capture_output=True, text=True, env=env)
            self.assertEqual(1, result.returncode)
            self.assertFalse((root/'java-call').exists())
            self.assertNotIn('MAX#GUEST', result.stdout)
            env['PASSWORD_RESULT'] = '2'
            result = subprocess.run(cmd+['--admin'], capture_output=True, text=True, env=env)
            self.assertEqual(2, result.returncode)
            self.assertFalse((root/'java-call').exists())
            result = subprocess.run(cmd+['--guest', '../owner'], capture_output=True, text=True, env=env)
            self.assertEqual(2, result.returncode)
            self.assertFalse((root/'java-call').exists())

    def test_invite_and_qr_share_current_address_and_code(self):
        with tempfile.TemporaryDirectory() as folder:
            data = Path(folder)
            address = 'MAX#0xTEST#MxTEST@192.0.2.1:8001'
            code = 'ABCD-EFGH-JKLM'
            (data / 'account.txt').write_text(address+'\n')
            (data / 'pair-code.txt').write_text(code+'\n')
            # Stale invite must never override current account/code files.
            (data / 'invite.txt').write_text('MAX#stale?code=OLD')
            java = data / 'java'
            java.write_text('#!/bin/sh\nexit 0\n')  # password verified by the separately tested JVM guard
            java.chmod(0o755)
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
            fake = Path(folder) / 'java'
            fake.write_text('#!/bin/sh\nexit 0\n'); fake.chmod(0o755)
            env = dict(os.environ, PATH=folder+os.pathsep+os.environ['PATH'])
            result = subprocess.run([str(SCRIPT), folder], env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('not ready or readable', result.stderr)

if __name__ == '__main__':
    unittest.main()
