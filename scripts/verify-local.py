"""Real HTTP and process-restart acceptance. Creates only new synthetic activities; keeps server running."""
import json, subprocess, time, uuid, urllib.request, urllib.error
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:8082'
checks = []
def api(route, body=None, key=None):
    headers={'Content-Type':'application/json'}
    if key: headers['X-Request-Id']=key
    req=urllib.request.Request(BASE+route, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=10) as r: return r.status,json.load(r)
    except urllib.error.HTTPError as e: return e.code,json.load(e)
def check(name, condition, detail=None):
    checks.append({'name':name,'passed':bool(condition),'detail':detail})
    if not condition: raise AssertionError(name+': '+str(detail))
def run(script, *options):
    subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts'/script),*options],cwd=ROOT,check=True,timeout=90)
def await_granted(claim):
    for _ in range(100):
        result=api('/api/claims/'+claim)[1]
        if result.get('granted') and result.get('status')=='SUCCESS': return result
        time.sleep(.1)
    raise AssertionError('grant did not recover: '+claim)
def main():
    check('actual Redis adapter',api('/api/metrics')[1]['stockImpl']=='redis-lua')
    status,a=api('/api/activities',{'name':'重启与补偿验收','totalStock':20,'perUserLimit':1,'ttlSeconds':86400})
    check('create persisted activity',status==200,a)
    activity=a['activityId'];request=str(uuid.uuid4())
    api('/api/demo/message-failure?count=20',{})
    status,out=api('/api/activities/'+activity+'/claim',{'userId':'restart-user'},request)
    check('pending claim reports HTTP 202',status==202 and out['code']=='PROCESSING',out)
    before=api('/api/claims/'+out['claimNo'])[1]
    check('failed transport has no grant yet',not before['granted'],before)
    # Force-stop the owned JVM; do not touch unrelated processes. Also shut down owned Redis.
    run('stop.ps1','-IncludeRedis')
    run('start.ps1','-Redis','-NoBrowser')
    restored=await_granted(out['claimNo'])
    check('outbox resumes after real process restart',restored['status']=='SUCCESS',restored)
    detail=api('/api/activities/'+activity)[1]
    check('stock and committed records survive restart',detail['dbStock']==19 and detail['cacheStock']==19 and detail['successClaims']==1,detail)
    status,again=api('/api/activities/'+activity+'/claim',{'userId':'restart-user'},request)
    check('same request remains idempotent after restart',status==200 and again['claimNo']==out['claimNo'],again)
    status,over=api('/api/activities/'+activity+'/claim',{'userId':'restart-user'},str(uuid.uuid4()))
    check('user limit survives restart',over['code']=='LIMIT_EXCEEDED',over)
    status,conflict=api('/api/activities/'+activity+'/claim',{'userId':'other-user'},request)
    check('identity conflict is HTTP 409',status==409,conflict)
    # Also prove a cold, non-persistent memory cache can recover from the same durable DB.
    run('stop.ps1')
    run('start.ps1','-NoBrowser')
    detail=api('/api/activities/'+activity)[1]
    check('cold memory cache rebuilds persisted stock',detail['stockImpl']=='in-memory' and detail['cacheStock']==19,detail)
    status,over=api('/api/activities/'+activity+'/claim',{'userId':'restart-user'},str(uuid.uuid4()))
    check('cold memory cache rebuilds user limits',over['code']=='LIMIT_EXCEEDED',over)
    run('stop.ps1')
    run('start.ps1','-Redis','-NoBrowser')
    check('final deployment uses Redis',api('/api/metrics')[1]['stockImpl']=='redis-lua')
    check('all activities reconcile',api('/api/reconcile/report')[1]['balanced'])

try:
    main()
finally:
    report={'createdAt':datetime.now(timezone.utc).isoformat(),'baseUrl':BASE,'checks':checks,'passed':bool(checks) and all(c['passed'] for c in checks)}
    (ROOT/'reports').mkdir(exist_ok=True)
    (ROOT/'reports'/'local-deployment.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
