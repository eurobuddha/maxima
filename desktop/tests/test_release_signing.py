import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]

def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'desktop' / (name + '.py'))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded

check = module('release-check').check
sign = module('sign-linux').sign

class ReleaseConfigurationTest(unittest.TestCase):
    def test_missing_credentials_stop_every_platform_without_echoing_values(self):
        for platform, variables in module('release-check').REQUIRED.items():
            for missing in variables:
                env = {'RELEASE_PLATFORM': platform, **dict.fromkeys(variables, 'synthetic-private-value')}
                del env[missing]
                with self.assertRaises(ValueError) as error:
                    check(ROOT, env)
                self.assertIn(missing, str(error.exception))
                self.assertNotIn('synthetic-private-value', str(error.exception))

    def test_matching_metadata_and_tag_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = root / 'desktop/src/main/java/com/eurobuddha/maxima/desktop/DesktopMain.java'
            main.parent.mkdir(parents=True)
            (root/'desktop/build.gradle').write_text("version = '1.2.3'\n")
            main.write_text('public static final String APP_VERSION = "1.2.3";')
            env = {'RELEASE_PLATFORM':'win', 'WINDOWS_CERT_PFX':'synthetic', 'WINDOWS_CERT_PASSWORD':'synthetic',
                   'GITHUB_REF':'refs/tags/desktop-v1.2.3'}
            self.assertEqual('1.2.3', check(root, env))
            env['GITHUB_REF'] = 'refs/tags/desktop-v1.2.4'
            with self.assertRaisesRegex(ValueError, 'tag'): check(root, env)
            env['GITHUB_REF'] = 'refs/heads/main'
            main.write_text('public static final String APP_VERSION = "1.2.4";')
            with self.assertRaisesRegex(ValueError, 'metadata'): check(root, env)

class LinuxSignatureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='pg-test-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.home = cls.root/'gnupg'; cls.home.mkdir(mode=0o700)
        cls.env = dict(os.environ, GNUPGHOME=str(cls.home))
        cls.addClassCleanup(lambda: subprocess.run(['gpgconf','--homedir',str(cls.home),'--kill','gpg-agent'],capture_output=True))
        cls.password = b'isolated test passphrase'
        cls.gpg('--pinentry-mode','loopback','--passphrase-fd','0','--quick-generate-key',
                'Parlons synthetic test <test@example.invalid>','ed25519','sign','1d',data=cls.password)
        records = cls.gpg('--with-colons','--list-secret-keys').decode().splitlines()
        cls.fingerprint = next(row.split(':')[9] for row in records if row.startswith('fpr:'))
        cls.key = cls.gpg('--pinentry-mode','loopback','--passphrase-fd','0','--armor',
                          '--export-secret-keys',cls.fingerprint,data=cls.password).decode()
        cls.config = {'LINUX_SIGNING_KEY':cls.key,'LINUX_SIGNING_FINGERPRINT':cls.fingerprint,
                      'LINUX_SIGNING_PASSPHRASE':cls.password.decode()}

    @classmethod
    def gpg(cls,*arguments,data=None):
        return subprocess.run(['gpg','--batch',*arguments],env=cls.env,input=data,
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout

    def setUp(self):
        self.output = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(self.output.cleanup)
        self.installer = Path(self.output.name)/'maximanode_1.2.3-1_amd64.deb'
        self.installer.write_bytes(b'Synthetic installer bytes; no executable payload')
        self.signature = self.installer.with_name(self.installer.name+'.asc')

    def test_detached_signature_verifies_and_rejects_tampering(self):
        with patch.dict(os.environ,self.config): sign(self.installer,'1.2.3')
        self.gpg('--verify',str(self.signature),str(self.installer))
        self.assertIn(b'BEGIN PGP PUBLIC KEY BLOCK',(self.installer.parent/'Parlons-signing-key.asc').read_bytes())
        self.installer.write_bytes(b'changed bytes')
        with self.assertRaises(subprocess.CalledProcessError): self.gpg('--verify',str(self.signature),str(self.installer))

    def test_wrong_password_or_identity_never_leaves_publishable_outputs(self):
        for change in [{'LINUX_SIGNING_PASSPHRASE':'wrong'}, {'LINUX_SIGNING_FINGERPRINT':'A'*40}]:
            with patch.dict(os.environ,{**self.config,**change}):
                with self.assertRaises((ValueError,subprocess.CalledProcessError)): sign(self.installer,'1.2.3')
            self.assertFalse(self.signature.exists())
            self.assertFalse((self.installer.parent/'Parlons-signing-key.asc').exists())

    def test_signature_cannot_be_overwritten_or_misversioned(self):
        with patch.dict(os.environ,self.config):
            with self.assertRaisesRegex(ValueError,'versioned'): sign(self.installer,'1.2.30')
            similar = self.installer.with_name('maximanode_1.2.30-1_amd64.deb')
            similar.write_bytes(b'synthetic')
            with self.assertRaisesRegex(ValueError,'versioned'): sign(similar,'1.2.3')
            sign(self.installer,'1.2.3')
            before = self.signature.read_bytes()
            with self.assertRaisesRegex(ValueError,'already exist'): sign(self.installer,'1.2.3')
            self.assertEqual(before,self.signature.read_bytes())

if __name__ == '__main__': unittest.main()
