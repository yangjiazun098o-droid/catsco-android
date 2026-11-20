"""Import the UI weights verbatim from the user's PuHuiTi 2.0 archive."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
weights = {300: ('45_Light_45_Light', 'light'), 400: ('55_Regular_55_Regular', 'regular'), 500: ('65_Medium_65_Medium', 'medium'),
           600: ('75_SemiBold_75_SemiBold', 'semibold'), 700: ('55_Regular_85_Bold', 'bold')}
output = root / 'app/src/main/res/font'
output.mkdir(parents=True, exist_ok=True)
records = []
with zipfile.ZipFile(sys.argv[1]) as archive:
    for weight, (suffix, name) in weights.items():
        source = f'Alibaba_PuHuiTi_2.0_{suffix}.ttf'
        data = archive.read(source)
        target = f'alibaba_puhuiti_{name}.ttf'
        (output / target).write_bytes(data)
        records.append(dict(weight=weight, source=source, resource=target,
                            bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
(output / 'alibaba_puhuiti.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:android="http://schemas.android.com/apk/res/android">
''' + ''.join(f'    <font android:font="@font/alibaba_puhuiti_{name}" android:fontWeight="{weight}" android:fontStyle="normal" />\n'
              for weight, (_, name) in weights.items()) + '</font-family>\n', encoding='utf-8')
manifest = root / 'design-assets/fonts/manifest.json'
manifest.parent.mkdir(parents=True, exist_ok=True)
manifest.write_text(json.dumps(dict(family='Alibaba PuHuiTi 2.0', source=Path(sys.argv[1]).name,
                                   files=records), ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Imported {len(records)} original font weights; {sum(r["bytes"] for r in records):,} bytes.')
