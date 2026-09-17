#!/usr/bin/env python3
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('inventory',Path(__file__).parents[2]/'deploy/postgis/verify-grids.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class InventoryTest(unittest.TestCase):
 def test_checks_actual_bytes_and_requires_documented_license_and_area(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);grid=root/'synthetic.gsb';grid.write_bytes(b'fixture')
   m={'schemaVersion':1,'grids':{'synthetic.gsb':hashlib.sha256(b'fixture').hexdigest()},'metadata':{'synthetic.gsb':{'version':'test','licenseRef':'CC0 fixture','provenanceRef':'test','areaOfUse':'test only'}}}
   manifest=root/'manifest.json';manifest.write_text(json.dumps(m))
   self.assertEqual(module.verify(root),'sha256:'+hashlib.sha256(manifest.read_bytes()).hexdigest())
   grid.write_bytes(b'changed')
   with self.assertRaises(ValueError):module.verify(root)
   grid.unlink()
   with self.assertRaises(ValueError):module.verify(root)
   grid.write_bytes(b'fixture');m['metadata']['synthetic.gsb'].pop('licenseRef');manifest.write_text(json.dumps(m))
   with self.assertRaises(ValueError):module.verify(root)
 def test_symlink_and_path_escape_are_rejected(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);m={'schemaVersion':1,'grids':{'../escape.gsb':'a'*64}};(root/'manifest.json').write_text(json.dumps(m))
   with self.assertRaises(ValueError):module.verify(root)
if __name__=='__main__':unittest.main()
