"""Record test results and the deployed jar hash without copying local credentials."""
import json, hashlib, xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime, timezone
ROOT=Path(__file__).resolve().parents[1]
suites=[]
for path in sorted((ROOT/'target'/'surefire-reports').glob('TEST-*.xml')):
    root=ET.parse(path).getroot()
    suites.append({k:root.attrib[k] for k in ('name','tests','failures','errors','skipped')})
tests={k:sum(int(s[k]) for s in suites) for k in ('tests','failures','errors','skipped')}
deployment=json.loads((ROOT/'reports'/'local-deployment.json').read_text(encoding='utf-8'))
jar=ROOT/'target'/'novamarket-1.0.0.jar'
report={'createdAt':datetime.now(timezone.utc).isoformat(),'tests':tests,'suites':suites,
        'httpAndRestartChecks':len(deployment['checks']),'httpAndRestartPassed':deployment['passed'],
        'deployedJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
        'scope':'Windows single instance; actual Redis Lua + H2 file DB; in-process message bus; MySQL and RocketMQ not runtime-validated.'}
(ROOT/'reports'/'validation-summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
